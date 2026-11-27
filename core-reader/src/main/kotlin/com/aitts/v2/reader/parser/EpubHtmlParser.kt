package com.aitts.v2.reader.parser

import com.aitts.v2.reader.model.ReaderBlock

/**
 * Tolerant pure-Kotlin XHTML/HTML block extractor handling malformed EPUB markup,
 * ruby furigana (<rt>/<rp>), blockquotes, headings, <br>-separated prose, and inline images.
 */
internal object EpubHtmlParser {

    private val SKIP_TAGS = setOf("head", "script", "style", "rt", "rp", "noscript", "title")
    private val HEADING_TAGS = mapOf("h1" to 1, "h2" to 2, "h3" to 3, "h4" to 4)
    private val VOID_TAGS = setOf("br", "hr", "img", "image", "meta", "link", "input")
    private val CONTAINER_TAGS = setOf("p", "div", "li", "blockquote", "pre", "section", "article", "h1", "h2", "h3", "h4")

    private val NAMED_ENTITIES = mapOf(
        "nbsp" to " ",
        "emsp" to " ",
        "ensp" to " ",
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "mdash" to "—",
        "ndash" to "–",
        "hellip" to "…",
        "ldquo" to "“",
        "rdquo" to "”",
        "lsquo" to "‘",
        "rsquo" to "’",
        "middot" to "·",
        "copy" to "©",
        "reg" to "®"
    )

    private val ENTITY_RE = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")
    private val TITLE_TAG_RE = Regex("""<title\b[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun extractTitle(html: String): String {
        val raw = TITLE_TAG_RE.find(html)?.groupValues?.getOrNull(1) ?: return ""
        return decodeEntities(raw).replace(Regex("\\s+"), " ").trim()
    }

    fun parse(html: String, baseDir: String): List<ReaderBlock> {
        val state = ParseState(baseDir)
        var i = 0
        while (i < html.length) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                state.appendText(html.substring(i))
                break
            }
            if (lt > i) state.appendText(html.substring(i, lt))
            if (html.startsWith("<!--", lt)) {
                val endComment = html.indexOf("-->", lt + 4)
                i = if (endComment < 0) html.length else endComment + 3
                continue
            }
            val gt = findTagEnd(html, lt + 1)
            if (gt < 0) break
            state.processTag(html.substring(lt + 1, gt))
            i = gt + 1
        }
        state.flushText()
        return state.blocks
    }

    fun decodeEntities(input: String): String = ENTITY_RE.replace(input) { m ->
        val token = m.groupValues[1]
        when {
            token.startsWith("#x", ignoreCase = true) ->
                token.substring(2).toIntOrNull(16)?.let { codePointToString(it) } ?: m.value
            token.startsWith("#") ->
                token.substring(1).toIntOrNull()?.let { codePointToString(it) } ?: m.value
            else -> NAMED_ENTITIES[token.lowercase()] ?: m.value
        }
    }

    private fun codePointToString(cp: Int): String =
        if (cp in 0x09..0x10FFFF) String(Character.toChars(cp)) else ""

    private fun findTagEnd(html: String, start: Int): Int {
        var quote: Char? = null
        var idx = start
        while (idx < html.length) {
            val ch = html[idx]
            if (quote != null) {
                if (ch == quote) quote = null
            } else if (ch == '"' || ch == '\'') {
                quote = ch
            } else if (ch == '>') {
                return idx
            }
            idx++
        }
        return -1
    }

    private class ParseState(private val baseDir: String) {
        val blocks = mutableListOf<ReaderBlock>()
        private val textBuf = StringBuilder()
        private var skipDepth = 0
        private var headingLevel = 0
        private var quoteDepth = 0

        fun appendText(raw: String) {
            if (skipDepth > 0 || raw.isEmpty()) return
            textBuf.append(decodeEntities(raw))
        }

        fun processTag(rawTag: String) {
            val trimmed = rawTag.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("?")) return
            val isClosing = trimmed.startsWith("/")
            val body = if (isClosing) trimmed.substring(1).trim() else trimmed
            val nameEnd = body.indexOfFirst { it.isWhitespace() || it == '/' }
            val rawName = if (nameEnd < 0) body else body.substring(0, nameEnd)
            val tagName = rawName.substringAfter(':').lowercase()
            val selfClosing = !isClosing && (trimmed.endsWith("/") || tagName in VOID_TAGS)

            if (isClosing) {
                handleCloseTag(tagName)
            } else {
                handleOpenTag(tagName, body, selfClosing)
            }
        }

        private fun handleOpenTag(tagName: String, tagBody: String, selfClosing: Boolean) {
            if (tagName in SKIP_TAGS) {
                if (!selfClosing) skipDepth++
                return
            }
            if (skipDepth > 0) return

            when {
                tagName == "br" -> if (headingLevel > 0) textBuf.append(' ') else flushText()
                tagName == "hr" -> {
                    flushText()
                    blocks.add(ReaderBlock.Divider(blocks.size))
                }
                tagName == "img" || tagName == "image" -> emitImage(tagBody)
                tagName in HEADING_TAGS -> {
                    flushText()
                    headingLevel = HEADING_TAGS[tagName] ?: 1
                }
                tagName == "blockquote" -> {
                    flushText()
                    quoteDepth++
                }
                tagName in CONTAINER_TAGS -> flushText()
            }
        }

        private fun handleCloseTag(tagName: String) {
            if (tagName in SKIP_TAGS) {
                if (skipDepth > 0) skipDepth--
                return
            }
            if (skipDepth > 0) return
            if (tagName in CONTAINER_TAGS) flushText()
            if (tagName in HEADING_TAGS) headingLevel = 0
            if (tagName == "blockquote" && quoteDepth > 0) quoteDepth--
        }

        private fun emitImage(tagBody: String) {
            val src = extractAttr(tagBody, "src")
                ?: extractAttr(tagBody, "href")
                ?: extractAttr(tagBody, "xlink:href")
                ?: extractAttr(tagBody, "data-src")
                ?: return
            val resolved = if (src.startsWith("data:image/", ignoreCase = true)) {
                src
            } else {
                EpubXmlSupport.normalizeZipPath(baseDir, src) ?: return
            }
            flushText()
            val alt = decodeEntities(extractAttr(tagBody, "alt").orEmpty()).trim()
            blocks.add(ReaderBlock.Image(index = blocks.size, resourcePath = resolved, altText = alt))
        }

        fun flushText() {
            if (textBuf.isEmpty()) return
            val clean = textBuf.toString()
                .replace('\u00A0', ' ')
                .replace(Regex("[ \\t\\r\\n]+"), " ")
                .trim(' ', '\u3000')
            textBuf.setLength(0)
            if (clean.isEmpty()) return
            val block = if (headingLevel > 0) {
                ReaderBlock.Heading(index = blocks.size, text = clean, level = headingLevel)
            } else {
                ReaderBlock.Paragraph(index = blocks.size, text = clean, isQuote = quoteDepth > 0)
            }
            blocks.add(block)
        }

        private fun extractAttr(tagBody: String, attrName: String): String? {
            val pattern = Regex("""(?:^|\s)$attrName\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
            val m = pattern.find(tagBody) ?: return null
            return (m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }).takeIf { it.isNotBlank() }
        }
    }
}
