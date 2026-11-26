package com.aitts.v2.engine.text

/**
 * Normalizes numbers, phone digits, times, currency, percentages, years, and ordinals into natural Chinese speech.
 * Fixes v1 bug where 100_000_000 produced "一亿万".
 */
object ChineseNumberNormalizer {

    private val digits = charArrayOf('零', '一', '二', '三', '四', '五', '六', '七', '八', '九')
    private val phoneDigits = charArrayOf('零', '幺', '二', '三', '四', '五', '六', '七', '八', '九')
    private val smallUnits = arrayOf("", "十", "百", "千")
    private val bigUnits = arrayOf("", "万", "亿", "万亿")

    private val phoneRegex = Regex("(?<!\\d)(1[3-9]\\d{9})(?!\\d)")
    private val ordinalRegex = Regex("第(\\d{1,12})([章节卷回集页名次天年条步把位段])")
    private val timeRegex = Regex("(?<!\\d)([0-2]?\\d):([0-5]\\d)(?!\\d)")
    private val currencyRegex = Regex("[¥￥](\\d+(?:\\.\\d+)?)")
    private val yearRegex = Regex("(?<!\\d)(\\d{4})年")
    private val durationYearRegex = Regex("(?<!\\d)(\\d{1,3}|\\d{5,12})年")
    private val percentRegex = Regex("(\\d+(?:\\.\\d+)?)%")

    fun normalize(input: String): String {
        var res = input
        res = phoneRegex.replace(res) { m ->
            m.groupValues[1].map { phoneDigits[it - '0'] }.joinToString("")
        }
        res = ordinalRegex.replace(res) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            "第${intToChinese(n)}${m.groupValues[2]}"
        }
        res = timeRegex.replace(res) { m ->
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val minText = if (min == 0) "整" else "${intToChinese(min.toLong())}分"
            "${intToChinese(h.toLong())}点$minText"
        }
        res = currencyRegex.replace(res) { m -> formatDecimal(m.groupValues[1]) + "元" }
        res = yearRegex.replace(res) { m ->
            m.groupValues[1].map { digits[it - '0'] }.joinToString("") + "年"
        }
        res = durationYearRegex.replace(res) { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@replace m.value
            "${intToChinese(n)}年"
        }
        res = percentRegex.replace(res) { m -> "百分之${formatDecimal(m.groupValues[1])}" }
        return res
    }

    private fun formatDecimal(numStr: String): String {
        if (!numStr.contains(".")) {
            val n = numStr.toLongOrNull() ?: return numStr
            return intToChinese(n)
        }
        val parts = numStr.split(".", limit = 2)
        val intPart = intToChinese(parts[0].toLongOrNull() ?: 0L)
        val decPart = parts[1].map { digits[it - '0'] }.joinToString("")
        return "${intPart}点$decPart"
    }

    /**
     * Converts integer into natural Chinese numerals by 4-digit sections (万 / 亿 / 万亿).
     */
    fun intToChinese(number: Long): String {
        if (number == 0L) return "零"
        if (number < 0L) return "负" + intToChinese(-number)

        var rem = number
        val sections = mutableListOf<Int>()
        while (rem > 0L) {
            sections.add((rem % 10_000L).toInt())
            rem /= 10_000L
        }

        val sb = StringBuilder()
        var needZeroBridge = false
        for (secIdx in sections.indices.reversed()) {
            val sec = sections[secIdx]
            if (sec == 0) {
                if (sb.isNotEmpty()) needZeroBridge = true
                continue
            }
            if (needZeroBridge || (sb.isNotEmpty() && sec < 1000)) {
                if (!sb.endsWith('零')) sb.append('零')
            }
            needZeroBridge = false
            val omitLeadingOneInTen = sb.isEmpty() && sec in 10..19
            sb.append(formatSection(sec, omitLeadingOneInTen))
            if (secIdx > 0 && secIdx < bigUnits.size) {
                sb.append(bigUnits[secIdx])
            }
        }
        return sb.toString()
    }

    private fun formatSection(sec: Int, omitLeadingOneInTen: Boolean): String {
        val sb = StringBuilder()
        var temp = sec
        val sectionDigits = IntArray(4)
        for (i in 3 downTo 0) {
            sectionDigits[i] = temp % 10
            temp /= 10
        }
        var zeroPending = false
        var started = false
        for (i in 0..3) {
            val d = sectionDigits[i]
            val unitIdx = 3 - i
            if (d == 0) {
                if (started) zeroPending = true
            } else {
                if (zeroPending) {
                    sb.append('零')
                    zeroPending = false
                }
                val skipOne = omitLeadingOneInTen && unitIdx == 1 && d == 1
                if (!skipOne || started) {
                    sb.append(digits[d])
                }
                sb.append(smallUnits[unitIdx])
                started = true
            }
        }
        return sb.toString()
    }
}
