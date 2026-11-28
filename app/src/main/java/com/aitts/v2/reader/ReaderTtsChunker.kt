package com.aitts.v2.reader

import com.aitts.v2.engine.text.TextPipeline
import com.aitts.v2.engine.text.TextSegmenter
import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ReplacementRule
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.reader.model.BookIndex
import com.aitts.v2.reader.model.ReaderBlock
import com.aitts.v2.reader.model.ReaderSettings
import com.aitts.v2.reader.model.ReaderTtsChunkMode
import com.aitts.v2.reader.parser.BookFormatParser
import com.aitts.v2.reader.parser.EpubBookParser
import java.io.File

data class ReaderTtsState(
    val bookId: String? = null,
    val bookTitle: String = "",
    val chapterIndex: Int = 0,
    val chapterHref: String = "",
    val chapterTitle: String = "",
    val activeBlockIndex: Int = -1,
    val activeSentenceText: String = "",
    val highlightAnchor: String = "",
    val isSynthesizing: Boolean = false,
    val isPlaying: Boolean = false,
    val errorMessage: String? = null
)

data class BookTtsChunk(
    val chapterIndex: Int,
    val chapterTitle: String,
    val chapterHref: String,
    val blockIndex: Int,
    val text: String,
    val highlightAnchor: String,
    val endBlockIndex: Int = blockIndex
)

/**
 * Prepares normalized TTS synthesis chunks from reader chapter blocks
 * according to the user's chunking strategy and target character budget.
 */
object ReaderTtsChunker {

    private val ANCHOR_SPLIT_RE = Regex("[。！？；\\n.!?;「」『』“”\"…—]+")
    private val STRIP_QUOTE_RE = Regex("[\\s「」『』“”\"…—]+")

    fun buildChunks(
        chapterIndex: Int,
        chapterTitle: String,
        chapterHref: String,
        blocks: List<ReaderBlock>,
        startBlockIndex: Int,
        mode: ReaderTtsChunkMode,
        targetChars: Int,
        rules: List<ReplacementRule>,
        globalSettings: GlobalSettings
    ): List<BookTtsChunk> {
        val maxChars = targetChars.coerceIn(80, 600)
        val speakable = blocks.mapNotNull { blk ->
            if (blk.index < startBlockIndex) return@mapNotNull null
            val raw = blk.speakableText?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val clean = TextPipeline.preprocessFlat(raw, rules, globalSettings.isNumberNormalizationEnabled)
            if (clean.isBlank()) null else blk.index to clean
        }
        if (speakable.isEmpty()) return emptyList()

        return when (mode) {
            ReaderTtsChunkMode.BY_SENTENCE -> splitAllBlocks(chapterIndex, chapterTitle, chapterHref, speakable, maxChars)
            ReaderTtsChunkMode.BY_PARAGRAPH -> splitOversizedBlocks(chapterIndex, chapterTitle, chapterHref, speakable, maxChars)
            ReaderTtsChunkMode.SMART_MERGE -> mergeShortBlocks(chapterIndex, chapterTitle, chapterHref, speakable, maxChars)
        }
    }

    private fun splitAllBlocks(
        chapterIndex: Int,
        chapterTitle: String,
        chapterHref: String,
        speakable: List<Pair<Int, String>>,
        maxChars: Int
    ): List<BookTtsChunk> {
        val out = mutableListOf<BookTtsChunk>()
        for ((blkIdx, text) in speakable) {
            val sentences = splitIntoSentences(text)
            for (sent in sentences) {
                val pieces = if (sent.length > maxChars) {
                    TextSegmenter.segment(sent, smartSplittingEnabled = true, maxLen = maxChars).map { it.text }
                } else listOf(sent)
                for (rawPiece in pieces) {
                    val t = rawPiece.trim()
                    if (t.isNotEmpty()) out.add(BookTtsChunk(chapterIndex, chapterTitle, chapterHref, blkIdx, t, pickHighlightAnchor(t)))
                }
            }
        }
        return out
    }

    private fun splitIntoSentences(text: String): List<String> {
        val endPunct = setOf('。', '！', '？', '；', '!', '?', ';', '\n')
        val res = mutableListOf<String>()
        val buf = StringBuilder()
        for (ch in text) {
            buf.append(ch)
            if (ch in endPunct && buf.any { it !in endPunct && !it.isWhitespace() }) {
                val s = buf.toString().trim()
                if (s.isNotEmpty()) res.add(s)
                buf.clear()
            }
        }
        val tail = buf.toString().trim()
        if (tail.isNotEmpty()) res.add(tail)
        return res
    }

    private fun splitOversizedBlocks(
        chapterIndex: Int,
        chapterTitle: String,
        chapterHref: String,
        speakable: List<Pair<Int, String>>,
        maxChars: Int
    ): List<BookTtsChunk> {
        val out = mutableListOf<BookTtsChunk>()
        for ((blkIdx, text) in speakable) {
            if (text.length <= maxChars) {
                out.add(BookTtsChunk(chapterIndex, chapterTitle, chapterHref, blkIdx, text, pickHighlightAnchor(text)))
            } else {
                val pieces = TextSegmenter.segment(text, smartSplittingEnabled = true, maxLen = maxChars)
                for (p in pieces) {
                    val t = p.text.trim()
                    if (t.isNotEmpty()) out.add(BookTtsChunk(chapterIndex, chapterTitle, chapterHref, blkIdx, t, pickHighlightAnchor(t)))
                }
            }
        }
        return out
    }

    private fun mergeShortBlocks(
        chapterIndex: Int,
        chapterTitle: String,
        chapterHref: String,
        speakable: List<Pair<Int, String>>,
        maxChars: Int
    ): List<BookTtsChunk> {
        val out = mutableListOf<BookTtsChunk>()
        val buf = StringBuilder()
        var firstBlk = -1
        var lastBlk = -1
        var firstAnchor = ""

        fun flushBuf() {
            val txt = buf.toString().trim()
            if (txt.isNotEmpty()) {
                val startB = firstBlk.coerceAtLeast(0)
                val endB = lastBlk.coerceAtLeast(startB)
                out.add(BookTtsChunk(chapterIndex, chapterTitle, chapterHref, startB, txt, firstAnchor, endB))
            }
            buf.setLength(0)
            firstBlk = -1
            lastBlk = -1
            firstAnchor = ""
        }

        for ((blkIdx, text) in speakable) {
            if (text.length > maxChars) {
                flushBuf()
                val pieces = TextSegmenter.segment(text, smartSplittingEnabled = true, maxLen = maxChars)
                for (p in pieces) {
                    val t = p.text.trim()
                    if (t.isNotEmpty()) out.add(BookTtsChunk(chapterIndex, chapterTitle, chapterHref, blkIdx, t, pickHighlightAnchor(t)))
                }
                continue
            }
            if (buf.isNotEmpty() && buf.length + 1 + text.length > maxChars) flushBuf()
            if (buf.isEmpty()) {
                firstBlk = blkIdx
                lastBlk = blkIdx
                firstAnchor = pickHighlightAnchor(text)
                buf.append(text)
            } else {
                lastBlk = blkIdx
                buf.append('\n').append(text)
            }
        }
        flushBuf()
        return out
    }

    internal fun pickHighlightAnchor(text: String): String {
        val best = text.split(ANCHOR_SPLIT_RE)
            .map { it.trim() }
            .filter { it.length >= 6 }
            .maxByOrNull { it.length }
        return (best ?: STRIP_QUOTE_RE.replace(text, " ").trim()).take(36)
    }

    internal fun rotateCredentials(creds: ProviderCredentials, index: Int): ProviderCredentials =
        if (creds.secondaryApiKey.isBlank() || index % 2 == 0) creds else creds.copy(apiKey = creds.secondaryApiKey)

    internal fun configFingerprint(cfg: TtsProviderConfig, g: GlobalSettings): String =
        "${cfg.hashCode()}|${g.speedPolicy}|${g.globalSpeed}|${g.globalPitch}|" +
            "${g.trimDeadSilence}|${g.isNumberNormalizationEnabled}"

    internal fun chunkFingerprint(r: ReaderSettings): String =
        "${r.ttsChunkMode}|${r.ttsTargetChunkChars}|${r.ttsSmartRubyReading}"

    internal fun rebuildRemainingChapterQueue(
        queue: MutableList<BookTtsChunk>,
        cursor: Int,
        parser: BookFormatParser,
        bookFile: File,
        index: BookIndex,
        readerSettings: ReaderSettings,
        rules: List<ReplacementRule>,
        globalSettings: GlobalSettings
    ): Int {
        val cur = queue.getOrNull(cursor) ?: return cursor + 1
        val curChap = cur.chapterIndex
        val keepSameBlock = queue.subList(0, cursor + 1).toMutableList()
        for (i in (cursor + 1) until queue.size) {
            if (queue[i].chapterIndex == curChap && queue[i].blockIndex == cur.blockIndex) {
                keepSameBlock.add(queue[i])
            } else break
        }
        val nextStartBlock = keepSameBlock.last().endBlockIndex + 1
        val curRef = index.chapters.getOrNull(curChap)
        if (curRef != null) {
            val content = if (parser is EpubBookParser) {
                parser.loadChapter(bookFile, curRef, readerSettings.ttsSmartRubyReading)
            } else parser.loadChapter(bookFile, curRef)
            keepSameBlock.addAll(
                buildChunks(
                    curChap, content.title, curRef.locator, content.blocks, nextStartBlock,
                    readerSettings.ttsChunkMode, readerSettings.ttsTargetChunkChars, rules, globalSettings
                )
            )
        }
        queue.clear()
        queue.addAll(keepSameBlock)
        return curChap + 1
    }

    internal fun loadAndChunkChapter(
        parser: BookFormatParser,
        bookFile: File,
        index: BookIndex,
        chapIdx: Int,
        readerSettings: ReaderSettings,
        rules: List<ReplacementRule>,
        globalSettings: GlobalSettings
    ): List<BookTtsChunk> {
        val ref = index.chapters.getOrNull(chapIdx) ?: return emptyList()
        val content = if (parser is EpubBookParser) {
            parser.loadChapter(bookFile, ref, readerSettings.ttsSmartRubyReading)
        } else {
            parser.loadChapter(bookFile, ref)
        }
        return buildChunks(
            chapterIndex = content.chapterIndex, chapterTitle = content.title, chapterHref = ref.locator,
            blocks = content.blocks, startBlockIndex = 0, mode = readerSettings.ttsChunkMode,
            targetChars = readerSettings.ttsTargetChunkChars, rules = rules, globalSettings = globalSettings
        )
    }
}
