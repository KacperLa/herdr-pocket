package dev.kaspy.herdr

import java.net.IDN
import java.util.Locale

@ConsistentCopyVisibility
data class HostEndpoint private constructor(val host: String, val port: Int) {
    val display: String get() = if (':' in host) "[$host]:$port" else "$host:$port"
    val preferenceKey: String get() = "host-pin:$display"

    companion object {
        fun parse(rawHost: String, rawPort: String): HostEndpoint? {
            val port = rawPort.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val host = rawHost.trim().removeSurrounding("[", "]")
            if (host.isBlank() || host.any { it.isWhitespace() || it.isISOControl() || it in "/[]@" }) return null
            val normalized = runCatching {
                if (':' in host) {
                    require(host.all { it in "0123456789abcdefABCDEF:." })
                    // Colon-bearing numeric input is parsed locally, never via DNS.
                    requireNotNull(java.net.InetAddress.getByName(host).hostAddress).substringBefore('%')
                } else IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
            return HostEndpoint(normalized, port)
        }
    }
}

internal data class HostTrustOffer(
    val endpoint: HostEndpoint,
    val fingerprint: String,
    val generation: Long,
    val previousPin: String? = null,
)

internal class HostTrust(
    private val read: (HostEndpoint) -> String?,
    private val write: (HostEndpoint, String) -> Boolean,
) {
    fun pin(endpoint: HostEndpoint): String? = read(endpoint)

    fun offer(endpoint: HostEndpoint, actual: String, generation: Long): HostTrustOffer? =
        if (pin(endpoint) == null && isSshSha256Pin(actual)) HostTrustOffer(endpoint, actual, generation) else null

    // Only a fresh, explicitly requested no-authentication probe can produce a replacement.
    fun replacementOffer(endpoint: HostEndpoint, actual: String, generation: Long): HostTrustOffer? {
        val previous = pin(endpoint) ?: return null
        return if (actual != previous && isSshSha256Pin(actual))
            HostTrustOffer(endpoint, actual, generation, previous) else null
    }

    fun accept(offer: HostTrustOffer, endpoint: HostEndpoint?, generation: Long): Boolean =
        endpoint == offer.endpoint && generation == offer.generation &&
            isSshSha256Pin(offer.fingerprint) && offer.fingerprint != offer.previousPin &&
            pin(offer.endpoint) == offer.previousPin && write(offer.endpoint, offer.fingerprint)

    fun legacyPinToMigrate(endpoint: HostEndpoint?, value: String?): String? =
        value?.trim()?.takeIf { endpoint != null && pin(endpoint) == null && isSshSha256Pin(it) }

}
