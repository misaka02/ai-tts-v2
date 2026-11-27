package com.aitts.v2.model

import kotlinx.serialization.Serializable

@Serializable
enum class SegmentationMode(val displayName: String, val description: String) {
    PARAGRAPH("按自然段落", "按换行符划分段落，兼顾上下文完整度"),
    PUNCTUATION("按标点断句", "按句末标点与长度阈值切分，适合流式快速响应"),
    SMART_HYBRID("智能对白剧场", "基于平衡引号栈拆出旁白与角色对白，注入情绪与分角声线")
}

@Serializable
enum class SpeedPolicy(val displayName: String, val description: String) {
    MULTIPLY("联动乘算 (推荐)", "引擎倍速 × 阅读器传入倍速 × 全局倍率"),
    ENGINE_ONLY("仅用引擎配置", "忽略第三方阅读器传入倍速，始终按本引擎设定朗读"),
    SYSTEM_ONLY("仅跟随阅读器", "始终跟随系统/阅读器传入倍速，忽略引擎默认倍速")
}

@Serializable
enum class AudioProcessingMode(
    val displayName: String,
    val clarityBoost: Boolean,
    val defaultGain: Float,
    val description: String
) {
    PASSTHROUGH("原声直出 (推荐)", false, 1.0f, "不做滤波与动态压缩，100% 保留原始音质"),
    CLARITY("清澈人声", true, 1.2f, "高通预加重提升人声齿音清晰度"),
    WARM("磁性电台", false, 1.3f, "柔和增益与软限幅，适合通勤嘈杂环境"),
    NIGHT_PROTECT("睡前护耳", false, 0.9f, "轻微限幅与柔化动态，适合夜间低音量听书"),
    CUSTOM("自定义参数", false, 1.0f, "手动指定清晰度滤波与响度增益")
}

@Serializable
enum class ThemeMode(val displayName: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色模式"),
    DARK("深色模式")
}

@Serializable
enum class ThemePalette(val displayName: String, val seedHex: Long) {
    OCEAN_AZURE("靛蓝印泥", 0xFF26537C),
    EMERALD_JADE("松烟竹青", 0xFF24614B),
    SLATE_TITANIUM("紫檀砚灰", 0xFF4E4A75),
    WARM_AMBER("朱砂琥珀", 0xFFA8551E)
}

@Serializable
enum class SubtitleNovelPreset(val displayName: String, val bgHex: Long, val textHex: Long) {
    AUTO("跟随系统", 0xFF181A20, 0xFFF5F5F7),
    WHITE("纯白书页", 0xFFFFFFFF, 0xFF0F172A),
    PARCHMENT("米黄羊皮纸", 0xFFF6EED8, 0xFF382314),
    GREEN("护眼豆沙绿", 0xFFD3E7D5, 0xFF123524),
    INK_GREY("水墨浅灰", 0xFFE2E8F0, 0xFF0F172A),
    NIGHT("暗夜极黑", 0xFF121316, 0xFFF5F5F7)
}

@Serializable
enum class SubtitleAlignment {
    LEFT,
    CENTER
}

@Serializable
data class GlobalSettings(
    val activeProviderId: String = "edge_tts_xiaoxiao",
    val isSentenceSplittingEnabled: Boolean = true,
    val textSegmentationMode: SegmentationMode = SegmentationMode.PARAGRAPH,
    val mergeShortParagraphs: Boolean = false,
    val minMergeParagraphLength: Int = 30,
    val splitLongParagraphs: Boolean = true,
    val maxSegmentLength: Int = 180,
    val enableSegmentPreload: Boolean = true,
    val preloadAheadCount: Int = 2,
    val ultraLowLatencyMode: Boolean = true,
    val isAudioCacheEnabled: Boolean = true,
    val maxCacheSizeMb: Int = 300,
    val isNumberNormalizationEnabled: Boolean = true,
    val isAcronymNormalizationEnabled: Boolean = true,
    val isEmotionProsodyEnabled: Boolean = true,
    val speedPolicy: SpeedPolicy = SpeedPolicy.MULTIPLY,
    val globalSpeed: Float = 1.0f,
    val globalPitch: Float = 1.0f,
    val sentencePauseMs: Int = 180,
    val audioProcessingMode: AudioProcessingMode = AudioProcessingMode.PASSTHROUGH,
    val voiceClarityBoostEnabled: Boolean = false,
    val loudnessGainFactor: Float = 1.0f,
    val trimDeadSilence: Boolean = false,
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "127.0.0.1",
    val proxyPort: Int = 7890,
    val proxyType: String = "HTTP",
    val connectTimeoutSeconds: Int = 15,
    val readTimeoutSeconds: Int = 60,
    val fallbackProviderId: String = "edge_tts_xiaoxiao",
    val autoFallbackOnFailure: Boolean = true,
    val autoRetryOnFailure: Boolean = true,
    val playbackNotificationEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val themePalette: ThemePalette = ThemePalette.OCEAN_AZURE,
    val isAmoledPureBlack: Boolean = false,
    val isFloatingSubtitleEnabled: Boolean = false,
    val floatingSubtitleOpacity: Float = 0.90f,
    val floatingSubtitleFontSize: Int = 14,
    val floatingSubtitleAlignment: SubtitleAlignment = SubtitleAlignment.LEFT,
    val floatingSubtitleMaxLines: Int = 3,
    val floatingSubtitleLockPosition: Boolean = false,
    val floatingSubtitleShowIcon: Boolean = true,
    val floatingSubtitleAdaptiveContrast: Boolean = true,
    val floatingSubtitleNovelPreset: SubtitleNovelPreset = SubtitleNovelPreset.AUTO,
    val floatingSubtitleY: Int = -1
)
