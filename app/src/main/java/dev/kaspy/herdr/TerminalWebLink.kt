package dev.kaspy.herdr

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** A browser-only destination. Keep the authored path/query/fragment; never interpret Intent URIs. */
internal class TerminalWebLink private constructor(val url: String, val scheme: String) {
    companion object {
        fun parse(raw: String): TerminalWebLink? {
            if (raw.length !in 8..4096 || raw.any { it !in '!'..'~' || it == '\\' }) return null
            val parsed = try { URI(raw) } catch (_: URISyntaxException) { return null }
            val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return null
            val host = parsed.host ?: return null
            val authority = parsed.rawAuthority ?: return null
            val port = parsed.port
            if (scheme !in setOf("http", "https") || parsed.isOpaque || parsed.rawUserInfo != null ||
                host.isEmpty() || '%' in authority || port !in -1..65535 || port == 0 ||
                authority != if (port == -1) host else "$host:$port"
            ) return null
            // Don't pass encoded control characters to other applications, even as a URL.
            if (ENCODED_BYTE.findAll(raw).any {
                    val byte = it.groupValues[1].toInt(16)
                    byte < 32 || byte == 127
                }
            ) return null
            return TerminalWebLink(scheme + raw.substring(parsed.scheme.length), scheme)
        }
        private val ENCODED_BYTE = Regex("%([A-Fa-f0-9]{2})")
    }
}
