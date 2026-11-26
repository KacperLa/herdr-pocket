package dev.kaspy.herdr

/** Stored replies remain whole. Provider-specific transport limits are applied at submission, never by silent clipping. */
internal object LiveReadback {
    const val FOSS_APPEND_SCALARS = 2_000
    const val FOSS_APPEND_UTF8 = 8_000
    const val FOSS_TOTAL_UTF8 = 16_000

    fun validText(text: String): Boolean =
        text.isNotBlank() && text.codePoints().noneMatch {
            it in 0xD800..0xDFFF || (Character.isISOControl(it) && it != 10 && it != 9)
        }

    fun validFossAppend(text: String): Boolean =
        validText(text) && text.codePointCount(0, text.length) <= FOSS_APPEND_SCALARS &&
            text.toByteArray(Charsets.UTF_8).size <= FOSS_APPEND_UTF8

    fun plan(profile: LiveVoiceProfile, text: String): List<String>? {
        if (!validText(text)) return null
        if (profile != LiveVoiceProfile.FOSS_LIVE) {
            // The provisioned endpoint owns its 500-token enforcement. Client-side
            // token guesses with an unrelated tokenizer are not authoritative.
            // This bound is only a necessary WebSocket frame constraint.
            return listOf(text).takeIf { text.toByteArray(Charsets.UTF_8).size < LiveVoiceProtocol.MAX_MESSAGE }
        }
        val total = text.toByteArray(Charsets.UTF_8).size
        if (total > FOSS_TOTAL_UTF8) return null
        if (validFossAppend(text)) return listOf(text)

        fun split(onWhitespace: Boolean): List<String>? {
            val parts = ArrayList<String>()
            var start = 0
            while (start < text.length) {
                var end = start
                var scalars = 0
                var bytes = 0
                var gap = start
                var safeEnd = start
                var fenced = false
                while (end < text.length && scalars < FOSS_APPEND_SCALARS) {
                    if (text.startsWith("```",end)) {
                        if (scalars + 3 > FOSS_APPEND_SCALARS || bytes + 3 > FOSS_APPEND_UTF8) break
                        end += 3
                        scalars += 3
                        bytes += 3
                        fenced = !fenced
                        if (!fenced) safeEnd = end
                        continue
                    }
                    val cp = text.codePointAt(end)
                    val size = when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
                    if (bytes + size > FOSS_APPEND_UTF8) break
                    bytes += size
                    scalars++
                    end += Character.charCount(cp)
                    if (!fenced) {
                        safeEnd = end
                        if (Character.isWhitespace(cp)) gap = end
                    }
                }
                // Each FOSS append strips its own Markdown. Never cut through a
                // fence, or the next frame could speak what the server normally omits.
                if (end < text.length) {
                    end = safeEnd
                    if (onWhitespace && gap > start && gap >= start+(end-start)/2) end = gap
                }
                if (end == start) return null
                val part = text.substring(start, end)
                if (!validFossAppend(part)) return null
                parts.add(part)
                start = end
            }
            // FOSS-Live inserts a newline into the delegation result after each
            // append; those separators count against later appends.
            return parts.takeIf { total + (parts.size - 1) <= FOSS_TOTAL_UTF8 }
        }
        return split(onWhitespace = true) ?: split(onWhitespace = false)
    }

    enum class Delivery { ACCEPTED, REJECTED, UNCONFIRMED, INTERRUPTED }
    data class Outcome(val delivery: Delivery, val accepted: Int, val total: Int)

    suspend fun submit(
        parts: List<String>, stillCurrent: suspend () -> Boolean,
        append: suspend (String) -> Delivery,
    ): Outcome {
        for ((index, part) in parts.withIndex()) {
            if (!stillCurrent()) return Outcome(Delivery.INTERRUPTED, index, parts.size)
            val outcome = append(part)
            if (outcome != Delivery.ACCEPTED) return Outcome(outcome, index, parts.size)
        }
        return Outcome(Delivery.ACCEPTED, parts.size, parts.size)
    }
}
