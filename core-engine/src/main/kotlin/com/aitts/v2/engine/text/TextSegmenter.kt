package com.aitts.v2.engine.text

import com.aitts.v2.model.SentenceSegment

/**
 * Smooth paragraph & punctuation-boundary text splitter (no artificial 16-char cutoff or role guessing).
 */
object TextSegmenter {

    private val primaryEndPunct = setOf('。', '！', '？', '；', '\n', '\r', '!', '?', ';')
    private val secondaryPunct = setOf('，', ',', '、', '：', ':', '—', '-')

    fun segment(
        text: String,
        smartSplittingEnabled: Boolean,
        maxLen: Int = 180
    ): List<SentenceSegment> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (!smartSplittingEnabled) return listOf(SentenceSegment(trimmed))

        val paragraphs = trimmed.split(Regex("[\r\n]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return listOf(SentenceSegment(trimmed))

        return paragraphs.flatMap { block ->
            if (block.length > maxLen) {
                splitByPunctuation(block, maxLen)
            } else {
                listOf(block)
            }
        }.map { SentenceSegment(it) }
    }

    private fun splitByPunctuation(text: String, maxLen: Int): List<String> {
        val result = mutableListOf<String>()
        val buf = StringBuilder()
        for (ch in text) {
            buf.append(ch)
            val reachedEnd = ch in primaryEndPunct && buf.length >= 20
            val reachedPause = buf.length >= maxLen - 16 && ch in secondaryPunct
            val hardCap = buf.length >= maxLen
            if (reachedEnd || reachedPause || hardCap) {
                val seg = buf.toString().trim()
                if (seg.isNotEmpty()) result.add(seg)
                buf.clear()
            }
        }
        val tail = buf.toString().trim()
        if (tail.isNotEmpty()) result.add(tail)
        return result
    }
}
