package com.google.ai.edge.gallery.voicechat

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Pre-processor văn bản cho Piper TTS.
 * Pipeline: cleanSymbols → normalizeNumbers → applyMisaProducts
 */
class PiperTextPreProcessor(context: Context) {

    companion object {
        private const val TAG = "PiperPreProcessor"
        private const val ASSET_FILE = "misa_product.json"

        private val DIGITS = arrayOf(
            "không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"
        )

        private val PHONE_PATTERN       = Regex("""(?<!\d)0\d{9}(?!\d)""")
        private val DATE_FULL_PATTERN   = Regex("""(?<!\d)(\d{1,2})/(\d{1,2})/(\d{4})(?!\d)""")
        private val DATE_SHORT_PATTERN  = Regex("""(?<!\d)(\d{1,2})/(\d{1,2})(?!/\d)(?!\d)""")
        private val NUMBER_PATTERN      = Regex("""\d+""")
        private val ELLIPSIS_PATTERN    = Regex("""[…]|\.\.\.|[?!]+""")
        private val NEWLINE_PATTERN     = Regex("""[ \t]*[\r\n]+[ \t]*""")
        private val BULLET_PATTERN      = Regex("""(?<=\.)\s*-\s*""")
        private val COLON_PATTERN       = Regex(""":""")
        private val SEPARATOR_PATTERN   = Regex("""[;/\\|]""")
        private val UNWANTED_PATTERN    = Regex("""[^\p{L}\p{N}\s,.\-]""")
        private val MULTI_COMMA         = Regex(""",+""")
        private val MULTI_DOT           = Regex("""\.+""")
        private val MULTI_SPACE         = Regex("""\s+""")
    }

    private val productReplacements: List<Pair<String, String>>

    init {
        val merged = mutableMapOf<String, String>()
        try {
            val json = JSONObject(context.assets.open(ASSET_FILE).bufferedReader().readText())
            val rawMap = mutableMapOf<String, String>()
            json.keys().forEach { cat ->
                val obj = json.getJSONObject(cat)
                obj.keys().forEach { key ->
                    val lk = key.lowercase().trim()
                    if (!rawMap.containsKey(lk)) rawMap[lk] = obj.getString(key).trim()
                }
            }
            rawMap.forEach { (lk, raw) ->
                val keyWords = lk.split(" ").filter { it.isNotEmpty() }
                val valueTokens = raw.split(" ").filter { it.isNotEmpty() }
                val hyphenated = if (keyWords.size <= 1) {
                    valueTokens.joinToString("-")
                } else {
                    val groups = mutableListOf<String>()
                    var idx = 0
                    for ((i, word) in keyWords.withIndex()) {
                        val syllableCount = if (rawMap.containsKey(word)) {
                            rawMap[word]!!.split(" ").filter { it.isNotEmpty() }.size
                        } else {
                            val remaining = valueTokens.size - idx
                            val wordsLeft = keyWords.size - i
                            remaining / wordsLeft
                        }
                        val end = (idx + syllableCount).coerceAtMost(valueTokens.size)
                        groups.add(valueTokens.subList(idx, end).joinToString("-"))
                        idx = end
                    }
                    if (idx < valueTokens.size) {
                        val last = groups.removeLast()
                        groups.add(last + "-" + valueTokens.subList(idx, valueTokens.size).joinToString("-"))
                    }
                    groups.joinToString(" ")
                }
                merged[lk] = hyphenated
            }
            Log.i(TAG, "Loaded ${merged.size} product replacements")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $ASSET_FILE (optional)", e)
        }
        productReplacements = merged.entries
            .sortedByDescending { it.key.length }
            .map { it.key to it.value }
    }

    fun process(text: String): String {
        var s = text
        s = cleanSymbols(s)
        s = normalizeNumbers(s)
        s = applyMisaProducts(s)
        return s
    }

    private fun cleanSymbols(text: String): String {
        var s = text
        s = ELLIPSIS_PATTERN.replace(s, ".")
        s = NEWLINE_PATTERN.replace(s, ". ")
        s = BULLET_PATTERN.replace(s, " ")
        s = COLON_PATTERN.replace(s, ".")
        s = SEPARATOR_PATTERN.replace(s, ",")
        s = UNWANTED_PATTERN.replace(s, " ")
        s = MULTI_COMMA.replace(s, ",")
        s = MULTI_DOT.replace(s, ".")
        s = MULTI_SPACE.replace(s, " ").trim()
        return s
    }

    private fun normalizeNumbers(text: String): String {
        var s = text
        s = PHONE_PATTERN.replace(s) { readPhoneNumber(it.value) }
        s = DATE_FULL_PATTERN.replace(s) { m ->
            val d = m.groupValues[1].toIntOrNull() ?: 0
            val mo = m.groupValues[2].toIntOrNull() ?: 0
            val y = m.groupValues[3].toLongOrNull() ?: 0L
            "ngày ${readNumber(d.toLong())} tháng ${readNumber(mo.toLong())} năm ${readNumber(y)}"
        }
        s = DATE_SHORT_PATTERN.replace(s) { m ->
            val d = m.groupValues[1].toIntOrNull() ?: 0
            val mo = m.groupValues[2].toIntOrNull() ?: 0
            "ngày ${readNumber(d.toLong())} tháng ${readNumber(mo.toLong())}"
        }
        s = NUMBER_PATTERN.replace(s) { m ->
            try {
                if (m.value.length > 9) m.value.map { DIGITS[it.toString().toInt()] }.joinToString(" ")
                else readNumber(m.value.toLong())
            } catch (_: Exception) { m.value }
        }
        return s
    }

    private fun readPhoneNumber(phone: String): String =
        phone.chunked(2).joinToString(" ") { pair ->
            pair.map { DIGITS[it.toString().toInt()] }.joinToString(" ")
        }

    private fun applyMisaProducts(text: String): String {
        var result = text.lowercase()
        for ((key, value) in productReplacements) {
            result = Regex("\\b${Regex.escape(key)}\\b", RegexOption.IGNORE_CASE).replace(result, value)
        }
        return result
    }

    private fun readNumber(n: Long): String {
        if (n == 0L) return DIGITS[0]
        var rem = n
        val sb = StringBuilder()
        listOf(1_000_000_000L to "tỷ", 1_000_000L to "triệu", 1_000L to "nghìn").forEach { (div, unit) ->
            val q = rem / div
            if (q > 0) { sb.append(readTriple(q.toInt())).append(" $unit "); rem %= div }
        }
        if (rem > 0) sb.append(readTriple(rem.toInt()))
        return sb.toString().trim()
    }

    private fun readTriple(num: Int): String {
        val h = num / 100; val t = (num % 100) / 10; val u = num % 10
        val sb = StringBuilder()
        if (h > 0) { sb.append(DIGITS[h]).append(" trăm "); if (t == 0 && u > 0) sb.append("linh ") }
        when {
            t > 1 -> { sb.append(DIGITS[t]).append(" mươi "); when { u == 1 -> sb.append("mốt "); u == 5 -> sb.append("lăm "); u > 0 -> sb.append(DIGITS[u]).append(" ") } }
            t == 1 -> { sb.append("mười "); when { u == 1 -> sb.append("một "); u == 5 -> sb.append("lăm "); u > 0 -> sb.append(DIGITS[u]).append(" ") } }
            t == 0 && u > 0 -> sb.append(DIGITS[u]).append(" ")
        }
        return sb.toString().trimEnd()
    }
}
