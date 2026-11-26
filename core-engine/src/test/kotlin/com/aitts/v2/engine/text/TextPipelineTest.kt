package com.aitts.v2.engine.text

import com.aitts.v2.model.EmotionType
import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.PresetCatalog
import com.aitts.v2.model.ReplacementRule
import com.aitts.v2.model.SegmentRole
import com.aitts.v2.model.SegmentationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPipelineTest {

    @Test
    fun `chinese number normalizer fixes 100 million and preserves years`() {
        assertEquals("一亿", ChineseNumberNormalizer.intToChinese(100_000_000L))
        assertEquals("一亿零五十", ChineseNumberNormalizer.intToChinese(100_000_050L))
        assertEquals("一万", ChineseNumberNormalizer.intToChinese(10_000L))
        assertEquals("十万", ChineseNumberNormalizer.intToChinese(100_000L))
        assertEquals("十五", ChineseNumberNormalizer.intToChinese(15L))

        val norm = ChineseNumberNormalizer.normalize("2026年10000年修为，第12章，手机13800138000，时间14:30，价格¥99.5，比例50%。")
        assertTrue(norm.contains("二零二六年"))
        assertTrue(norm.contains("一万年"))
        assertTrue(norm.contains("第十二章"))
        assertTrue(norm.contains("幺三八零零幺三八零零零"))
        assertTrue(norm.contains("十四点三十分"))
        assertTrue(norm.contains("九十九点五元"))
        assertTrue(norm.contains("百分之五十"))
    }

    @Test
    fun `user watermark rules execute before url cleanup and dialogue segmentation keeps quotes`() {
        val raw = "少女哽咽道：「你真的要走吗？」（本章完） https://pirate.example.com/123"
        val settings = GlobalSettings(
            isSentenceSplittingEnabled = true,
            textSegmentationMode = SegmentationMode.SMART_HYBRID,
            isAcronymNormalizationEnabled = false
        )
        val segments = TextPipeline.process(raw, PresetCatalog.defaultRules, settings)

        val joined = segments.joinToString("") { it.text }
        assertFalse(joined.contains("pirate"))
        assertFalse(joined.contains("本章完"))
        assertFalse(joined.contains("网址链接"))

        val dlgSeg = segments.firstOrNull { it.role == SegmentRole.FEMALE_DIALOGUE }
        assertEquals(EmotionType.SAD, dlgSeg?.emotion)
        assertTrue(dlgSeg?.text?.contains("你真的要走吗") == true)
    }

    @Test
    fun `acronym normalizer respects disabled flag`() {
        val raw = "AI 与 WiFi 技术"
        val off = TextPipeline.process(
            raw,
            emptyList(),
            GlobalSettings(isSentenceSplittingEnabled = false, isAcronymNormalizationEnabled = false)
        )
        assertEquals("AI 与 WiFi 技术", off.single().text)

        val on = TextPipeline.process(
            raw,
            emptyList(),
            GlobalSettings(isSentenceSplittingEnabled = false, isAcronymNormalizationEnabled = true)
        )
        assertEquals("A-I 与 W-i-F-i 技术", on.single().text)
    }

    @Test
    fun `custom literal and regex rules run in order`() {
        val rules = listOf(
            ReplacementRule("1", "foo", "bar", isRegex = false),
            ReplacementRule("2", "\\d+", "数字", isRegex = true)
        )
        val segs = TextPipeline.process(
            "foo 42",
            rules,
            GlobalSettings(isSentenceSplittingEnabled = false, isNumberNormalizationEnabled = false)
        )
        assertEquals("bar 数字", segs.single().text)
    }
}
