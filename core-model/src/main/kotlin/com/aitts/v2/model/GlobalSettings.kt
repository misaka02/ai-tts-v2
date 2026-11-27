package com.aitts.v2.model

import kotlinx.serialization.Serializable

@Serializable
enum class SpeedPolicy(val displayName: String, val description: String) {
    MULTIPLY("联动乘算 (推荐)", "引擎倍速 × 阅读器传入倍速 × 全局倍率"),
    ENGINE_ONLY("仅用引擎配置", "忽略第三方阅读器传入倍速，始终按本引擎设定朗读"),
    SYSTEM_ONLY("仅跟随阅读器", "始终跟随系统/阅读器传入倍速，忽略引擎默认倍速")
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
enum class SubtitleAlignment {
    LEFT,
    CENTER
}

@Serializable
data class GlobalSettings(
    val activeProviderId: String = "edge_tts_xiaoxiao",
    val isSmartSegmentationEnabled: Boolean = true,
    val preloadAheadCount: Int = 2,
    val isAudioCacheEnabled: Boolean = true,
    val maxCacheSizeMb: Int = 300,
    val isNumberNormalizationEnabled: Boolean = false,
    val speedPolicy: SpeedPolicy = SpeedPolicy.MULTIPLY,
    val globalSpeed: Float = 1.0f,
    val globalPitch: Float = 1.0f,
    val sentencePauseMs: Int = 180,
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
    val floatingSubtitleLockPosition: Boolean = false,
    val floatingSubtitleY: Int = -1,
    val manifestUrl: String = DEFAULT_MANIFEST_URL
) {
    companion object {
        const val DEFAULT_MANIFEST_URL =
            "https://raw.githubusercontent.com/misaka02/ai-tts-v2/main/catalog/manifest.json"
    }
}
