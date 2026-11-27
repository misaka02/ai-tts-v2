package com.aitts.v2.model

/**
 * Curated one-click provider templates grouped by engine category.
 */
object ProviderTemplates {
    val all: List<ProviderTemplate> = listOf(
        ProviderTemplate(
            id = "tpl_edge",
            title = "Microsoft Edge TTS",
            subtitle = "免 Key 在线神经语音，实时同步多国音色",
            category = EngineCategory.TRADITIONAL,
            config = TtsProviderConfig(
                id = "tpl_edge",
                type = ProviderType.EDGE_TTS,
                name = "Edge TTS (云希)",
                modelName = "edge-neural",
                voiceId = "zh-CN-YunxiNeural"
            )
        ),
        ProviderTemplate(
            id = "tpl_azure",
            title = "Azure Speech Services",
            subtitle = "微软官方订阅 API，支持实时风格与强度调节",
            category = EngineCategory.TRADITIONAL,
            config = TtsProviderConfig(
                id = "tpl_azure",
                type = ProviderType.AZURE,
                name = "Azure 语音",
                baseUrl = "https://eastasia.tts.speech.microsoft.com/cognitiveservices/v1",
                modelName = "azure-neural",
                voiceId = "zh-CN-XiaoxiaoNeural"
            )
        ),
        ProviderTemplate(
            id = "tpl_openai",
            title = "OpenAI TTS / 兼容中转",
            subtitle = "gpt-4o-mini-tts / tts-1，支持原生流式与指令",
            category = EngineCategory.LLM_GENERATIVE,
            config = TtsProviderConfig(
                id = "tpl_openai",
                type = ProviderType.OPENAI_COMPATIBLE,
                name = "OpenAI TTS",
                baseUrl = "https://api.openai.com/v1/audio/speech",
                modelName = "gpt-4o-mini-tts",
                voiceId = "nova",
                isStreamingEnabled = true
            )
        ),
        ProviderTemplate(
            id = "tpl_siliconflow",
            title = "硅基流动 (CosyVoice2)",
            subtitle = "兼容 OpenAI 接口，自动发现上传音色",
            category = EngineCategory.LLM_GENERATIVE,
            config = TtsProviderConfig(
                id = "tpl_siliconflow",
                type = ProviderType.OPENAI_COMPATIBLE,
                name = "硅基流动 CosyVoice2",
                baseUrl = "https://api.siliconflow.cn/v1/audio/speech",
                modelName = "FunAudioLLM/CosyVoice2-0.5B",
                voiceId = "FunAudioLLM/CosyVoice2-0.5B:alex",
                isStreamingEnabled = true
            )
        ),
        ProviderTemplate(
            id = "tpl_gemini",
            title = "Google Gemini TTS",
            subtitle = "Gemini 2.5 Flash 原生多模态音频与 SSE 流式",
            category = EngineCategory.LLM_GENERATIVE,
            config = TtsProviderConfig(
                id = "tpl_gemini",
                type = ProviderType.GEMINI,
                name = "Gemini 2.5 Flash TTS",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                modelName = "gemini-2.5-flash-preview-tts",
                voiceId = "Puck",
                promptInstruction = "Read the text clearly and naturally.",
                audioFormat = "wav",
                isStreamingEnabled = true
            )
        ),
        ProviderTemplate(
            id = "tpl_mimo",
            title = "小米 MiMo-V2.5-TTS",
            subtitle = "原生指令情感演播，SSE 低延迟流式",
            category = EngineCategory.LLM_GENERATIVE,
            config = TtsProviderConfig(
                id = "tpl_mimo",
                type = ProviderType.MIMO,
                name = "小米 MiMo-TTS",
                baseUrl = "https://api.xiaomimimo.com/v1/chat/completions",
                modelName = "mimo-v2.5-tts",
                voiceId = "茉莉",
                isStreamingEnabled = true
            )
        ),
        ProviderTemplate(
            id = "tpl_minimax",
            title = "MiniMax Speech-02",
            subtitle = "实时拉取官方/复刻音色，支持原生情绪调节",
            category = EngineCategory.LLM_GENERATIVE,
            config = TtsProviderConfig(
                id = "tpl_minimax",
                type = ProviderType.MINIMAX,
                name = "MiniMax Speech-02",
                baseUrl = "https://api.minimax.chat/v1/t2a_v2",
                modelName = "speech-02-turbo",
                voiceId = "male-qn-qingse",
                sampleRate = 32000,
                isStreamingEnabled = true
            )
        ),
        ProviderTemplate(
            id = "tpl_doubao",
            title = "字节火山 / 豆包语音",
            subtitle = "支持火山引擎 AppID + 音色与情感标签",
            category = EngineCategory.TRADITIONAL,
            config = TtsProviderConfig(
                id = "tpl_doubao",
                type = ProviderType.DOUBAO,
                name = "豆包语音",
                baseUrl = "https://openspeech.bytedance.com/api/v1/tts",
                modelName = "volcano_tts",
                voiceId = "zh_female_shuangkuaisisi_moon_bigtts",
                extraParams = mapOf("appId" to "", "cluster" to "volcano_tts")
            )
        ),
        ProviderTemplate(
            id = "tpl_fish",
            title = "Fish Audio",
            subtitle = "高质量社区音色库，支持自定义端点发现",
            category = EngineCategory.TRADITIONAL,
            config = TtsProviderConfig(
                id = "tpl_fish",
                type = ProviderType.FISH_AUDIO,
                name = "Fish Audio",
                baseUrl = "https://api.fish.audio/v1/tts",
                modelName = "speech-1.5",
                voiceId = "7f92f8afb8ec43bf81429cc1c9199cb1"
            )
        ),
        ProviderTemplate(
            id = "tpl_sherpa",
            title = "本机离线 Sherpa-ONNX",
            subtitle = "无需联网，支持 VITS / Matcha / Kokoro / ZipVoice",
            category = EngineCategory.TRADITIONAL,
            config = TtsProviderConfig(
                id = "tpl_sherpa",
                type = ProviderType.OFFLINE_SHERPA,
                name = "Sherpa 离线引擎",
                modelName = "vits-piper-zh_CN-xiao_ya-medium-int8",
                voiceId = "spk_0",
                sampleRate = 22050,
                audioFormat = "wav",
                extraParams = mapOf("numThreads" to "2", "silenceScale" to "0.2")
            )
        ),
        ProviderTemplate(
            id = "tpl_custom",
            title = "自定义 HTTP 服务 (GPT-SoVITS 等)",
            subtitle = "自定义 URL / Headers / JSON Body 与音频字段提取",
            category = EngineCategory.TRADITIONAL,
            config = TtsProviderConfig(
                id = "tpl_custom",
                type = ProviderType.CUSTOM_HTTP,
                name = "自建 HTTP TTS",
                baseUrl = "http://127.0.0.1:9880/tts?text=\${text}&text_lang=zh&speed_factor=\${speed}",
                customPayloadTemplate = "",
                audioFormat = "wav"
            )
        )
    )
}
