package dev.kaspy.herdr

/** Preserve plain text, but never forward embedded terminal control sequences or a partial paste. */
internal fun terminalClipboardText(value: CharSequence?): String? {
    // A single SSH input packet is limited to 64 KiB; allow room for native paste markers.
    val payloadLimit = 64 * 1024 - 12
    if (value == null || value.isEmpty() || value.length > payloadLimit) return null
    val text = value.toString()
    if (text.any { Character.isISOControl(it) && it != '\t' && it != '\r' && it != '\n' }) return null
    return text.takeIf { it.toByteArray(Charsets.UTF_8).size <= payloadLimit }
}
