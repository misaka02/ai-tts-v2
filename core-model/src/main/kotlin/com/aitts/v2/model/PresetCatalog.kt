package com.aitts.v2.model

/**
 * Default replacement rules, preset rule packs, and default provider profiles.
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
        ),
        ReplacementRule("rule_chongqing", "重庆", "崇庆", false, description = "多音字：重庆 (chóng)", category = RuleCategory.POLYPHONE),
        ReplacementRule("rule_yinhang", "银行", "银航", false, description = "多音字：银行 (háng)", category = RuleCategory.POLYPHONE),
        ReplacementRule("rule_canci", "参差", "涔呲", false, description = "多音字：参差 (cēn cī)", category = RuleCategory.POLYPHONE),
        ReplacementRule("rule_guanqia", "关卡", "关恰", false, description = "多音字：关卡 (qiǎ)", category = RuleCategory.POLYPHONE),
        ReplacementRule("rule_xueruo", "削弱", "薛弱", false, description = "多音字：削弱 (xuē)", category = RuleCategory.POLYPHONE),
        ReplacementRule("rule_qiuzi", "龟兹", "丘慈", false, description = "古地名：龟兹 (qiū cí)", category = RuleCategory.POLYPHONE)
    )

    val xianxiaRulesPack = listOf(
        ReplacementRule("xianxia_dantian", "丹田", "单田", false, description = "丹田 (dān)", category = RuleCategory.POLYPHONE),
        ReplacementRule("xianxia_zhuji", "筑基", "住基", false, description = "筑基 (zhù)", category = RuleCategory.POLYPHONE),
        ReplacementRule("xianxia_jiejie", "桀桀", "节节", false, description = "桀桀 (jié)", category = RuleCategory.POLYPHONE),
        ReplacementRule("xianxia_qiankun", "乾坤", "前坤", false, description = "乾坤 (qián)", category = RuleCategory.POLYPHONE),
        ReplacementRule("xianxia_shihai", "识海", "拾海", false, description = "识海 (shí)", category = RuleCategory.POLYPHONE),
        ReplacementRule("xianxia_kuilei", "傀儡", "魁累", false, description = "傀儡 (kuǐ lěi)", category = RuleCategory.POLYPHONE),
        ReplacementRule("xianxia_zhigu", "桎梏", "至固", false, description = "桎梏 (zhì gù)", category = RuleCategory.POLYPHONE),
        ReplacementRule("xianxia_pini", "睥睨", "辟逆", false, description = "睥睨 (pì nì)", category = RuleCategory.POLYPHONE)
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
        ),
        ReplacementRule(
            id = "sym_yyds",
            pattern = "\\byyds\\b",
            replacement = "永远的神",
            isRegex = true,
            description = "yyds -> 永远的神",
            category = RuleCategory.SPECIAL
        )
    )

    fun createDefaultProviders(): List<TtsProviderConfig> =
        defaultEdgeAndLlmProviders() + defaultVendorAndCustomProviders() + defaultOfflineProviders()

    private fun defaultEdgeAndLlmProviders(): List<TtsProviderConfig> = listOf(
        TtsProviderConfig(
            id = "edge_tts_xiaoxiao",
            type = ProviderType.EDGE_TTS,
            name = "Edge TTS (晓晓 · 免费默认)",
            modelName = "edge-neural",
            voiceId = "zh-CN-XiaoxiaoNeural",
            tags = listOf("免Key", "中文女声")
        ),
        TtsProviderConfig(
            id = "edge_tts_yunxi",
            type = ProviderType.EDGE_TTS,
            name = "Edge TTS (云希 · 沉稳男声)",
            modelName = "edge-neural",
            voiceId = "zh-CN-YunxiNeural",
            tags = listOf("免Key", "中文男声")
        ),
        TtsProviderConfig(
            id = "mimo_default",
            type = ProviderType.MIMO,
            name = "小米 MiMo-V2.5-TTS",
            enabled = false,
            baseUrl = "https://api.xiaomimimo.com/v1/chat/completions",
            modelName = "mimo-v2.5-tts",
            voiceId = "茉莉"
        ),
        TtsProviderConfig(
            id = "gemini_default",
            type = ProviderType.GEMINI,
            name = "Google Gemini 2.5 Flash TTS",
            enabled = false,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            modelName = "gemini-2.5-flash-preview-tts",
            voiceId = "Puck",
            promptInstruction = "Read the text clearly and naturally.",
            audioFormat = "wav"
        ),
        TtsProviderConfig(
            id = "siliconflow_cosyvoice",
            type = ProviderType.OPENAI_COMPATIBLE,
            name = "硅基流动 (CosyVoice2)",
            enabled = false,
            baseUrl = "https://api.siliconflow.cn/v1/audio/speech",
            modelName = "FunAudioLLM/CosyVoice2-0.5B",
            voiceId = "FunAudioLLM/CosyVoice2-0.5B:alex"
        ),
        TtsProviderConfig(
            id = "openai_default",
            type = ProviderType.OPENAI_COMPATIBLE,
            name = "OpenAI / 兼容中转",
            enabled = false,
            baseUrl = "https://api.openai.com/v1/audio/speech",
            modelName = "tts-1",
            voiceId = "nova"
        )
    )

    private fun defaultVendorAndCustomProviders(): List<TtsProviderConfig> = listOf(
        TtsProviderConfig(
            id = "stepfun_default",
            type = ProviderType.OPENAI_COMPATIBLE,
            name = "阶跃星辰 (Step-Audio)",
            enabled = false,
            baseUrl = "https://api.stepfun.com/v1/audio/speech",
            modelName = "stepaudio-2.5-tts",
            voiceId = "cixingnansheng"
        ),
        TtsProviderConfig(
            id = "minimax_default",
            type = ProviderType.MINIMAX,
            name = "MiniMax Speech-02",
            enabled = false,
            baseUrl = "https://api.minimax.chat/v1/t2a_v2",
            modelName = "speech-02-turbo",
            voiceId = "male-qn-qingse",
            sampleRate = 32000
        ),
        TtsProviderConfig(
            id = "doubao_default",
            type = ProviderType.DOUBAO,
            name = "火山引擎 / 豆包语音",
            enabled = false,
            baseUrl = "https://openspeech.bytedance.com/api/v1/tts",
            modelName = "volcano_tts",
            voiceId = "zh_female_shuangkuaisisi_moon_bigtts",
            extraParams = mapOf("appId" to "", "cluster" to "volcano_tts")
        ),
        TtsProviderConfig(
            id = "fish_default",
            type = ProviderType.FISH_AUDIO,
            name = "Fish Audio (鱼音)",
            enabled = false,
            baseUrl = "https://api.fish.audio/v1/tts",
            modelName = "speech-1.5",
            voiceId = "7f92f8afb8ec43bf81429cc1c9199cb1"
        ),
        TtsProviderConfig(
            id = "azure_default",
            type = ProviderType.AZURE,
            name = "Azure 认知语音",
            enabled = false,
            baseUrl = "https://eastasia.tts.speech.microsoft.com/cognitiveservices/v1",
            modelName = "azure-neural",
            voiceId = "zh-CN-XiaoxiaoNeural"
        ),
        TtsProviderConfig(
            id = "custom_gpt_sovits",
            type = ProviderType.CUSTOM_HTTP,
            name = "自定义 HTTP (GPT-SoVITS 示例)",
            enabled = false,
            baseUrl = "http://192.168.1.100:9880/tts",
            modelName = "gpt-sovits-v2",
            voiceId = "default",
            customPayloadTemplate = "{\n  \"text\": \"\${text}\",\n  \"text_lang\": \"zh\",\n  \"speed\": \${speed}\n}",
            sampleRate = 32000,
            audioFormat = "wav"
        )
    )

    private fun defaultOfflineProviders(): List<TtsProviderConfig> = listOf(
        TtsProviderConfig(
            id = "offline_piper_chaowen_int8",
            type = ProviderType.OFFLINE_SHERPA,
            name = "离线 Piper 超文男声 (INT8)",
            enabled = false,
            modelName = "vits-piper-zh_CN-chaowen-medium-int8",
            voiceId = "spk_0",
            sampleRate = 22050,
            audioFormat = "wav",
            tags = listOf("离线", "13MB")
        ),
        TtsProviderConfig(
            id = "offline_piper_huayan",
            type = ProviderType.OFFLINE_SHERPA,
            name = "离线 Piper 华言女声",
            enabled = false,
            modelName = "vits-piper-zh_CN-huayan-medium",
            voiceId = "spk_0",
            sampleRate = 22050,
            audioFormat = "wav",
            tags = listOf("离线", "VITS")
        ),
        TtsProviderConfig(
            id = "offline_matcha_baker",
            type = ProviderType.OFFLINE_SHERPA,
            name = "离线 Matcha 标贝女声",
            enabled = false,
            modelName = "matcha-icefall-zh-baker",
            voiceId = "spk_0",
            sampleRate = 22050,
            audioFormat = "wav",
            tags = listOf("离线", "Matcha")
        ),
        TtsProviderConfig(
            id = "offline_aishell3",
            type = ProviderType.OFFLINE_SHERPA,
            name = "离线 AISHELL-3 (174发音人)",
            enabled = false,
            modelName = "vits-icefall-zh-aishell3",
            voiceId = "spk_0",
            sampleRate = 22050,
            audioFormat = "wav",
            tags = listOf("离线", "多发音人")
        )
    )
}
