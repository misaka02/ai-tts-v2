package com.aitts.v2.reader.parser

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.URLDecoder
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource

internal data class TocItemEntry(
    val path: String,
    val title: String,
    val depth: Int = 0,
    val fragment: String = ""
)

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
        val detected = CharsetDetector.detectMarkup(bytes, isTruncatedSample = false)
        val decoded = String(bytes, detected.bomLength, bytes.size - detected.bomLength, detected.charset)
        val raw = decoded.replace(BARE_AMP_RE, "&amp;")
        return parseXmlInternal(raw.toByteArray(Charsets.UTF_8))
    }

    private fun parseXmlInternal(bytes: ByteArray): Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        factory.newDocumentBuilder().apply {
            setEntityResolver(EntityResolver { _, _ -> InputSource(StringReader("")) })
        }.parse(ByteArrayInputStream(bytes))
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

    fun parseTocEntries(zip: ZipFile, opf: OpfPackage): Map<String, TocItemEntry> {
        val entries = LinkedHashMap<String, TocItemEntry>()
        opf.navPath?.let { navPath ->
            readZipEntryBytes(zip, navPath, MAX_METADATA_BYTES)?.let { bytes ->
                collectNavEntries(bytes, parentDir(navPath), entries)
            }
        }
        if (entries.isNotEmpty()) return entries
        opf.ncxPath?.let { ncxPath ->
            readZipEntryBytes(zip, ncxPath, MAX_METADATA_BYTES)?.let { bytes ->
                collectNcxEntries(bytes, parentDir(ncxPath), entries)
            }
        }
        return entries
    }

    private fun collectNavEntries(bytes: ByteArray, navDir: String, out: MutableMap<String, TocItemEntry>) {
        val doc = parseXmlDocument(bytes)
        if (doc != null) {
            val navs = doc.getElementsByTagName("nav")
            val rootEl = (0 until navs.length).asSequence()
                .mapNotNull { navs.item(it) as? Element }
                .firstOrNull()
                ?: doc.documentElement
            if (rootEl != null) {
                walkNavList(rootEl, navDir, depth = -1, out = out)
            }
            if (out.isNotEmpty()) return
        }
        val linkRe = Regex(
            pattern = """<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val tagStripRe = Regex("<[^>]+>")
        val detected = CharsetDetector.detectMarkup(bytes, isTruncatedSample = false)
        val navHtml = String(bytes, detected.bomLength, bytes.size - detected.bomLength, detected.charset)
        for (match in linkRe.findAll(navHtml)) {
            val rawHref = match.groupValues[1]
            val path = normalizeZipPath(navDir, rawHref) ?: continue
            val frag = rawHref.substringAfter('#', "")
            val rawLabel = match.groupValues[2].replace(tagStripRe, " ")
            val label = EpubHtmlParser.decodeEntities(rawLabel).replace(Regex("\\s+"), " ").trim()
            if (label.isNotEmpty()) out.putIfAbsent(path, TocItemEntry(path, label, 0, frag))
        }
    }

    private fun walkNavList(el: Element, navDir: String, depth: Int, out: MutableMap<String, TocItemEntry>) {
        val children = el.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            val tag = child.tagName.substringAfter(':').lowercase()
            if (tag == "a") {
                val rawHref = child.getAttribute("href").trim()
                val label = child.textContent.replace(Regex("\\s+"), " ").trim()
                val path = normalizeZipPath(navDir, rawHref)
                if (path != null && label.isNotEmpty()) {
                    out.putIfAbsent(path, TocItemEntry(path, label, depth.coerceAtLeast(0), rawHref.substringAfter('#', "")))
                }
            } else {
                val nextDepth = if (tag == "ol" || tag == "ul") depth + 1 else depth
                walkNavList(child, navDir, nextDepth, out)
            }
        }
    }

    private fun collectNcxEntries(bytes: ByteArray, ncxDir: String, out: MutableMap<String, TocItemEntry>) {
        val doc = parseXmlDocument(bytes) ?: return
        val navMaps = doc.getElementsByTagName("navMap")
        val root = if (navMaps.length > 0) navMaps.item(0) as? Element else doc.documentElement
        if (root != null) {
            walkNcxPoints(root, ncxDir, depth = 0, out = out)
        }
    }

    private fun walkNcxPoints(parent: Element, ncxDir: String, depth: Int, out: MutableMap<String, TocItemEntry>) {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (!child.tagName.substringAfter(':').equals("navPoint", ignoreCase = true)) continue
            val label = firstChildTagText(child, "text")
            val contentEl = firstDescendant(child, "content")
            val rawSrc = contentEl?.getAttribute("src")?.trim().orEmpty()
            val path = normalizeZipPath(ncxDir, rawSrc)
            if (path != null && label.isNotEmpty()) {
                out.putIfAbsent(path, TocItemEntry(path, label, depth, rawSrc.substringAfter('#', "")))
            }
            walkNcxPoints(child, ncxDir, depth + 1, out)
        }
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
