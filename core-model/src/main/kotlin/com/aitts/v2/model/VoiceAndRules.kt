package com.aitts.v2.model

import kotlinx.serialization.Serializable

@Serializable
data class VoiceModel(
    val id: String,
    val name: String,
    val gender: String = "Female",
    val locale: String = "zh-CN",
    val description: String = ""
)

@Serializable
enum class RuleCategory(val label: String) {
    COMMON("通用规则"),
    POLYPHONE("多音字纠错"),
    CLEANUP("符号净化"),
    WATERMARK("防盗去水印"),
    SPECIAL("专有名词"),
    CUSTOM("自定义")
}

@Serializable
data class ReplacementRule(
    val id: String,
    val pattern: String,
    val replacement: String,
    val isRegex: Boolean = true,
    val isCaseSensitive: Boolean = false,
    val enabled: Boolean = true,
    val description: String = "",
    val category: RuleCategory = RuleCategory.COMMON
)
