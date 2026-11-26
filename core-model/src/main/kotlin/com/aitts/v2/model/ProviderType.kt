package com.aitts.v2.model

import kotlinx.serialization.Serializable

/**
 * Supported TTS provider protocol families.
 */
@Serializable
enum class ProviderType(
    val displayName: String,
    val description: String,
    val requiresApiKey: Boolean
) {
    EDGE_TTS(
        displayName = "微软 Edge TTS (免费)",
        description = "免 Key 直连微软神经网络语音 (晓晓、云希等)",
        requiresApiKey = false
    ),
    MIMO(
        displayName = "小米 MiMo TTS",
        description = "MiMo-V2.5-TTS 语音大模型，支持导演模式与 SSE 流式",
        requiresApiKey = true
    ),
    GEMINI(
        displayName = "Google Gemini TTS",
        description = "Gemini 原生音频多模态生成，支持 30 款预置音色",
        requiresApiKey = true
    ),
    OPENAI_COMPATIBLE(
        displayName = "OpenAI 兼容接口",
        description = "统一适配 OpenAI / 硅基流动 (CosyVoice2) / 阶跃星辰 / 各类中转站",
        requiresApiKey = true
    ),
    MINIMAX(
        displayName = "MiniMax (海螺语音)",
        description = "Speech-02 拟真大模型，支持 T2A v2 同步与 SSE 流式",
        requiresApiKey = true
    ),
    DOUBAO(
        displayName = "火山引擎 / 豆包语音",
        description = "字节跳动豆包大模型语音合成 (需填写 AppID 与 AccessToken)",
        requiresApiKey = true
    ),
    FISH_AUDIO(
        displayName = "Fish Audio (鱼音)",
        description = "高表现力克隆声音大模型",
        requiresApiKey = true
    ),
    AZURE(
        displayName = "微软 Azure Speech",
        description = "Azure 认知服务官方 SSML 语音合成",
        requiresApiKey = true
    ),
    CUSTOM_HTTP(
        displayName = "自定义 HTTP 模板",
        description = "适配 GPT-SoVITS、CosyVoice、F5-TTS 等私有部署节点",
        requiresApiKey = false
    ),
    OFFLINE_SHERPA(
        displayName = "端侧离线模型 (Sherpa-ONNX)",
        description = "纯本地神经网络离线合成，断网可用",
        requiresApiKey = false
    )
}
