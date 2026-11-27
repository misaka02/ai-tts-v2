package com.aitts.v2.reader.parser

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal data class ManifestEntry(
    val id: String,
    val path: String,
    val mediaType: String,
    val properties: String = ""
)

internal data class OpfPackage(
    val title: String,
    val author: String,
    val language: String,
    val description: String,
    val opfDir: String,
    val manifestById: Map<String, ManifestEntry>,
    val spineIds: List<String>,
    val coverPath: String?,
    val ncxPath: String?,
    val navPath: String?
)

/**
 * Hardened ZIP + XML metadata helpers for EPUB 2 (NCX) and EPUB 3 (OPF + Nav).
 */
internal object EpubXmlSupport {

    private const val MAX_METADATA_BYTES = 2 * 1024 * 1024
    private val BARE_AMP_RE = Regex("&(?!(?:amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)")

    fun normalizeZipPath(baseDir: String, relativePath: String): String? {
        val cleanHref = relativePath.substringBefore('#').substringBefore('?').trim()
        if (cleanHref.isEmpty()) return null
        val decoded = runCatching { URLDecoder.decode(cleanHref, Charsets.UTF_8.name()) }.getOrDefault(cleanHref)
        val combined = if (decoded.startsWith("/") || baseDir.isEmpty()) {
            decoded.trimStart('/')
        } else {
            "$baseDir/${decoded.trimStart('/')}"
        }
        val stack = ArrayDeque<String>()
        for (segment in combined.split('/', '\\')) {
            when {
                segment.isEmpty() || segment == "." -> Unit
                segment == ".." -> {
                    if (stack.isEmpty()) return null
                    stack.removeLast()
                }
                else -> stack.addLast(segment)
            }
        }
        return stack.joinToString("/")
    }

    fun parentDir(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx > 0) path.substring(0, idx) else ""
    }

    fun readZipEntryBytes(zip: ZipFile, path: String, maxBytes: Int): ByteArray? {
        val normalized = normalizeZipPath("", path) ?: return null
        val entry = zip.getEntry(normalized) ?: findCaseInsensitiveEntry(zip, normalized) ?: return null
        if (entry.isDirectory) return null
        zip.getInputStream(entry).use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                total += read
                if (total > maxBytes) return null
                out.write(buf, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun findCaseInsensitiveEntry(zip: ZipFile, path: String): java.util.zip.ZipEntry? {
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (e.name.equals(path, ignoreCase = true)) return e
        }
        return null
    }

    fun parseXmlDocument(bytes: ByteArray): Document? {
        val firstAttempt = parseXmlInternal(bytes)
        if (firstAttempt != null) return firstAttempt
        val raw = bytes.toString(Charsets.UTF_8).replace(BARE_AMP_RE, "&amp;")
        return parseXmlInternal(raw.toByteArray(Charsets.UTF_8))
    }

    private fun parseXmlInternal(bytes: ByteArray): Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }.getOrNull()

    fun findOpfPath(zip: ZipFile): String? {
        val containerBytes = readZipEntryBytes(zip, "META-INF/container.xml", MAX_METADATA_BYTES) ?: return null
        val doc = parseXmlDocument(containerBytes) ?: return null
        val rootfiles = doc.getElementsByTagName("rootfile")
        for (i in 0 until rootfiles.length) {
            val el = rootfiles.item(i) as? Element ?: continue
            val fullPath = el.getAttribute("full-path").trim()
            if (fullPath.isNotEmpty()) return normalizeZipPath("", fullPath)
        }
        return null
    }

    fun parseOpfPackage(zip: ZipFile, opfPath: String): OpfPackage? {
        val bytes = readZipEntryBytes(zip, opfPath, MAX_METADATA_BYTES) ?: return null
        val doc = parseXmlDocument(bytes) ?: return null
        val opfDir = parentDir(opfPath)
        val manifest = parseManifest(doc, opfDir)
        val spineIds = parseSpine(doc)
        val coverIdFromMeta = findCoverMetaId(doc)
        val coverEntry = manifest.values.firstOrNull { "cover-image" in it.properties }
            ?: coverIdFromMeta?.let { manifest[it] }
            ?: manifest["cover-image"]
            ?: manifest["cover"]?.takeIf { it.mediaType.startsWith("image/") }
        val navEntry = manifest.values.firstOrNull { "nav" in it.properties }
        val ncxEntry = manifest.values.firstOrNull { it.mediaType.equals("application/x-dtbncx+xml", true) }
            ?: manifest["ncx"]

        return OpfPackage(
            title = firstTagText(doc, "dc:title", "title"),
            author = firstTagText(doc, "dc:creator", "creator"),
            language = firstTagText(doc, "dc:language", "language"),
            description = firstTagText(doc, "dc:description", "description"),
            opfDir = opfDir,
            manifestById = manifest,
            spineIds = spineIds,
            coverPath = coverEntry?.path,
            ncxPath = ncxEntry?.path,
            navPath = navEntry?.path
        )
    }

    fun parseTocTitles(zip: ZipFile, opf: OpfPackage): Map<String, String> {
        val titles = LinkedHashMap<String, String>()
        opf.navPath?.let { navPath ->
            readZipEntryBytes(zip, navPath, MAX_METADATA_BYTES)?.let { bytes ->
                collectNavTitles(bytes, parentDir(navPath), titles)
            }
        }
        if (titles.isNotEmpty()) return titles
        opf.ncxPath?.let { ncxPath ->
            readZipEntryBytes(zip, ncxPath, MAX_METADATA_BYTES)?.let { bytes ->
                collectNcxTitles(bytes, parentDir(ncxPath), titles)
            }
        }
        return titles
    }

    private fun parseManifest(doc: Document, opfDir: String): Map<String, ManifestEntry> {
        val map = LinkedHashMap<String, ManifestEntry>()
        val items = doc.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val el = items.item(i) as? Element ?: continue
            val id = el.getAttribute("id").trim()
            val href = el.getAttribute("href").trim()
            val path = normalizeZipPath(opfDir, href) ?: continue
            if (id.isNotEmpty()) {
                map[id] = ManifestEntry(
                    id = id,
                    path = path,
                    mediaType = el.getAttribute("media-type").trim(),
                    properties = el.getAttribute("properties").trim()
                )
            }
        }
        return map
    }

    private fun parseSpine(doc: Document): List<String> {
        val ids = mutableListOf<String>()
        val refs = doc.getElementsByTagName("itemref")
        for (i in 0 until refs.length) {
            val el = refs.item(i) as? Element ?: continue
            val idref = el.getAttribute("idref").trim()
            if (idref.isNotEmpty()) ids.add(idref)
        }
        return ids
    }

    private fun findCoverMetaId(doc: Document): String? {
        val metas = doc.getElementsByTagName("meta")
        for (i in 0 until metas.length) {
            val el = metas.item(i) as? Element ?: continue
            if (el.getAttribute("name").equals("cover", ignoreCase = true)) {
                return el.getAttribute("content").trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun collectNavTitles(bytes: ByteArray, navDir: String, out: MutableMap<String, String>) {
        val doc = parseXmlDocument(bytes)
        if (doc != null) {
            val links = doc.getElementsByTagName("a")
            for (i in 0 until links.length) {
                val a = links.item(i) as? Element ?: continue
                val href = a.getAttribute("href").trim()
                val label = a.textContent.replace(Regex("\\s+"), " ").trim()
                val path = normalizeZipPath(navDir, href) ?: continue
                if (label.isNotEmpty() && !out.containsKey(path)) out[path] = label
            }
            if (out.isNotEmpty()) return
        }
        val linkRe = Regex(
            pattern = """<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val tagStripRe = Regex("<[^>]+>")
        for (match in linkRe.findAll(bytes.toString(Charsets.UTF_8))) {
            val path = normalizeZipPath(navDir, match.groupValues[1]) ?: continue
            val rawLabel = match.groupValues[2].replace(tagStripRe, " ")
            val label = EpubHtmlParser.decodeEntities(rawLabel).replace(Regex("\\s+"), " ").trim()
            if (label.isNotEmpty() && !out.containsKey(path)) out[path] = label
        }
    }

    private fun collectNcxTitles(bytes: ByteArray, ncxDir: String, out: MutableMap<String, String>) {
        val doc = parseXmlDocument(bytes) ?: return
        val navPoints: NodeList = doc.getElementsByTagName("navPoint")
        for (i in 0 until navPoints.length) {
            val np = navPoints.item(i) as? Element ?: continue
            val label = firstChildTagText(np, "text")
            val contentEl = firstDescendant(np, "content") ?: continue
            val src = contentEl.getAttribute("src").trim()
            val path = normalizeZipPath(ncxDir, src) ?: continue
            if (label.isNotEmpty() && !out.containsKey(path)) out[path] = label
        }
    }

    private fun firstTagText(doc: Document, vararg tagNames: String): String {
        for (name in tagNames) {
            val nodes = doc.getElementsByTagName(name)
            if (nodes.length > 0) {
                val txt = nodes.item(0)?.textContent?.trim().orEmpty()
                if (txt.isNotEmpty()) return txt
            }
        }
        return ""
    }

    private fun firstChildTagText(el: Element, tagName: String): String {
        val nodes = el.getElementsByTagName(tagName)
        return if (nodes.length > 0) nodes.item(0)?.textContent?.replace(Regex("\\s+"), " ")?.trim().orEmpty() else ""
    }

    private fun firstDescendant(el: Element, tagName: String): Element? {
        val nodes = el.getElementsByTagName(tagName)
        return if (nodes.length > 0) nodes.item(0) as? Element else null
    }
}
