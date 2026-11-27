package com.aitts.v2.model

enum class EngineCategory(val displayName: String) {
    TRADITIONAL("传统参数引擎"),
    LLM_GENERATIVE("AI 大模型引擎")
}

sealed interface ProviderParamSpec {
    val key: String
    val label: String
    val defaultValue: String
    val hint: String

    data class ParamText(
        override val key: String,
        override val label: String,
        override val defaultValue: String = "",
        override val hint: String = ""
    ) : ProviderParamSpec

    data class ParamSelect(
        override val key: String,
        override val label: String,
        val options: List<String>,
        override val defaultValue: String = "",
        override val hint: String = ""
    ) : ProviderParamSpec

    data class ParamSlider(
        override val key: String,
        override val label: String,
        val min: Float,
        val max: Float,
        override val defaultValue: String,
        override val hint: String = ""
    ) : ProviderParamSpec
}

data class ProviderDescriptor(
    val type: ProviderType,
    val category: EngineCategory,
    val defaultBaseUrl: String,
    val supportsBaseUrl: Boolean,
    val acousticSpec: EngineAcousticSpec,
    val supportsStreaming: Boolean,
    val supportsDirectorPrompt: Boolean,
    val extraParams: List<ProviderParamSpec>,
    val summary: String
) {
    val supportsPitch: Boolean
        get() = acousticSpec.supportsPitch
}

data class ProviderTemplate(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: EngineCategory,
    val config: TtsProviderConfig
)

object ProviderDescriptorRegistry {

    private val descriptors: Map<ProviderType, ProviderDescriptor> = listOf(
        ProviderDescriptor(
            type = ProviderType.EDGE_TTS,
            category = EngineCategory.TRADITIONAL,
            defaultBaseUrl = "https://speech.platform.bing.com",
            supportsBaseUrl = true,
            acousticSpec = EngineAcousticSpec(0.5f..2.0f, 0.5f..1.5f, "SSML 音调 -50Hz ~ +50Hz"),
            supportsStreaming = false,
            supportsDirectorPrompt = false,
            extraParams = listOf(
                ProviderParamSpec.ParamText("gecVersion", "Sec-MS-GEC 版本号", "", "留空使用远程清单或内置版本")
            ),
            summary = "微软在线神经语音，支持 SSML 语速/音调(Hz)调节与实时音色列表同步"
        ),
        ProviderDescriptor(
            type = ProviderType.AZURE,
            category = EngineCategory.TRADITIONAL,
            defaultBaseUrl = "https://eastasia.tts.speech.microsoft.com/cognitiveservices/v1",
            supportsBaseUrl = true,
            acousticSpec = EngineAcousticSpec(0.5f..3.0f, 0.5f..1.5f, "SSML 音调 -50% ~ +50%"),
            supportsStreaming = false,
            supportsDirectorPrompt = false,
            extraParams = listOf(
                ProviderParamSpec.ParamSelect("style", "SSML 语气风格", emptyList(), "", "将随所选音色自动同步 StyleList"),
                ProviderParamSpec.ParamSlider("styleDegree", "风格强度", 0.5f, 2.0f, "1.0", "数值越高情绪表现越强")
            ),
            summary = "Azure Speech 服务，实时同步区域音色及 StyleList 并支持 SSML 风格强度"
        ),
        ProviderDescriptor(
            type = ProviderType.DOUBAO,
            category = EngineCategory.TRADITIONAL,
            defaultBaseUrl = "https://openspeech.bytedance.com/api/v1/tts",
            supportsBaseUrl = true,
            acousticSpec = EngineAcousticSpec(0.5f..2.0f, 0.5f..1.5f, "标准音色 0.5~1.5 (bigtts 自动省略)"),
            supportsStreaming = false,
            supportsDirectorPrompt = false,
            extraParams = listOf(
                ProviderParamSpec.ParamText("appId", "火山引擎 AppID (必填)", "", "可在控制台查看"),
                ProviderParamSpec.ParamText("cluster", "Cluster 集群", "volcano_tts", "默认 volcano_tts"),
                ProviderParamSpec.ParamSelect(
                    "emotion",
                    "情感标签 (可选)",
                    listOf("", "happy", "sad", "angry", "gentle", "chat", "news"),
                    ""
                )
            ),
            summary = "字节火山/豆包语音合成，原生支持语速/音调比例及情感标签"
        ),
        ProviderDescriptor(
            type = ProviderType.FISH_AUDIO,
            category = EngineCategory.TRADITIONAL,
            defaultBaseUrl = "https://api.fish.audio/v1/tts",
            supportsBaseUrl = true,
            acousticSpec = EngineAcousticSpec(0.5f..2.0f, null),
            supportsStreaming = false,
            supportsDirectorPrompt = false,
            extraParams = listOf(
                ProviderParamSpec.ParamSelect("latency", "合成延迟策略", listOf("balanced", "normal"), "balanced")
            ),
            summary = "Fish Audio 声音克隆与社区音色库，支持按自定义 Base URL 实时发现模型"
        ),
        ProviderDescriptor(
            type = ProviderType.OFFLINE_SHERPA,
            category = EngineCategory.TRADITIONAL,
            defaultBaseUrl = "",
            supportsBaseUrl = false,
            acousticSpec = EngineAcousticSpec(0.5f..2.5f, null),
            supportsStreaming = false,
            supportsDirectorPrompt = false,
            extraParams = listOf(
                ProviderParamSpec.ParamSlider("numThreads", "CPU 推理线程数", 1f, 4f, "2", "仅 Full ARM64 版生效"),
                ProviderParamSpec.ParamSlider("silenceScale", "句尾停顿缩放", 0.0f, 1.0f, "0.2", "控制离线模型分句停顿")
            ),
            summary = "本机 Sherpa-ONNX 离线推理，零网络依赖，可调线程数与停顿系数"
        ),
        ProviderDescriptor(
            type = ProviderType.CUSTOM_HTTP,
            category = EngineCategory.TRADITIONAL,
            defaultBaseUrl = "http://127.0.0.1:9880/tts",
            supportsBaseUrl = true,
            acousticSpec = EngineAcousticSpec(0.25f..4.0f, 0.5f..2.0f, "通过 \${pitch} 占位符透传"),
            supportsStreaming = false,
            supportsDirectorPrompt = true,
            extraParams = emptyList(),
            summary = "通用自建 HTTP/JSON 模板（GPT-SoVITS / CosyVoice / 代理）"
        ),
        ProviderDescriptor(
            type = ProviderType.OPENAI_COMPATIBLE,
            category = EngineCategory.LLM_GENERATIVE,
            defaultBaseUrl = "https://api.openai.com/v1/audio/speech",
            supportsBaseUrl = true,
            acousticSpec = EngineAcousticSpec(0.25f..4.0f, null),
            supportsStreaming = true,
            supportsDirectorPrompt = true,
            extraParams = emptyList(),
            summary = "OpenAI / 硅基流动 / 阶跃 / 各类兼容网关，支持原生 PCM 流式与 instructions"
        ),
        ProviderDescriptor(
            type = ProviderType.GEMINI,
            category = EngineCategory.LLM_GENERATIVE,
            defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
            supportsBaseUrl = true,
            acousticSpec = EngineAcousticSpec(null, null),
            supportsStreaming = true,
            supportsDirectorPrompt = true,
            extraParams = emptyList(),
            summary = "Google Gemini 原生多模态音频输出，支持自然语言导演指令与 SSE 流式"
        ),
        ProviderDescriptor(
            type = ProviderType.MIMO,
            category = EngineCategory.LLM_GENERATIVE,
            defaultBaseUrl = "https://api.xiaomimimo.com/v1/chat/completions",
            supportsBaseUrl = true,
            acousticSpec = EngineAcousticSpec(null, null),
            supportsStreaming = true,
            supportsDirectorPrompt = true,
            extraParams = emptyList(),
            summary = "小米 MiMo-V2.5-TTS 大模型对话语音，支持情感指令与 SSE PCM 流式"
        ),
        ProviderDescriptor(
            type = ProviderType.MINIMAX,
            category = EngineCategory.LLM_GENERATIVE,
            defaultBaseUrl = "https://api.minimax.chat/v1/t2a_v2",
            supportsBaseUrl = true,
            acousticSpec = EngineAcousticSpec(0.5f..2.0f, 0.5f..1.5f, "官方 -12 ~ +12 半音"),
            supportsStreaming = true,
            supportsDirectorPrompt = false,
            extraParams = listOf(
                ProviderParamSpec.ParamSelect(
                    "emotion",
                    "官方情绪标签",
                    listOf("", "happy", "sad", "angry", "fearful", "disgusted", "surprised", "neutral"),
                    "",
                    "Speech-02 系列原生支持"
                )
            ),
            summary = "MiniMax Speech-02 大模型语音，支持官方 get_voice 实时拉取与原生情绪控制"
        )
    ).associateBy { it.type }

    fun descriptorFor(type: ProviderType): ProviderDescriptor =
        descriptors[type] ?: error("Missing descriptor for $type")

    fun allDescriptors(): List<ProviderDescriptor> = ProviderType.entries.map { descriptorFor(it) }

    val templates: List<ProviderTemplate>
        get() = ProviderTemplates.all
}
