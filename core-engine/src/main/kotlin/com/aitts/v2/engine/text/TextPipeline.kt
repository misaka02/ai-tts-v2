package com.aitts.v2.engine.text

import com.aitts.v2.engine.pipeline.TextProcessor
import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.ReplacementRule
import com.aitts.v2.model.SentenceSegment

/**
 * Strictly ordered 3-stage pluggable text pipeline:
 * 1. Clean invisible controls, HTML, Markdown (preserving quotes & URLs).
 * 2. Apply user replacement rules.
 * 3. Optional Chinese number/time/currency normalization (primarily for lightweight offline models).
 */
object TextPipeline {

    fun process(
        rawText: String,
        rules: List<ReplacementRule>,
        settings: GlobalSettings
    ): List<SentenceSegment> {
        if (rawText.isBlank()) return emptyList()

        val preCleanChain = listOf(
            TextProcessor { TextCleanerAndRules.cleanMarkup(it) },
            TextProcessor { TextCleanerAndRules.applyRules(it, rules) },
            TextProcessor { TextCleanerAndRules.normalizeWhitespace(it) }
        )
        val normalizedSpace = preCleanChain.fold(rawText) { acc, processor -> processor.process(acc) }
        if (normalizedSpace.isBlank()) return emptyList()

        val rawSegments = TextSegmenter.segment(
            text = normalizedSpace,
            smartSplittingEnabled = settings.isSmartSegmentationEnabled
        )

        return rawSegments.mapNotNull { seg ->
            val finalText = if (settings.isNumberNormalizationEnabled) {
                TextCleanerAndRules.normalizeWhitespace(ChineseNumberNormalizer.normalize(seg.text))
            } else {
                seg.text
            }
            if (finalText.isBlank()) null else seg.copy(text = finalText)
        }
    }

    /**
     * Single-string preview preprocessing for the Pronunciation Dictionary workbench.
     */
    fun preprocessFlat(
        rawText: String,
        rules: List<ReplacementRule>,
        enableNumbers: Boolean
    ): String {
        if (rawText.isBlank()) return ""
        var res = TextCleanerAndRules.cleanMarkup(rawText)
        res = TextCleanerAndRules.applyRules(res, rules)
        if (enableNumbers) res = ChineseNumberNormalizer.normalize(res)
        return TextCleanerAndRules.normalizeWhitespace(res)
    }
}
