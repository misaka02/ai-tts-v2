package com.aitts.v2.engine.text

import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.ReplacementRule
import com.aitts.v2.model.SegmentRole
import com.aitts.v2.model.SentenceSegment

/**
 * Strictly ordered 4-stage text preprocessing and segmentation pipeline:
 * 1. Clean invisible controls, HTML, Markdown (preserving quotes & URLs).
 * 2. Apply user replacement rules (watermark/URL removal & custom polyphones).
 * 3. Segment text into narrator/dialogue/emotion chunks while quotes are still intact.
 * 4. Apply optional Chinese number & acronym pronunciation normalization per segment.
 */
object TextPipeline {

    fun process(
        rawText: String,
        rules: List<ReplacementRule>,
        settings: GlobalSettings
    ): List<SentenceSegment> {
        if (rawText.isBlank()) return emptyList()

        val cleaned = TextCleanerAndRules.cleanMarkup(rawText)
        val afterRules = TextCleanerAndRules.applyRules(cleaned, rules)
        val normalizedSpace = TextCleanerAndRules.normalizeWhitespace(afterRules)
        if (normalizedSpace.isBlank()) return emptyList()

        val rawSegments = if (!settings.isSentenceSplittingEnabled) {
            listOf(SentenceSegment(normalizedSpace, SegmentRole.NARRATOR))
        } else {
            DialogueSegmenter.segment(
                text = normalizedSpace,
                mode = settings.textSegmentationMode,
                mergeShort = settings.mergeShortParagraphs,
                minMergeLen = settings.minMergeParagraphLength,
                splitLong = settings.splitLongParagraphs,
                maxLen = settings.maxSegmentLength,
                ultraLowLatency = settings.ultraLowLatencyMode
            )
        }

        return rawSegments.mapNotNull { seg ->
            var text = seg.text
            if (settings.isNumberNormalizationEnabled) {
                text = ChineseNumberNormalizer.normalize(text)
            }
            if (settings.isAcronymNormalizationEnabled) {
                text = AcronymNormalizer.normalize(text)
            }
            text = TextCleanerAndRules.normalizeWhitespace(text)
            if (text.isBlank()) null else seg.copy(text = text)
        }
    }

    /**
     * Single-string preview preprocessing (used for trial workbench before synthesis).
     */
    fun preprocessFlat(
        rawText: String,
        rules: List<ReplacementRule>,
        enableNumbers: Boolean,
        enableAcronyms: Boolean
    ): String {
        if (rawText.isBlank()) return ""
        var res = TextCleanerAndRules.cleanMarkup(rawText)
        res = TextCleanerAndRules.applyRules(res, rules)
        if (enableNumbers) res = ChineseNumberNormalizer.normalize(res)
        if (enableAcronyms) res = AcronymNormalizer.normalize(res)
        return TextCleanerAndRules.normalizeWhitespace(res)
    }
}
