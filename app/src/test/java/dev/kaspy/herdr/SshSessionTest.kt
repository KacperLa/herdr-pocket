package dev.kaspy.herdr

import com.jcraft.jsch.HostKeyRepository
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test

class SshSessionTest {
    private val keyBlob = "abc".toByteArray()
    private val pin = "SHA256:ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0"

    @Test fun opensshStyleSha256UsesWireBytesAndNoPadding() {
        assertEquals(pin, sshSha256Fingerprint(keyBlob))
        assertTrue(isSshSha256Pin(pin))
        assertFalse(isSshSha256Pin(pin + "="))
        assertFalse(isSshSha256Pin(pin.dropLast(1) + "1")) // non-canonical trailing bits
        assertFalse(isSshSha256Pin("MD5:" + pin.substring(7)))
    }

    @Test fun noPinAndMismatchedPinAreNotAccepted() {
        val unknown = PinnedHostKey(null)
        assertEquals(HostKeyRepository.NOT_INCLUDED, unknown.check("example", keyBlob))
        assertFalse(unknown.accepted)
        assertEquals(pin, unknown.rejected)

        val mismatch = PinnedHostKey(sshSha256Fingerprint("different".toByteArray()))
        assertEquals(HostKeyRepository.CHANGED, mismatch.check("example", keyBlob))
        assertFalse(mismatch.accepted)
        assertEquals(pin, mismatch.rejected)
    }

    @Test fun exactPinIsAcceptedButNeverSaved() {
        val repo = PinnedHostKey(pin)
        assertEquals(HostKeyRepository.OK, repo.check("example", keyBlob))
        assertTrue(repo.accepted)
        assertNull(repo.rejected)
        assertTrue(repo.getHostKey().isEmpty())
    }

    @Test fun disconnectAbortsStalledHandshakeAndInvalidatesItsGeneration() {
        val events = CopyOnWriteArrayList<SshSession.Event>()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 4_000
            SshSession { _, event -> events.add(event) }.use { ssh ->
                val config = SshConfig(server.inetAddress.hostAddress!!, server.localPort, "test-user", null)
                val first = checkNotNull(ssh.connect(config, byteArrayOf(), null, "exit"))
                server.accept().use { socket ->
                    socket.soTimeout = 4_000
                    val input = socket.getInputStream().bufferedReader()
                    assertTrue(input.readLine().startsWith("SSH-2.0-"))
                    assertNull(ssh.connect(config, byteArrayOf(), null, "exit"))
                    ssh.disconnect()
                    assertFalse(ssh.isCurrent(first))
                    assertNull(input.readLine())
                }
                val second = checkNotNull(ssh.connect(config, byteArrayOf(), null, "exit"))
                assertNotEquals(first, second)
                server.accept().use { socket ->
                    socket.soTimeout = 4_000
                    val input = socket.getInputStream().bufferedReader()
                    assertTrue(input.readLine().startsWith("SSH-2.0-"))
                    ssh.disconnect()
                    assertNull(input.readLine())
                }
                assertTrue(events.isEmpty())
            }
        }
    }
}
