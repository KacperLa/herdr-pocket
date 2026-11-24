package dev.kaspy.herdr

internal data class StartupReconnectProfile(val host: String, val port: Int, val user: String)

/**
 * One cold-start discovery opportunity per model. Claim only after restoring the
 * persisted profile, URI grant and the pin scoped to that exact host and port.
 * No credentials or connection state are owned here.
 */
internal class StartupReconnect {
    private var available = true

    /** Manual actions win if startup has not yet claimed its opportunity. */
    @Synchronized
    fun cancelAutomatic() {
        available = false
    }

    @Synchronized
    fun claim(
        profile: StartupReconnectProfile?,
        rememberedKeyReadable: Boolean,
        pinnedHostKey: String?,
    ): StartupReconnectProfile? {
        if (!available) return null
        available = false
        if (profile == null || !rememberedKeyReadable || pinnedHostKey == null ||
            !isSshSha256Pin(pinnedHostKey)
        ) return null
        val endpoint = HostEndpoint.parse(profile.host, profile.port.toString()) ?: return null
        val user = profile.user.trim().takeIf { it.isNotEmpty() && it.none(Char::isISOControl) } ?: return null
        return StartupReconnectProfile(endpoint.host, endpoint.port, user)
    }
}

/** A missing or ambiguous remembered session must not redirect startup to another agent. */
internal fun startupSession(sessions: List<CoreSession>, rememberedAddress: String?): CoreSession? =
    if (rememberedAddress == null) CoreSessions.autoAttach(sessions, null)
    else sessions.singleOrNull { it.state == "live" && it.address == rememberedAddress }
