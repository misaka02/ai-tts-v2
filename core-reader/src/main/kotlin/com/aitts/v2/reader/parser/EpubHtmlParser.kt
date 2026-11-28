package com.aitts.v2.reader.parser

import com.aitts.v2.reader.model.ReaderBlock
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Industrial DOM-based XHTML/HTML extractor powered by Jsoup.
 * Strips furigana <rt>/<rp> and non-content noise while faithfully preserving headings,
 * paragraphs, quotes, lists, tables, preformatted code, <br>-split prose, and inline images.
 */
internal object EpubHtmlParser {

    private val HEADING_LEVELS = mapOf("h1" to 1, "h2" to 2, "h3" to 3, "h4" to 4, "h5" to 4, "h6" to 4)
    private val SKIP_TAGS = setOf("head", "script", "style", "rt", "rp", "noscript", "title", "svg")
    private val INLINE_TAGS = setOf("span", "a", "em", "strong", "b", "i", "u", "ruby", "rb", "small", "sub", "sup", "code")
    private val HAS_READABLE_GLYPH_RE = Regex("[\\u4E00-\\u9FFF\\u3400-\\u4DBFA-Za-z0-9\\u3040-\\u30FF]")
    private val NOTEREF_MARKER_RE = Regex("^[*†‡※㊟\\d\\[\\]()【】]+$")

    fun extractTitle(html: String): String = runCatching {
        Jsoup.parse(html).title().replace(Regex("\\s+"), " ").trim()
    }.getOrDefault("")

    fun decodeEntities(input: String): String =
        org.jsoup.parser.Parser.unescapeEntities(input, false)

    fun parse(
        html: String,
        baseDir: String,
        smartRubyReading: Boolean = true,
        stripNoterefMarkers: Boolean = false
    ): List<ReaderBlock> {
        val doc = Jsoup.parse(html)
        if (smartRubyReading) resolveMaskedRubyNodes(doc)
        if (stripNoterefMarkers) stripNoterefMarkers(doc)
        doc.select("head, script, style, rt, rp, noscript").remove()
        val body = doc.body()
        val state = ExtractorState(baseDir)
        for (child in body.childNodes()) {
            when (child) {
                is org.jsoup.nodes.TextNode -> state.appendText(child.wholeText)
                is Element -> state.visit(child, inQuote = false)
            }
        }
        state.flushText(isQuote = false, headingLevel = 0)
        return state.blocks
    }

    private fun resolveMaskedRubyNodes(doc: org.jsoup.nodes.Document) {
        for (ruby in doc.select("ruby")) {
            val rtText = ruby.select("rt").joinToString("") { it.text().trim() }.trim()
            if (rtText.isEmpty()) continue
            val clone = ruby.clone()
            clone.select("rt, rp").remove()
            val baseText = clone.text().trim()
            if (baseText.isNotEmpty() && !HAS_READABLE_GLYPH_RE.containsMatchIn(baseText)) {
                ruby.replaceWith(org.jsoup.nodes.TextNode(rtText))
            }
        }
    }

    private fun stripNoterefMarkers(doc: org.jsoup.nodes.Document) {
        for (a in doc.select("a")) {
            val isNoteRef = a.attr("epub:type").contains("noteref", ignoreCase = true) || a.hasClass("noteref")
            if (isNoteRef && NOTEREF_MARKER_RE.matches(a.text().trim())) {
                a.remove()
            }
        }
    }

    private class ExtractorState(private val baseDir: String) {
        val blocks = mutableListOf<ReaderBlock>()
        private val buf = StringBuilder()

        fun appendText(text: String) {
            if (text.isNotEmpty()) buf.append(text)
        }

        fun visit(el: Element, inQuote: Boolean, headingLevel: Int = 0) {
            val tag = el.tagName().substringAfter(':').lowercase()
            if (tag in SKIP_TAGS && tag != "svg") return
            when {
                tag == "svg" -> handleSvg(el)
                tag == "br" -> if (headingLevel > 0) buf.append(' ') else flushText(inQuote, 0)
                tag == "hr" -> {
                    flushText(inQuote, headingLevel)
                    blocks.add(ReaderBlock.Divider(blocks.size))
                }
                tag == "img" || tag == "image" -> emitImage(el, inQuote, headingLevel)
                tag in HEADING_LEVELS -> handleHeading(el, inQuote, HEADING_LEVELS.getValue(tag))
                tag == "blockquote" -> handleBlockContainer(el, isQuote = true)
                tag == "table" -> handleTable(el, inQuote)
                tag == "li" -> handleListItem(el, inQuote)
                tag in setOf("p", "pre", "figcaption", "dt", "dd") -> handleBlockContainer(el, inQuote)
                tag in INLINE_TAGS -> visitChildren(el, inQuote, headingLevel)
                else -> handleContainer(el, inQuote, headingLevel)
            }
        }

        private fun handleHeading(el: Element, inQuote: Boolean, level: Int) {
            flushText(inQuote, 0)
            visitChildren(el, inQuote, level)
            flushText(isQuote = false, headingLevel = level)
        }

        private fun handleBlockContainer(el: Element, isQuote: Boolean) {
            flushText(isQuote, 0)
            visitChildren(el, isQuote, 0)
            flushText(isQuote, 0)
        }

        private fun handleContainer(el: Element, inQuote: Boolean, headingLevel: Int) {
            val hasBlockChildren = el.children().any { child ->
                child.tagName().substringAfter(':').lowercase() !in INLINE_TAGS
            }
            if (hasBlockChildren) {
                flushText(inQuote, headingLevel)
                visitChildren(el, inQuote, headingLevel)
                flushText(inQuote, headingLevel)
            } else {
                visitChildren(el, inQuote, headingLevel)
                if (el.tagName().lowercase() in setOf("div", "section", "article", "header", "footer")) {
                    flushText(inQuote, headingLevel)
                }
            }
        }

        private fun handleListItem(el: Element, inQuote: Boolean) {
            flushText(inQuote, 0)
            buf.append("• ")
            visitChildren(el, inQuote, 0)
            flushText(inQuote, 0)
        }

        private fun handleTable(table: Element, inQuote: Boolean) {
            flushText(inQuote, 0)
            for (row in table.select("tr")) {
                val cells = row.select("th, td").map { it.text().trim() }.filter { it.isNotEmpty() }
                if (cells.isNotEmpty()) {
                    blocks.add(ReaderBlock.Paragraph(index = blocks.size, text = cells.joinToString(" | "), isQuote = inQuote))
                }
            }
        }

        private fun handleSvg(svg: Element) {
            for (img in svg.select("image, img")) {
                emitImage(img, inQuote = false, headingLevel = 0)
            }
        }

        private fun visitChildren(el: Element, inQuote: Boolean, headingLevel: Int) {
            for (node in el.childNodes()) {
                when (node) {
                    is org.jsoup.nodes.TextNode -> appendText(node.wholeText)
                    is Element -> visit(node, inQuote, headingLevel)
                }
            }
        }

        private fun emitImage(el: Element, inQuote: Boolean, headingLevel: Int) {
            val src = listOf("src", "href", "xlink:href", "data-src")
                .firstNotNullOfOrNull { attr -> el.attr(attr).trim().takeIf { it.isNotEmpty() } }
                ?: return
            val resolved = if (src.startsWith("data:image/", ignoreCase = true)) {
                src
            } else {
                EpubXmlSupport.normalizeZipPath(baseDir, src) ?: return
            }
            flushText(inQuote, headingLevel)
            val alt = el.attr("alt").trim()
            blocks.add(ReaderBlock.Image(index = blocks.size, resourcePath = resolved, altText = alt))
        }

        fun flushText(isQuote: Boolean, headingLevel: Int) {
            if (buf.isEmpty()) return
            val clean = buf.toString()
                .replace('\u00A0', ' ')
                .replace(Regex("[ \\t\\r\\n]+"), " ")
                .trim(' ', '\u3000')
            buf.setLength(0)
            if (clean.isEmpty()) return
            val block = if (headingLevel > 0) {
                ReaderBlock.Heading(index = blocks.size, text = clean, level = headingLevel)
            } else {
                ReaderBlock.Paragraph(index = blocks.size, text = clean, isQuote = isQuote)
            }
            blocks.add(block)
        }
    }
}

/**
 * Extracts a single footnote/endnote element by fragment ID for in-place reader popups.
 */
object EpubFootnoteExtractor {
    fun extractById(html: String, fragmentId: String): String {
        val cleanId = fragmentId.trim()
        if (html.isBlank() || cleanId.isEmpty()) return ""
        return runCatching {
            val doc = Jsoup.parse(html)
            val el = doc.getElementById(cleanId) ?: return ""
            val direct = cleanNoteText(el)
            if (direct.isNotBlank()) return direct
            val parentText = el.parent()
                ?.takeIf { it.tagName().lowercase() in setOf("li", "p", "div", "aside", "dd") }
                ?.let { cleanNoteText(it) }
                .orEmpty()
            if (parentText.isNotBlank()) return parentText
            el.nextElementSibling()?.let { cleanNoteText(it) }.orEmpty()
        }.getOrDefault("")
    }

    private fun cleanNoteText(el: Element): String {
        val clone = el.clone()
        val backSymbols = setOf("⤶", "↩", "↑", "←")
        if (clone.tagName().equals("a", ignoreCase = true)) {
            val selfTxt = clone.text().trim()
            if (selfTxt.isEmpty() || selfTxt in backSymbols) return ""
        }
        clone.select("a").forEach { a ->
            if (a !== clone) {
                val txt = a.text().trim()
                if (txt.isEmpty() || txt in backSymbols) a.remove()
            }
        }
        return clone.text().replace(Regex("\\s+"), " ").trim()
    }
}
