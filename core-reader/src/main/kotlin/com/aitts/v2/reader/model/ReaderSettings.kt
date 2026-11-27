package com.aitts.v2.reader.model

import kotlinx.serialization.Serializable

@Serializable
enum class ReaderPaperTheme(
    val displayName: String,
    val bgHex: Long,
    val textHex: Long,
    val secondaryTextHex: Long,
    val highlightHex: Long,
    val isDark: Boolean
) {
    FOLLOW_APP("跟随应用", 0x00000000L, 0x00000000L, 0x00000000L, 0x00000000L, false),
    CREAM("羊皮纸", 0xFFF5EFE2L, 0xFF231F1AL, 0xFF6B6257L, 0x1F26537CL, false),
    PAPER("宣纸白", 0xFFFAF8F4L, 0xFF1B1917L, 0xFF675F55L, 0x1F26537CL, false),
    JADE("松烟竹青", 0xFFE6EFE8L, 0xFF1A2A22L, 0xFF53695EL, 0x1F24614BL, false),
    NIGHT("墨夜羊皮", 0xFF181614L, 0xFFE6DFD3L, 0xFF9C9387L, 0x336AA0D4L, true),
    AMOLED("纯黑护眼", 0xFF000000L, 0xFFD9D3C7L, 0xFF8C8479L, 0x336AA0D4L, true)
}

@Serializable
enum class ReaderFontFamily(val displayName: String) {
    SERIF("衬线宋体"),
    SANS("无衬线体")
}

@Serializable
data class ReaderSettings(
    val paperTheme: ReaderPaperTheme = ReaderPaperTheme.CREAM,
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.65f,
    val paragraphSpacingDp: Float = 12f,
    val horizontalPaddingDp: Float = 20f,
    val fontFamily: ReaderFontFamily = ReaderFontFamily.SERIF,
    val firstLineIndent: Boolean = true,
    val autoScrollOnTts: Boolean = true,
    val autoNextChapterOnTts: Boolean = true
)
