package com.aitts.v2.model

/**
 * Clean default rules and minimal out-of-box providers.
 * Non-default vendor engines are created cleanly via [ProviderDescriptorRegistry.templates].
 */
object PresetCatalog {

    val defaultRules = listOf(
        ReplacementRule(
            id = "rule_watermark_url",
            pattern = "(?:https?://|www\\.)[a-zA-Z0-9./?=_%&-]+",
            replacement = "",
            isRegex = true,
            description = "过滤夹带的网址与防盗链接",
            category = RuleCategory.WATERMARK
        ),
        ReplacementRule(
            id = "rule_novel_chapter_end",
            pattern = "(?:\\(本章完\\)|（本章完）|PS[:：].*|求月票|求推荐票|求追读|作者有话说.*)",
            replacement = "",
            isRegex = true,
            description = "过滤章节末尾防盗广告与作者求票语",
            category = RuleCategory.WATERMARK
        ),
        ReplacementRule(
            id = "rule_ellipsis",
            pattern = "[…]{2,}|[.]{3,}",
            replacement = "，",
            isRegex = true,
            description = "将过长省略号替换为自然停顿",
            category = RuleCategory.CLEANUP
        ),
        ReplacementRule(
            id = "rule_repeat_symbols",
            pattern = "[~\\-_=+]{3,}",
            replacement = "，",
            isRegex = true,
            description = "将章节分隔符转为自然停顿",
            category = RuleCategory.CLEANUP
        )
    )

    val novelSymbolsPack = listOf(
        ReplacementRule(
            id = "sym_blocks",
            pattern = "[▓█■□▲▼◆◇★☆※●○◎▶▷]+",
            replacement = " ",
            isRegex = true,
            description = "清理装饰方块与星号",
            category = RuleCategory.CLEANUP
        ),
        ReplacementRule(
            id = "sym_brackets",
            pattern = "[【】〖〗\\[\\]{}]",
            replacement = " ",
            isRegex = true,
            description = "清理非对白方括号",
            category = RuleCategory.CLEANUP
        )
    )

    fun createDefaultProviders(): List<TtsProviderConfig> = listOf(
        TtsProviderConfig(
            id = "edge_tts_xiaoxiao",
            type = ProviderType.EDGE_TTS,
            name = "Edge TTS (晓晓 · 默认)",
            modelName = "edge-neural",
            voiceId = "zh-CN-XiaoxiaoNeural"
        )
    )
}
