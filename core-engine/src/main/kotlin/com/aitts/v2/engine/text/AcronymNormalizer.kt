package com.aitts.v2.engine.text

import java.util.regex.Pattern

/**
 * Normalizes common tech & gaming acronyms for natural Chinese/English mixed TTS pronunciation.
 */
object AcronymNormalizer {

    private val explicitMap = mapOf(
        "GPT-4o" to "G-P-T-四-o",
        "GPT-4" to "G-P-T-四",
        "GPT-3.5" to "G-P-T-三点五",
        "ChatGPT" to "Chat-G-P-T",
        "USB-C" to "U-S-B-C",
        "1080P" to "一零八零P",
        "1080p" to "一零八零p",
        "720P" to "七二零P",
        "WiFi 6" to "W-i-F-i-六",
        "IPv4" to "I-P-v-四",
        "IPv6" to "I-P-v-六",
        "JSON" to "J-S-O-N",
        "SDK" to "S-D-K",
        "AI" to "A-I",
        "APP" to "A-P-P",
        "App" to "A-P-P",
        "WiFi" to "W-i-F-i",
        "wifi" to "W-i-F-i",
        "CPU" to "C-P-U",
        "GPU" to "G-P-U",
        "NPU" to "N-P-U",
        "USB" to "U-S-B",
        "PDF" to "P-D-F",
        "GPS" to "G-P-S",
        "VIP" to "V-I-P",
        "API" to "A-P-I",
        "TTS" to "T-T-S",
        "LLM" to "L-L-M",
        "NPC" to "N-P-C",
        "BUG" to "B-U-G",
        "Bug" to "B-U-G",
        "BOSS" to "B-O-S-S",
        "Boss" to "B-O-S-S",
        "HTTP" to "H-T-T-P",
        "HTTPS" to "H-T-T-P-S",
        "iOS" to "i-O-S",
        "3D" to "三D",
        "4K" to "四K",
        "5G" to "五G"
    )

    private val compiledRules = explicitMap.map { (k, v) ->
        Pattern.compile("(?<![a-zA-Z])${Pattern.quote(k)}(?![a-zA-Z])") to v
    }
    private val generalAcronymPattern = Pattern.compile("(?<![a-zA-Z])([A-Z]{2,5})(?![a-zA-Z])")

    fun normalize(text: String): String {
        if (text.isBlank()) return text
        var result = text
        for ((pattern, replacement) in compiledRules) {
            val matcher = pattern.matcher(result)
            if (matcher.find()) {
                result = matcher.replaceAll(replacement)
            }
        }
        val matcher = generalAcronymPattern.matcher(result)
        val sb = StringBuffer()
        while (matcher.find()) {
            val acr = matcher.group(1) ?: ""
            val spaced = if (!acr.contains("-")) acr.toCharArray().joinToString("-") else acr
            matcher.appendReplacement(sb, spaced)
        }
        matcher.appendTail(sb)
        return sb.toString()
    }
}
