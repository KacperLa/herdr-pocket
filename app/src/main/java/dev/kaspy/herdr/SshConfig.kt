package dev.kaspy.herdr

/** Agent/session identity remains stable when its SSH host profile is edited. */
data class AgentWorkspace(val id: String, val label: String, val attachCommand: String, val hostProfileId: String)

data class HostProfile(val id: String, val label: String, val config: SshConfig)

data class SshConfig(val host: String, val port: Int, val user: String, val expectedPin: String?)
