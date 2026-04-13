package com.google.ai.edge.gallery.voicechat

import android.util.Log

/**
 * Tích lũy text streaming từ LLM, cắt thành chunk đủ dài để đưa vào TTS.
 *
 * Quy tắc:
 * - Dấu ngắt câu trung gian (: ;) → chuyển thành , trước khi xử lý
 * - Dấu kết câu (? !) → chuyển thành .
 * - Cắt tại dấu . hoặc ,
 * - Chunk < MIN_WORDS từ → ghép với chunk tiếp theo.
 *   Nếu dấu cuối chunk ngắn là . → đổi thành , rồi mới ghép.
 * - Chunk đủ dài → normalize dấu cuối thành .
 */
class StreamingChunkAccumulator(private val minWords: Int = 7) {

    companion object {
        private const val TAG = "ChunkAccumulator"
    }

    private val buffer = StringBuilder()
    private var chunkCounter = 0

    /**
     * Nạp thêm partial text, trả về danh sách chunk đã sẵn sàng cho TTS.
     * Mỗi chunk đã được normalize dấu cuối.
     */
    fun feed(partial: String): List<String> {
        buffer.append(normalizePunctuation(partial))
        val chunks = extractReadyChunks()
        chunks.forEach { chunk ->
            Log.i(TAG, "✂ Chunk #${chunkCounter++} cắt: \"$chunk\" (${wordCount(chunk)} từ)")
        }
        return chunks
    }

    /**
     * Flush toàn bộ buffer còn lại khi LLM done.
     * Trả về chunk cuối (có thể < MIN_WORDS).
     */
    fun flush(): String? {
        val remaining = buffer.toString().trim()
        buffer.clear()
        if (remaining.isEmpty()) return null
        val result = ensureTerminalDot(remaining)
        Log.i(TAG, "✂ Chunk #${chunkCounter++} flush: \"$result\" (${wordCount(result)} từ)")
        return result
    }

    // ── Private ──────────────────────────────────────────────────────────────

    /**
     * Chuẩn hóa dấu ngắt/kết câu trong partial text đến:
     *  : ;  → ,
     *  ? !  → .
     */
    private fun normalizePunctuation(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                ':', ';'       -> sb.append(',')
                '?', '!'       -> sb.append('.')
                else           -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Quét buffer, tách ra các chunk đã "chốt" tại dấu . hoặc ,.
     * Chunk ngắn (< minWords) được giữ lại trong buffer để ghép tiếp.
     */
    private fun extractReadyChunks(): List<String> {
        val result = mutableListOf<String>()
        var carry = ""          // phần ghép từ chunk ngắn trước

        while (true) {
            val raw = buffer.toString()
            val cutIdx = findNextCut(raw) ?: break

            val chunkRaw = raw.substring(0, cutIdx + 1).trim()
            buffer.delete(0, cutIdx + 1)

            if (chunkRaw.isEmpty()) continue

            val candidate = if (carry.isNotEmpty()) "$carry $chunkRaw" else chunkRaw

            if (wordCount(candidate) < minWords) {
                // Chunk ngắn → đổi dấu cuối . thành , rồi giữ làm carry
                val merged = replaceTerminalDotWithComma(candidate)
                Log.d(TAG, "   ↩ Chunk ngắn (${wordCount(candidate)} từ < $minWords), ghép tiếp: \"$merged\"")
                carry = merged
            } else {
                // Chunk đủ dài → normalize dấu cuối thành .
                result.add(ensureTerminalDot(carry.let {
                    if (it.isNotEmpty()) "$it $chunkRaw" else chunkRaw
                }))
                carry = ""
            }
        }

        // Nếu còn carry mà buffer không còn dấu cắt → giữ carry trong buffer
        if (carry.isNotEmpty()) {
            buffer.insert(0, "$carry ")
        }

        // Nếu buffer còn lại (chưa có dấu cắt) < minWords từ
        // VÀ đã có ít nhất 1 chunk sẵn sàng → ghép luôn vào chunk cuối, clear buffer.
        val tail = buffer.toString().trim()
        if (tail.isNotEmpty() && wordCount(tail) < minWords && result.isNotEmpty()) {
            val lastChunk = result.removeLast()
            // Đổi dấu . cuối chunk trước thành , để nối mượt
            val lastWithComma = replaceTerminalDotWithComma(lastChunk)
            val merged = ensureTerminalDot("$lastWithComma $tail")
            Log.d(TAG, "   ⤵ Tail ngắn (${wordCount(tail)} từ < $minWords), ghép vào chunk trước: \"$merged\"")
            result.add(merged)
            buffer.clear()
        }

        return result
    }

    /**
     * Tìm vị trí dấu . hoặc , đầu tiên trong chuỗi.
     * Bỏ qua dấu . hoặc , nằm giữa chữ số (ví dụ: 1,000 hoặc 3.14).
     */
    private fun findNextCut(text: String): Int? {
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '.' || ch == ',') {
                val prevIsDigit = i > 0 && text[i - 1].isDigit()
                val nextIsDigit = i < text.length - 1 && text[i + 1].isDigit()
                if (prevIsDigit && nextIsDigit) continue   // số thập phân hoặc phân cách nghìn
                return i
            }
        }
        return null
    }

    /** Đếm số từ, bỏ qua dấu chấm và dấu phẩy dính vào từ. */
    private fun wordCount(text: String): Int =
        text.trim().split(Regex("\\s+"))
            .count { it.replace(Regex("[.,]+"), "").isNotEmpty() }

    /**
     * Nếu dấu cuối cùng là . → đổi thành ,.
     * Dùng khi chunk ngắn cần ghép tiếp.
     */
    private fun replaceTerminalDotWithComma(text: String): String {
        val t = text.trimEnd()
        return if (t.endsWith('.')) t.dropLast(1) + ',' else t
    }

    /**
     * Đảm bảo chunk kết thúc bằng dấu chấm.
     * Nếu đã có dấu câu cuối (. ,) thì thay bằng ., không thì append .
     */
    private fun ensureTerminalDot(text: String): String {
        val t = text.trimEnd()
        if (t.isEmpty()) return t
        return when (t.last()) {
            '.', ',' -> t.dropLast(1) + '.'
            else     -> "$t."
        }
    }
}
