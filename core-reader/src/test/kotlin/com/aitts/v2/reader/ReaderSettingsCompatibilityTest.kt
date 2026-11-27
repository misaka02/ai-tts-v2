package com.aitts.v2.reader

import com.aitts.v2.reader.model.ReaderColumnCount
import com.aitts.v2.reader.model.ReaderDirection
import com.aitts.v2.reader.model.ReaderFontFamily
import com.aitts.v2.reader.model.ReaderImageFilter
import com.aitts.v2.reader.model.ReaderPaperTheme
import com.aitts.v2.reader.model.ReaderSettings
import com.aitts.v2.reader.model.ReaderTapZoneMode
import com.aitts.v2.reader.model.ReaderTextAlign
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSettingsCompatibilityTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `legacy json without new typography fields deserializes cleanly`() {
        val legacyJson = """{"paperTheme":"NIGHT","fontSizeSp":21.0,"firstLineIndent":false,"letterSpacingEm":0.08}"""
        val decoded = json.decodeFromString<ReaderSettings>(legacyJson)
        assertEquals(ReaderPaperTheme.NIGHT, decoded.paperTheme)
        assertEquals(21f, decoded.fontSizeSp)
        assertFalse(decoded.firstLineIndent)
        assertEquals(0f, decoded.effectiveIndentEm())
        assertEquals(400, decoded.fontWeight)
        assertEquals(16f, decoded.verticalPaddingDp)
        assertEquals(ReaderTapZoneMode.STANDARD, decoded.tapZoneMode)
        assertTrue(decoded.showHeaderBar)
        assertTrue(decoded.showFooterBar)
    }

    @Test
    fun `effectiveIndentEm prefers explicit paragraphIndentEm over legacy boolean`() {
        val legacyOn = ReaderSettings(firstLineIndent = true, paragraphIndentEm = -1f)
        assertEquals(2f, legacyOn.effectiveIndentEm())

        val overrideOne = ReaderSettings(firstLineIndent = false, paragraphIndentEm = 1f)
        assertEquals(1f, overrideOne.effectiveIndentEm())

        val overrideZero = ReaderSettings(firstLineIndent = true, paragraphIndentEm = 0f)
        assertEquals(0f, overrideZero.effectiveIndentEm())
    }

    @Test
    fun `full round trip preserves all typography and interaction settings`() {
        val full = ReaderSettings(
            paperTheme = ReaderPaperTheme.JADE,
            fontFamily = ReaderFontFamily.MONOSPACE,
            textAlign = ReaderTextAlign.CENTER,
            fontWeight = 600,
            verticalPaddingDp = 24f,
            wordSpacingEm = 0.15f,
            paragraphIndentEm = 1f,
            hyphens = true,
            columnCount = ReaderColumnCount.TWO,
            verticalText = true,
            readingDirection = ReaderDirection.RTL,
            nightImageFilter = ReaderImageFilter.DARKEN,
            tapZoneMode = ReaderTapZoneMode.LEFT_HAND,
            invertVolumeKeys = true,
            showHeaderBar = false,
            showFooterBar = true,
            showBatteryTime = false,
            customBgHex = 0xFF111B27L,
            customTextHex = 0xFFD4E0EDL
        )
        val encoded = json.encodeToString(full)
        val restored = json.decodeFromString<ReaderSettings>(encoded)
        assertEquals(full, restored)
    }
}
