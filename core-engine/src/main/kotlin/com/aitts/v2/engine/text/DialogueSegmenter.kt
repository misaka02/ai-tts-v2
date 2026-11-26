package com.aitts.v2.engine.text

import com.aitts.v2.model.EmotionType
import com.aitts.v2.model.SegmentRole
import com.aitts.v2.model.SegmentationMode
import com.aitts.v2.model.SentenceSegment
import java.util.ArrayDeque

/**
 * Segments novel text into narrator/dialogue chunks with balanced quote stack,
 * short-paragraph merging, punctuation-aware splitting, and ultra-low-latency first chunking.
 */
object DialogueSegmenter {

    private val primaryEndPunct = setOf('。', '！', '？', '；', '\n', '\r', '!', '?', ';')
    private val secondaryPunct = setOf('，', ',', '、', '：', ':', '—', '-')

    fun segment(
        text: String,
        mode: SegmentationMode,
        mergeShort: Boolean,
        minMergeLen: Int,
        splitLong: Boolean,
        maxLen: Int,
        ultraLowLatency: Boolean
    ): List<SentenceSegment> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        return when (mode) {
            SegmentationMode.SMART_HYBRID -> {
                val raw = splitWithRoles(trimmed, if (splitLong) maxLen else 400, ultraLowLatency)
                if (!mergeShort || raw.size <= 1) raw else mergeAdjacentSameRole(raw, minMergeLen)
            }
            SegmentationMode.PUNCTUATION -> {
                val sentences = splitIntoSentences(trimmed, if (splitLong) maxLen else 300, preferPrimaryEnd = false)
                val merged = if (mergeShort && sentences.size > 1) mergeStrings(sentences, minMergeLen, "\n") else sentences
                merged.map { SentenceSegment(it, SegmentRole.NARRATOR) }
            }
            SegmentationMode.PARAGRAPH -> segmentParagraphs(trimmed, mergeShort, minMergeLen, splitLong, maxLen)
        }
    }

    private fun segmentParagraphs(
        trimmed: String,
        mergeShort: Boolean,
        minMergeLen: Int,
        splitLong: Boolean,
        maxLen: Int
    ): List<SentenceSegment> {
        val paragraphs = trimmed.split(Regex("[\r\n]+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return listOf(SentenceSegment(trimmed, SegmentRole.NARRATOR))
        val merged = if (mergeShort && paragraphs.size > 1) mergeParagraphs(paragraphs, minMergeLen) else paragraphs
        val finalBlocks = if (splitLong) {
            merged.flatMap { block ->
                if (block.length > maxLen) splitIntoSentences(block, maxLen, preferPrimaryEnd = true) else listOf(block)
            }
        } else {
            merged
        }
        return finalBlocks.map { SentenceSegment(it, SegmentRole.NARRATOR) }
    }

    fun splitWithRoles(
        text: String,
        maxLength: Int = 80,
        ultraLowLatency: Boolean = true
    ): List<SentenceSegment> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        val rawBlocks = extractQuoteBlocks(trimmed)
        val result = mutableListOf<SentenceSegment>()
        for ((idx, block) in rawBlocks.withIndex()) {
            if (block.text.isBlank()) continue
            val effMax = if (ultraLowLatency && idx == 0 && block.text.length > 18) 16 else maxLength
            for (s in splitIntoSentences(block.text, effMax, preferPrimaryEnd = false)) {
                if (s.isNotBlank()) result.add(SentenceSegment(s, block.role, block.emotion))
            }
        }
        return result
    }

    private fun extractQuoteBlocks(trimmed: String): List<SentenceSegment> {
        val rawBlocks = mutableListOf<SentenceSegment>()
        val quoteStack = ArrayDeque<Char>()
        var dialogueRole = SegmentRole.DIALOGUE
        var lastNarrator = ""
        val buf = StringBuilder()

        for (c in trimmed) {
            when {
                isOpeningQuote(c) -> {
                    if (quoteStack.isEmpty()) {
                        dialogueRole = flushNarratorBuffer(buf, rawBlocks) { lastNarrator = it }
                    }
                    quoteStack.push(c)
                    buf.append(c)
                }
                quoteStack.isNotEmpty() && isMatchingClose(quoteStack.peek(), c) -> {
                    quoteStack.pop()
                    buf.append(c)
                    if (quoteStack.isEmpty()) {
                        flushDialogueBuffer(buf, dialogueRole, lastNarrator, rawBlocks)
                    }
                }
                shouldForceCloseQuote(quoteStack, buf.length, c) -> {
                    buf.append(c)
                    flushDialogueBuffer(buf, dialogueRole, lastNarrator, rawBlocks)
                    quoteStack.clear()
                    dialogueRole = SegmentRole.DIALOGUE
                }
                else -> buf.append(c)
            }
        }

        if (buf.isNotEmpty()) {
            val rem = buf.toString().trim()
            val role = if (quoteStack.isNotEmpty()) dialogueRole else SegmentRole.NARRATOR
            val emotion = if (quoteStack.isNotEmpty()) DialogueAnalyzer.detectEmotion(lastNarrator, rem) else EmotionType.NEUTRAL
            rawBlocks.add(SentenceSegment(rem, role, emotion))
        }
        return rawBlocks
    }

    private fun flushNarratorBuffer(
        buf: StringBuilder,
        out: MutableList<SentenceSegment>,
        onNarrator: (String) -> Unit
    ): SegmentRole {
        if (buf.isEmpty()) return SegmentRole.DIALOGUE
        val narrator = buf.toString().trim()
        onNarrator(narrator)
        out.add(SentenceSegment(narrator, SegmentRole.NARRATOR))
        buf.clear()
        return DialogueAnalyzer.detectSpeakerRole(narrator)
    }

    private fun flushDialogueBuffer(
        buf: StringBuilder,
        role: SegmentRole,
        lastNarrator: String,
        out: MutableList<SentenceSegment>
    ) {
        val dlg = buf.toString().trim()
        out.add(SentenceSegment(dlg, role, DialogueAnalyzer.detectEmotion(lastNarrator, dlg)))
        buf.clear()
    }

    private fun shouldForceCloseQuote(stack: ArrayDeque<Char>, len: Int, c: Char): Boolean {
        if (stack.isEmpty()) return false
        if (c == '\n' || c == '\r') return true
        return len > 180 && (c == '。' || c == '！' || c == '？')
    }

    private fun splitIntoSentences(text: String, maxLen: Int, preferPrimaryEnd: Boolean): List<String> {
        val result = mutableListOf<String>()
        val chunk = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            chunk.append(c)
            if (isSplitPoint(c, chunk.length, maxLen, preferPrimaryEnd)) {
                while (i + 1 < text.length && isClosingQuote(text[i + 1])) {
                    i++
                    chunk.append(text[i])
                }
                chunk.toString().trim().takeIf { it.isNotEmpty() }?.let { result.add(it) }
                chunk.clear()
            }
            i++
        }
        chunk.toString().trim().takeIf { it.isNotEmpty() }?.let { result.add(it) }
        return if (result.isEmpty()) listOf(text) else result
    }

    private fun isSplitPoint(c: Char, len: Int, maxLen: Int, preferPrimaryEnd: Boolean): Boolean {
        val isPrimary = c in primaryEndPunct && (!preferPrimaryEnd || len >= maxLen / 2)
        val isSecondary = c in secondaryPunct && len >= (if (preferPrimaryEnd) maxLen else maxLen / 2)
        val isOver = if (preferPrimaryEnd) {
            len >= (maxLen * 1.5).toInt() && (c in secondaryPunct || c == ' ')
        } else {
            len >= maxLen
        }
        return isPrimary || isSecondary || isOver
    }

    private fun mergeAdjacentSameRole(segments: List<SentenceSegment>, minLen: Int): List<SentenceSegment> {
        val merged = mutableListOf<SentenceSegment>()
        var role = segments.first().role
        var emotion = segments.first().emotion
        val buf = StringBuilder(segments.first().text)
        for (i in 1 until segments.size) {
            val seg = segments[i]
            if (seg.role == role && buf.length < minLen) {
                appendSmart(buf, seg.text)
            } else {
                merged.add(SentenceSegment(buf.toString().trim(), role, emotion))
                buf.clear().append(seg.text)
                role = seg.role
                emotion = seg.emotion
            }
        }
        if (buf.isNotEmpty()) merged.add(SentenceSegment(buf.toString().trim(), role, emotion))
        return merged
    }

    private fun mergeStrings(items: List<String>, minLen: Int, sep: String): List<String> {
        val merged = mutableListOf<String>()
        val buf = StringBuilder()
        for (item in items) {
            if (buf.isEmpty()) buf.append(item)
            else if (buf.length < minLen) {
                if (sep == "\n") appendSmart(buf, item) else buf.append(sep).append(item)
            } else {
                merged.add(buf.toString().trim())
                buf.clear().append(item)
            }
        }
        if (buf.isNotEmpty()) merged.add(buf.toString().trim())
        return merged
    }

    private fun mergeParagraphs(paragraphs: List<String>, minLen: Int): List<String> {
        val merged = mutableListOf<String>()
        val buf = StringBuilder()
        for (p in paragraphs) {
            val shouldMergeShort = p.length < minLen && buf.length + p.length < minLen * 2
            val shouldMergePair = buf.length < minLen && p.length < minLen
            if (buf.isEmpty()) {
                buf.append(p)
            } else if (shouldMergeShort || shouldMergePair) {
                buf.append("\n").append(p)
            } else {
                merged.add(buf.toString().trim())
                buf.clear().append(p)
            }
        }
        if (buf.isNotEmpty()) merged.add(buf.toString().trim())
        return merged
    }

    private fun appendSmart(buf: StringBuilder, next: String) {
        val t = next.trim()
        if (t.isEmpty()) return
        if (buf.isNotEmpty() && buf.last().isAsciiAlnum() && t.first().isAsciiAlnum()) buf.append(' ')
        buf.append(t)
    }

    private fun Char.isAsciiAlnum() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
    private fun isOpeningQuote(c: Char) = c == '“' || c == '‘' || c == '「' || c == '『'
    private fun isMatchingClose(open: Char, close: Char) = when (open) {
        '“' -> close == '”'
        '‘' -> close == '’'
        '「' -> close == '」'
        '『' -> close == '』'
        else -> false
    }
    private fun isClosingQuote(c: Char) = c == '”' || c == '’' || c == '」' || c == '』' || c == '）' || c == ')'
}
