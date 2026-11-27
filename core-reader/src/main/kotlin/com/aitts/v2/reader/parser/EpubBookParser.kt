package com.aitts.v2.reader.parser

import com.aitts.v2.reader.model.BookFormat
import com.aitts.v2.reader.model.BookIndex
import com.aitts.v2.reader.model.ChapterContent
import com.aitts.v2.reader.model.ChapterRef
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * EPUB 2 & EPUB 3 parser supporting ZIP verification, OPF metadata/cover extraction,
 * Nav/NCX TOC mapping, tolerant HTML parsing, and bounded binary resource access.
 */
class EpubBookParser : BookFormatParser {

    override val format: BookFormat = BookFormat.EPUB
    override val extensions: List<String> = BookFormat.EPUB.extensions

    override fun canParse(file: File, fileName: String): Boolean {
        if (!file.exists() || file.length() < 24) return false
        val magic = ByteArray(4)
        file.inputStream().use { if (it.read(magic) < 4) return false }
        val isZip = magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
            magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
        if (!isZip) return false
        return runCatching {
            ZipFile(file).use { it.getEntry("META-INF/container.xml") != null }
        }.getOrDefault(false)
    }

    override fun indexBook(file: File, fallbackTitle: String): BookIndex {
        ZipFile(file).use { zip ->
            val opfPath = EpubXmlSupport.findOpfPath(zip)
                ?: throw IOException("无效的 EPUB：缺少 container.xml 或 OPF")
            val opf = EpubXmlSupport.parseOpfPackage(zip, opfPath)
                ?: throw IOException("无效的 EPUB：OPF 解析失败")
            val tocEntries = EpubXmlSupport.parseTocEntries(zip, opf)

            val chapters = buildChapterList(zip, opf, tocEntries)
            if (chapters.isEmpty()) {
                throw IOException("EPUB 中未找到可阅读的章节内容")
            }

            return BookIndex(
                title = opf.title.ifBlank { fallbackTitle },
                author = opf.author,
                language = opf.language,
                description = EpubHtmlParser.decodeEntities(opf.description).replace(Regex("<[^>]+>"), "").trim(),
                chapters = chapters,
                coverResourcePath = opf.coverPath
            )
        }
    }

    override fun loadChapter(file: File, chapter: ChapterRef): ChapterContent {
        ZipFile(file).use { zip ->
            val path = EpubXmlSupport.normalizeZipPath("", chapter.locator)
                ?: throw IOException("非法章节路径: ${chapter.locator}")
            val bytes = EpubXmlSupport.readZipEntryBytes(zip, path, MAX_CHAPTER_BYTES)
                ?: throw IOException("章节不存在或超出大小限制: $path")
            val detected = CharsetDetector.detectMarkup(bytes, isTruncatedSample = false)
            val html = String(bytes, detected.bomLength, bytes.size - detected.bomLength, detected.charset)
            val blocks = EpubHtmlParser.parse(html, EpubXmlSupport.parentDir(path))
            return ChapterContent(chapter.index, chapter.title, blocks)
        }
    }

    override fun readResource(file: File, resourcePath: String): ByteArray? {
        val safePath = EpubXmlSupport.normalizeZipPath("", resourcePath) ?: return null
        return runCatching {
            ZipFile(file).use { zip ->
                EpubXmlSupport.readZipEntryBytes(zip, safePath, MAX_RESOURCE_BYTES)
            }
        }.getOrNull()
    }

    private fun buildChapterList(
        zip: ZipFile,
        opf: OpfPackage,
        tocEntries: Map<String, TocItemEntry>
    ): List<ChapterRef> {
        val list = mutableListOf<ChapterRef>()
        val lowerCaseEntries = buildLowerCaseEntryIndex(zip)
        for (idref in opf.spineIds) {
            val item = opf.manifestById[idref] ?: continue
            if ("nav" in item.properties && item.path == opf.navPath && opf.spineIds.size > 1) {
                continue
            }
            if (!isReadableSpineType(item.mediaType, item.path)) continue
            val tocItem = tocEntries[item.path]
            val resolvedTitle = tocItem?.title
                ?: readFallbackTitle(zip, item.path, lowerCaseEntries)
                ?: "第 ${list.size + 1} 章"
            list.add(
                ChapterRef(
                    index = list.size,
                    title = resolvedTitle,
                    locator = item.path,
                    depth = tocItem?.depth ?: 0,
                    fragment = tocItem?.fragment.orEmpty()
                )
            )
        }
        return list
    }

    private fun buildLowerCaseEntryIndex(zip: ZipFile): Map<String, String> {
        val map = HashMap<String, String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.isDirectory) {
                map.putIfAbsent(entry.name.lowercase(), entry.name)
            }
        }
        return map
    }

    private fun isReadableSpineType(mediaType: String, path: String): Boolean {
        if (mediaType.contains("html", ignoreCase = true) || mediaType.contains("xml", ignoreCase = true)) {
            return true
        }
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in setOf("xhtml", "html", "htm", "xml")
    }

    private fun readFallbackTitle(
        zip: ZipFile,
        path: String,
        lowerCaseEntries: Map<String, String>
    ): String? {
        val actualPath = if (zip.getEntry(path) != null) path else lowerCaseEntries[path.lowercase()] ?: return null
        val headBytes = EpubXmlSupport.readZipEntryBytes(zip, actualPath, 16 * 1024) ?: return null
        val detected = CharsetDetector.detectMarkup(headBytes, isTruncatedSample = headBytes.size >= 16 * 1024)
        val html = String(headBytes, detected.bomLength, headBytes.size - detected.bomLength, detected.charset)
        return EpubHtmlParser.extractTitle(html).takeIf { it.isNotBlank() }
    }

    companion object {
        private const val MAX_CHAPTER_BYTES = 4 * 1024 * 1024
        private const val MAX_RESOURCE_BYTES = 12 * 1024 * 1024
    }
}
