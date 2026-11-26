package com.aitts.v2.engine.text

import com.aitts.v2.model.ReplacementRule
import java.util.concurrent.ConcurrentHashMap

/**
 * Stage 1 & 2 of the text pipeline:
 * 1. Clean invisible controls, HTML tags/entities, Markdown emphasis, repeated punctuation (preserving quotes & URLs).
 * 2. Apply user replacement rules in exact priority order with thread-safe regex compilation cache.
 */
object TextCleanerAndRules {

    private const val MAX_REGEX_CACHE = 256
    private val regexCache = ConcurrentHashMap<String, Regex>()

    private val controlCharsRegex = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u200B-\\u200F\\uFEFF\\u00A0]")
    private val htmlTagRegex = Regex("<[^>]+>")
    private val mdBoldRegex = Regex("\\*\\*(.*?)\\*\\*")
    private val mdUnderlineRegex = Regex("__(.*?)__")
    private val repeatQuestionRegex = Regex("？{2,}")
    private val repeatExclaimRegex = Regex("！{2,}")
    private val repeatCommaRegex = Regex("，{2,}")
    private val multiSpacesRegex = Regex("[ \\t]+")

    fun cleanMarkup(input: String): String {
        if (input.isBlank()) return ""
        var res = input
        if (res.contains("<") && res.contains(">")) {
            res = htmlTagRegex.replace(res, " ")
        }
        if (res.contains("&")) {
            res = res.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
        }
        if (res.contains("**") || res.contains("__")) {
            res = mdBoldRegex.replace(res, "$1")
            res = mdUnderlineRegex.replace(res, "$1")
        }
        res = controlCharsRegex.replace(res, " ")
        res = repeatQuestionRegex.replace(res, "？")
        res = repeatExclaimRegex.replace(res, "！")
        res = repeatCommaRegex.replace(res, "，")
        return res
    }

    fun applyRules(input: String, rules: List<ReplacementRule>): String {
        var result = input
        for (rule in rules) {
            if (!rule.enabled || rule.pattern.isBlank()) continue
            result = applySingleRule(result, rule)
        }
        return result
    }

    private fun applySingleRule(input: String, rule: ReplacementRule): String {
        return runCatching {
            if (rule.isRegex) {
                val key = "${rule.isCaseSensitive}:${rule.pattern}"
                if (regexCache.size >= MAX_REGEX_CACHE) {
                    regexCache.clear()
                }
                val regex = regexCache.getOrPut(key) {
                    val opts = if (rule.isCaseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                    Regex(rule.pattern, opts)
                }
                regex.replace(input, rule.replacement)
            } else {
                input.replace(rule.pattern, rule.replacement, ignoreCase = !rule.isCaseSensitive)
            }
        }.getOrDefault(input)
    }

    fun normalizeWhitespace(input: String): String {
        return multiSpacesRegex.replace(input, " ").trim()
    }
}
