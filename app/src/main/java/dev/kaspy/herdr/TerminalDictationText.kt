package dev.kaspy.herdr

import org.connectbot.terminal.MAX_ENCODED_USER_LINE_CHARS

/** Sanitize one final speech candidate; Enter is encoded separately by the terminal. */
internal fun terminalDictationText(result: String?): String? {
    if (result.isNullOrEmpty() || result.length > MAX_ENCODED_USER_LINE_CHARS) return null
    val line = StringBuilder(result.length)
    var pendingSpace = false
    var offset = 0
    while (offset < result.length) {
        val cp = result.codePointAt(offset)
        offset += Character.charCount(cp)
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85) {
            pendingSpace = line.isNotEmpty()
            continue
        }
        if (Character.isISOControl(cp) ||
            Character.getType(cp) == Character.SURROGATE.toInt() ||
            (Character.getType(cp) == Character.FORMAT.toInt() && cp != 0x200C && cp != 0x200D)
        ) continue
        if (pendingSpace) line.append(' ')
        pendingSpace = false
        line.appendCodePoint(cp)
    }
    if (line.isEmpty() || line.all { it.isWhitespace() || it == '\u200C' || it == '\u200D' }) return null
    return line.toString()
}

/** A listener owns one ticket. Even an invalid/empty final callback consumes it. */
internal class TerminalDictationTicket(val terminal: Any, permissionGranted: Boolean = true) {
    private var granted = permissionGranted
    private var started = false
    private var finished = false
    private var hasText = false

    fun permissionResult(granted: Boolean, currentTerminal: Any?): DictationPermissionResult {
        if (finished || started || currentTerminal !== terminal) return DictationPermissionResult.STALE
        this.granted = granted
        return if (granted) DictationPermissionResult.READY else DictationPermissionResult.DENIED
    }

    fun claimStart(currentTerminal: Any?, resumed: Boolean): Boolean {
        if (!granted || !resumed || started || finished || currentTerminal !== terminal) return false
        started = true
        return true
    }

    fun cancel() {
        finished = true
        hasText = false
    }

    fun finish(currentTerminal: Any?, results: List<String>?): String? {
        if (finished) return null
        finished = true
        val text = if (currentTerminal === terminal) results?.asSequence()?.take(10)
            ?.mapNotNull(::terminalDictationText)?.firstOrNull() else null
        hasText = text != null
        return text
    }

    fun allowsDelivery(currentTerminal: Any?, resumed: Boolean): Boolean =
        hasText && resumed && currentTerminal === terminal
}

internal enum class DictationPermissionResult { READY, DENIED, STALE }
internal enum class DictationModel { INSTALLED, PENDING, NEEDS_DOWNLOAD, UNKNOWN }

/** Never infer an unsupported language merely because a provider returns incomplete metadata. */
internal fun onDeviceSpeechModel(
    languageTag: String,
    installed: List<String>,
    pending: List<String>,
    supportedForDownload: List<String>,
): DictationModel {
    fun List<String>.matches() = any { it.replace('_', '-').equals(languageTag, ignoreCase = true) }
    return when {
        installed.matches() -> DictationModel.INSTALLED
        pending.matches() -> DictationModel.PENDING
        supportedForDownload.matches() -> DictationModel.NEEDS_DOWNLOAD
        else -> DictationModel.UNKNOWN
    }
}

/** Segments are retained in order, but never delivered until a complete final callback. */
internal class RecordedDictationSegments {
    private val line = StringBuilder()
    private var overflow = false
    var hasSegments = false
        private set

    fun add(candidates: List<String>?) {
        hasSegments = true
        if (overflow) return
        val candidate = candidates?.asSequence()?.take(10)?.firstOrNull { it.isNotBlank() } ?: return
        if (candidate.length > MAX_ENCODED_USER_LINE_CHARS) {
            overflow = true
            line.clear()
            return
        }
        val cleaned = terminalDictationText(candidate) ?: return
        val separator = if (line.isEmpty()) 0 else 1
        if (line.length + separator + cleaned.length > MAX_ENCODED_USER_LINE_CHARS) {
            overflow = true
            line.clear()
            return
        }
        if (separator != 0) line.append(' ')
        line.append(cleaned)
    }

    fun result(): String? = if (!overflow && line.isNotEmpty()) line.toString() else null
}
