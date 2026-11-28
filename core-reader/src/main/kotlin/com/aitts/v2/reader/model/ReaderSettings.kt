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
    SANS("无衬线体"),
    MONOSPACE("等宽字体")
}

@Serializable
enum class ReaderReadingMode(val displayName: String) {
    PAGED("左右翻页"),
    SCROLL("上下滚动")
}

@Serializable
enum class ReaderTextAlign(val displayName: String) {
    AUTO("跟随原书"),
    START("左对齐"),
    JUSTIFY("两端对齐"),
    CENTER("居中")
}

@Serializable
enum class ReaderColumnCount(val displayName: String) {
    AUTO("自动"),
    ONE("单栏"),
    TWO("双栏")
}

@Serializable
enum class ReaderDirection(val displayName: String) {
    AUTO("自动"),
    LTR("从左到右"),
    RTL("从右到左")
}

@Serializable
enum class ReaderImageFilter(val displayName: String) {
    NONE("原图"),
    DARKEN("夜间变暗"),
    INVERT("反色")
}

@Serializable
enum class ReaderTapZoneMode(val displayName: String) {
    STANDARD("标准左退右进"),
    LEFT_HAND("左手左进右退"),
    ALL_NEXT("点击均下一页"),
    SWIPE_ONLY("仅滑动翻页")
}

@Serializable
enum class ReaderOrientationLock(val displayName: String) {
    SYSTEM("跟随系统"),
    PORTRAIT("锁定竖屏"),
    LANDSCAPE("锁定横屏")
}

@Serializable
enum class ReaderScriptConvert(val displayName: String, val code: Int) {
    NONE("原文字形", 0),
    TO_SIMPLIFIED("转简体中文", 1),
    TO_TRADITIONAL("转繁体中文", 2)
}

@Serializable
enum class ReaderPageAnimation(val displayName: String) {
    SLIDE("平滑翻页"),
    NONE("无动画直切")
}

@Serializable
data class ReaderSettings(
    val paperTheme: ReaderPaperTheme = ReaderPaperTheme.CREAM,
    val fontSizeSp: Float = 18f,
    val lineHeightMultiplier: Float = 1.65f,
    val paragraphSpacingDp: Float = 12f,
    val horizontalPaddingDp: Float = 20f,
    val fontFamily: ReaderFontFamily = ReaderFontFamily.SERIF,
    val readingMode: ReaderReadingMode = ReaderReadingMode.PAGED,
    val volumeKeyPaging: Boolean = true,
    val firstLineIndent: Boolean = true,
    val autoScrollOnTts: Boolean = true,
    val autoNextChapterOnTts: Boolean = true,
    val brightness: Float = -1f,
    val keepScreenOn: Boolean = true,
    val fullscreenImmersive: Boolean = false,
    val publisherStyles: Boolean = false,
    val textAlign: ReaderTextAlign = ReaderTextAlign.START,
    val letterSpacingEm: Float = 0f,
    val customFontPath: String = "",
    val fontWeight: Int = 400,
    val verticalPaddingDp: Float = 16f,
    val wordSpacingEm: Float = 0f,
    val paragraphIndentEm: Float = -1f,
    val hyphens: Boolean = false,
    val ligatures: Boolean = true,
    val columnCount: ReaderColumnCount = ReaderColumnCount.AUTO,
    val verticalText: Boolean = false,
    val readingDirection: ReaderDirection = ReaderDirection.AUTO,
    val nightImageFilter: ReaderImageFilter = ReaderImageFilter.NONE,
    val tapZoneMode: ReaderTapZoneMode = ReaderTapZoneMode.STANDARD,
    val invertVolumeKeys: Boolean = false,
    val showHeaderBar: Boolean = true,
    val showFooterBar: Boolean = true,
    val showBatteryTime: Boolean = true,
    val customBgHex: Long = 0L,
    val customTextHex: Long = 0L,
    val orientationLock: ReaderOrientationLock = ReaderOrientationLock.SYSTEM,
    val scriptConvert: ReaderScriptConvert = ReaderScriptConvert.NONE,
    val pageAnimation: ReaderPageAnimation = ReaderPageAnimation.SLIDE,
    val leftEdgeBrightnessGesture: Boolean = true,
    val autoScrollSpeed: Int = 4
) {
    fun effectiveIndentEm(): Float = when {
        paragraphIndentEm >= 0f -> paragraphIndentEm
        firstLineIndent -> 2f
        else -> 0f
    }

    companion object {
        val Default = ReaderSettings()
    }
}
