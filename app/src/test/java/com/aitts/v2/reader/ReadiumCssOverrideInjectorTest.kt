package com.aitts.v2.reader

import com.aitts.v2.reader.model.ReaderFontFamily
import com.aitts.v2.reader.model.ReaderImageFilter
import com.aitts.v2.reader.model.ReaderSettings
import com.aitts.v2.reader.model.ReaderTextAlign
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadiumCssOverrideInjectorTest {

    @Test
    fun `override css enforces non-default weight, spacing, surgical indent, align and custom font`() {
        val settings = ReaderSettings(
            fontFamily = ReaderFontFamily.SERIF,
            fontWeight = 600,
            letterSpacingEm = 0.12f,
            wordSpacingEm = 0.25f,
            paragraphIndentEm = 2f,
            paragraphSpacingDp = 16f,
            textAlign = ReaderTextAlign.JUSTIFY,
            hyphens = true,
            verticalText = true,
            nightImageFilter = ReaderImageFilter.DARKEN,
            publisherStyles = false
        )
        val css = ReadiumCssOverrideInjector.buildOverrideCss(
            settings = settings,
            resolvedTextHex = "#E6DFD3",
            resolvedBgHex = "#181614",
            hasCustomFont = true
        )
        assertTrue(css.contains("font-weight: 600 !important"))
        assertFalse(css.contains("font-weight: 400"))
        assertTrue(css.contains("AiTtsCustomFont"))
        assertTrue(css.contains("letter-spacing: 0.12em !important"))
        assertTrue(css.contains("word-spacing: 0.25em !important"))
        assertTrue(css.contains("p:not(.hanging-punc):not(.img):not(.align-right):not(.align-end)"))
        assertTrue(css.contains("text-indent: 2.0em !important"))
        assertTrue(css.contains("text-align: justify !important"))
        assertTrue(css.contains("writing-mode: vertical-rl !important"))
        assertTrue(css.contains("brightness(0.76) !important"))
        assertTrue(css.contains("h1, h2, h3, h4, h5, h6 { color: #E6DFD3; }"))
    }

    @Test
    fun `publisherStyles preserves embedded fonts, emphasis dots, subtitle colors, and normal weight`() {
        val settings = ReaderSettings(
            fontWeight = 400,
            letterSpacingEm = 0.20f,
            publisherStyles = true,
            nightImageFilter = ReaderImageFilter.INVERT
        )
        val css = ReadiumCssOverrideInjector.buildOverrideCss(
            settings = settings,
            resolvedTextHex = "#FAF8F4",
            resolvedBgHex = "#111111",
            hasCustomFont = false
        )
        assertFalse(css.contains("font-weight:"))
        assertFalse(css.contains("font-family:"))
        assertFalse(css.contains("letter-spacing:"))
        assertFalse(css.contains("text-indent:"))
        assertTrue(css.contains("invert(100%) !important"))
        assertTrue(css.contains("h1, h2, h3, h4, h5, h6 { color: #FAF8F4; }"))
    }
}
