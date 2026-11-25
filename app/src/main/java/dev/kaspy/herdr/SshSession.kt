package dev.kaspy.herdr

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * One SSH transport and active PTY, plus at most one listing or pending replacement. All events run on a worker; consumers
 * dispatch to their UI thread and call [isCurrent] there before applying the event.
 */
class SshSession(private val onEvent: (Long, Event) -> Unit) : AutoCloseable {
    sealed interface Event {
        data object Connected : Event
        data object TransportReady : Event
        data class Output(val bytes: ByteArray) : Event
        data class ErrorOutput(val bytes: ByteArray) : Event
        data class RejectedHostKey(val actual: String, val expected: String?) : Event
        data class Closed(val exitStatus: Int?) : Event
        /** An established PTY lost its SSH transport before an exit status arrived. */
        data object TransportLost : Event
        data class Failed(val message: String) : Event
        data class OperationList(val id: Long, val stdout: ByteArray, val stderr: ByteArray, val exitStatus: Int?) : Event
        data class OperationSwitched(val id: Long) : Event
        data class OperationFailed(val id: Long, val message: String) : Event
    }

    private data class Size(val columns: Int, val rows: Int, val px: Int, val py: Int)

    private class Operation(val id: Long, val switchSize: Size?, val tui:Boolean) {
        @Volatile var canceled = false
        @Volatile var overflow = false
        private val tuiFrame = if (tui) TuiAttachFrame() else null
        @Volatile var frameReady = false
            private set
        fun observeOutput(bytes: ByteArray, offset: Int, count: Int) {
            if (!frameReady && tuiFrame?.observe(bytes,offset,count)==true) frameReady = true
        }
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val pending = mutableListOf<Event>()
        var pendingBytes = 0
    }

    private class Attempt(val generation: Long, val initialSize: Size, @Volatile var pty: Boolean, val transportOnly: Boolean) {
        @Volatile var socket: Socket? = null
        @Volatile var channel: ChannelExec? = null
        @Volatile var session: com.jcraft.jsch.Session? = null
        val outputOrder = Any()
        var operation: Operation? = null  // guarded by the transport lock
        var ending = false               // guarded by the transport lock
        @Volatile var input: OutputStream? = null
        @Volatile var desiredSize: Size = initialSize
        @Volatile var setupComplete = false
        @Volatile var timedOut = false
        @Volatile var commandTimedOut = false
        @Volatile var outputExceeded = false
        @Volatile var transportReadFailed = false
        // The JSch reader is the only writer of these counts.
        var stdoutBytes = 0
        var stderrBytes = 0
    }

    private val lock = Any()
    private val reader = Executors.newSingleThreadExecutor { r -> Thread(r, "herdr-ssh").apply { isDaemon = true } }
    private val operations = Executors.newSingleThreadExecutor { r ->
        Thread(r, "herdr-ssh-operation").apply { isDaemon = true }
    }
    private val filesWorker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1)) { r ->
        Thread(r, "herdr-sftp").apply { isDaemon = true }
    }
    private val deadline = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "herdr-ssh-deadline").apply { isDaemon = true }
    }
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64)) { r ->
        Thread(r, "herdr-ssh-input").apply { isDaemon = true }
    }
    private var generation = 0L
    private var active: Attempt? = null
    private var nextOperation = 0L
    private var permanentlyClosed = false

    /**
     * With no pin this only obtains the remote fingerprint and rejects the host.
     * No private key is parsed or offered for this discovery. Caller retains
     * ownership of its credential arrays and can clear them once this returns.
     */
    fun connect(
        config: SshConfig,
        privateKey: ByteArray,
        passphrase: ByteArray?,
        command: String,
        columns: Int = 80,
        rows: Int = 24,
        pty: Boolean = true,
        transportOnly: Boolean = false,
    ): Long? {
        val endpoint = config.copy(
            host = config.host.trim().removeSurrounding("[", "]"),
            user = config.user.trim(),
            expectedPin = config.expectedPin?.trim()?.ifEmpty { null }
        )
        require(endpoint.host.isNotEmpty() && endpoint.host.none { it.isWhitespace() || it.isISOControl() }) {
            "Enter an SSH host name or address."
        }
        require(endpoint.user.isNotEmpty() && endpoint.user.none { it.isISOControl() }) { "Enter an SSH username." }
        require(endpoint.port in 1..65535) { "SSH port must be between 1 and 65535." }
        require(endpoint.expectedPin == null || isSshSha256Pin(endpoint.expectedPin)) {
            "Enter the verified SHA256: fingerprint shown by OpenSSH."
        }
        require(command.isNotBlank() && '\u0000' !in command) { "An attach command is required." }
        require(!transportOnly || (!pty && endpoint.expectedPin != null)) { "An idle transport requires a pinned host and no PTY." }
        if (endpoint.expectedPin != null) {
            require(privateKey.size in 1..MAX_KEY_BYTES) { "Choose an SSH private key file (up to 128 KiB)." }
            require((passphrase?.size ?: 0) <= MAX_KEY_BYTES) { "The passphrase is too long." }
        }

        return synchronized(lock) {
            if (permanentlyClosed || active != null) return@synchronized null
            val attempt = Attempt(++generation, size(columns, rows, 0, 0), pty, transportOnly)
            val keyCopy = if (endpoint.expectedPin != null) privateKey.copyOf() else null
            val passCopy = if (keyCopy != null) passphrase?.copyOf() else null
            active = attempt
            reader.execute { runAttempt(attempt, endpoint, keyCopy, passCopy, command) }
            attempt.generation
        }
    }

    fun isCurrent(id: Long): Boolean = synchronized(lock) { !permanentlyClosed && generation == id }

    fun hasIdleTransport(): Boolean = synchronized(lock) {
        active?.let { it.transportOnly && it.setupComplete && !it.ending && it.channel == null && it.session?.isConnected == true } == true
    }

    /** Opaque channel identity, not SSH transport generation: switching agents replaces it. */
    fun terminalIdentity(): Any? = synchronized(lock) {
        active?.takeIf { it.pty && it.setupComplete && !it.ending && it.input!=null }
            ?.channel?.takeIf { it.isConnected }
    }

    fun send(bytes: ByteArray): Boolean = enqueueInput(null, bytes)

    fun sendToTerminal(identity: Any, bytes: ByteArray): Boolean = enqueueInput(identity, bytes)

    private fun enqueueInput(identity: Any?, bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_INPUT_BYTES) return false
        val (attempt, stream) = synchronized(lock) {
            val current=active ?: return false
            if(identity!=null && (current.channel!==identity || !current.pty || current.ending ||
                current.channel?.isConnected!=true)) return false
            current to (current.input ?: return false)
        }
        val copy = bytes.copyOf()
        return try {
            writer.execute {
                if (isActive(attempt) && attempt.input === stream) {
                    try {
                        stream.write(copy)
                        stream.flush()
                    } catch (_: IOException) {
                        // Closing the transport wakes the reader and ends this generation.
                        if (attempt.input === stream) attempt.socket?.let(::closeSocket)
                    } finally {
                        copy.fill(0)
                    }
                } else {
                    copy.fill(0)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            copy.fill(0)
            false
        }
    }

    fun resize(columns: Int, rows: Int, widthPixels: Int = 0, heightPixels: Int = 0) {
        val attempt = synchronized(lock) { active } ?: return
        if (!attempt.pty) return
        attempt.desiredSize = size(columns, rows, widthPixels, heightPixels)
        if (attempt.input != null) resizePending(attempt)
    }

    private fun resizePending(attempt: Attempt) {
        try {
            writer.execute { if (isActive(attempt)) attempt.channel?.let { applySize(it, attempt.desiredSize) } }
        } catch (_: RejectedExecutionException) {
            // Input is bounded; retain the desired size for the next update.
        }
    }

    internal class FileRequest(private val onCancel: () -> Unit = {}) {
        @Volatile private var channel: ChannelSftp? = null
        @Volatile var canceled = false
            private set

        @Synchronized fun bind(opened: ChannelSftp): Boolean {
            if (canceled) return false
            channel = opened
            return true
        }

        @Synchronized fun markCanceled(): ChannelSftp? {
            if (!canceled) {
                canceled = true
                onCancel()
            }
            return channel
        }
    }

    internal fun cancelFiles(request: FileRequest) {
        val channel = request.markCanceled() ?: return
        try { deadline.execute { runCatching { channel.disconnect() } } }
        catch (_: RejectedExecutionException) { /* Transport shutdown closes the channel. */ }
    }

    internal fun readFiles(
        pty: Any, root: String, parts: List<String>, preview: Boolean, canonical: Boolean,
        onResult: (FileRequest, SftpRead) -> Unit,
    ): FileRequest? {
        val attempt = synchronized(lock) {
            active?.takeIf { it.setupComplete && !it.ending && it.channel === pty && it.session?.isConnected == true }
        } ?: return null
        val request = FileRequest()
        val finished = java.util.concurrent.atomic.AtomicBoolean()
        fun current() = synchronized(lock) { active === attempt && !attempt.ending && attempt.channel === pty }
        return try {
            val timeout = deadline.schedule({
                if (finished.compareAndSet(false, true)) {
                    if (!request.canceled && current()) onResult(request, SftpRead.Failure("SFTP did not respond. Try again."))
                    request.markCanceled()?.disconnect()
                }
            }, 15, TimeUnit.SECONDS)
            try {
                filesWorker.execute {
                    var channel: ChannelSftp? = null
                    var connected = false
                    val result = try {
                        if (request.canceled || !current()) return@execute
                        channel = attempt.session?.openChannel("sftp") as? ChannelSftp ?: throw IOException()
                        if (!request.bind(channel)) return@execute
                        channel.connect(5000)
                        connected = true
                        if (request.canceled || !current()) return@execute
                        AgentSftp(channel).read(root, parts, preview, canonical)
                    } catch (error: Exception) {
                        if (connected) AgentSftp.failure(error)
                        else SftpRead.Failure("This SSH connection could not open an SFTP channel.")
                    } finally {
                        timeout.cancel(false)
                        runCatching { channel?.disconnect() }
                    }
                    if (finished.compareAndSet(false, true) && !request.canceled && current()) onResult(request, result)
                }
            } catch (error: RejectedExecutionException) {
                timeout.cancel(false)
                throw error
            }
            request
        } catch (_: RejectedExecutionException) {
            null
        }
    }

    /** Takes ownership of a unique app-private staging file until a successful callback transfers it back. */
    internal fun downloadFile(
        pty: Any, root: String, parts: List<String>, destination: File,
        onResult: (FileRequest, SftpDownload) -> Unit,
    ): FileRequest? {
        val attempt = synchronized(lock) {
            active?.takeIf { it.setupComplete && !it.ending && it.channel === pty && it.session?.isConnected == true }
        } ?: return null
        val path = parts.toList()
        val request = FileRequest { destination.delete() }
        val finished = java.util.concurrent.atomic.AtomicBoolean()
        fun current() = synchronized(lock) { active === attempt && !attempt.ending && attempt.channel === pty }
        return try {
            val timeout = deadline.schedule({
                if (finished.compareAndSet(false, true)) {
                    val report = !request.canceled && current()
                    runCatching { request.markCanceled()?.disconnect() }
                    if (report && current()) onResult(request, SftpDownload.Failure("SFTP did not respond in time. Try again."))
                }
            }, 120, TimeUnit.SECONDS)
            try {
                filesWorker.execute {
                    var channel: ChannelSftp? = null
                    var connected = false
                    var handedOff = false
                    try {
                        if (request.canceled || !current()) return@execute
                        val result = try {
                            channel = attempt.session?.openChannel("sftp") as? ChannelSftp ?: throw IOException()
                            if (!request.bind(channel)) return@execute
                            channel.connect(5000)
                            connected = true
                            if (request.canceled || !current()) return@execute
                            AgentSftp(channel).download(root, path, destination) { !request.canceled && current() }
                        } catch (error: Exception) {
                            SftpDownload.Failure(if (connected) AgentSftp.failure(error).message
                                else "This SSH connection could not open an SFTP channel.")
                        }
                        if (finished.compareAndSet(false, true) && !request.canceled && current()) {
                            handedOff = result is SftpDownload.Success
                            if (!handedOff) destination.delete()
                            onResult(request,result)
                        }
                    } finally {
                        timeout.cancel(false)
                        runCatching { channel?.disconnect() }
                        if (!handedOff) destination.delete()
                    }
                }
            } catch (error: RejectedExecutionException) {
                timeout.cancel(false)
                throw error
            }
            request
        } catch (_: RejectedExecutionException) {
            null
        }
    }

    internal fun uploadFile(
        pty:Any, root:String, folderParts:List<String>, name:String, source:()->InputStream,
        onResult:(FileRequest,SftpUpload)->Unit,
    ):FileRequest? {
        if(!AgentFiles.entryName(name)) return null
        val attempt=synchronized(lock) {
            active?.takeIf {it.setupComplete && !it.ending && it.channel===pty && it.session?.isConnected==true}
        } ?: return null
        val path=folderParts.toList()
        val request=FileRequest()
        val reported=java.util.concurrent.atomic.AtomicBoolean()
        fun current()=synchronized(lock) {active===attempt && !attempt.ending && attempt.channel===pty}
        return try {
            val timeout=deadline.schedule({
                if(!request.canceled && reported.compareAndSet(false,true)) {
                    runCatching { request.markCanceled()?.disconnect() }
                    if(current()) onResult(request,SftpUpload.Failure("Upload timed out. Refresh the folder before retrying."))
                }
            },120,TimeUnit.SECONDS)
            try {
                filesWorker.execute {
                    var channel:ChannelSftp?=null
                    var stage:SftpUploadStage?=null
                    try {
                        if(request.canceled || !current()) return@execute
                        var result=try {
                            channel=attempt.session?.openChannel("sftp") as? ChannelSftp ?: throw IOException()
                            if(!request.bind(channel)) return@execute
                            channel.connect(5000)
                            AgentSftp(channel).upload(root,path,name,source,
                                { !request.canceled && current() },{ stage=it })
                        } catch(error:Exception) {
                            val message=if(error is SftpException && error.id==ChannelSftp.SSH_FX_PERMISSION_DENIED)
                                "You don’t have permission to upload to this folder."
                            else "Could not upload this file over SFTP."
                            SftpUpload.Failure(message)
                        }
                        stage?.let { owned ->
                            val cleaned=channel?.takeIf {it.isConnected && !request.canceled}
                                ?.let {AgentSftp(it).cleanupUpload(owned)}==true
                            if(!cleaned) {
                                runCatching {channel?.disconnect()}
                                val cleanedAfter=cleanupUploadStage(attempt.session,owned)
                                if(!cleanedAfter && result is SftpUpload.Failure)
                                    result=SftpUpload.Failure("Upload failed; a hidden temporary folder may remain on the host.")
                            }
                        }
                        if(current() && !request.canceled && reported.compareAndSet(false,true))
                            onResult(request,result)
                    } finally {
                        timeout.cancel(false)
                        runCatching {channel?.disconnect()}
                    }
                }
            } catch(error:RejectedExecutionException) {
                timeout.cancel(false)
                throw error
            }
            request
        } catch(_:RejectedExecutionException) {null}
    }

    private fun cleanupUploadStage(session:com.jcraft.jsch.Session?,stage:SftpUploadStage):Boolean {
        if(session?.isConnected!=true) return false
        val channel=runCatching {session.openChannel("sftp") as ChannelSftp}.getOrNull() ?: return false
        val timeout=runCatching {deadline.schedule({runCatching {channel.disconnect()}},10,TimeUnit.SECONDS)}.getOrNull()
        if(timeout==null) {channel.disconnect();return false}
        return try {
            channel.connect(5000)
            AgentSftp(channel).cleanupUpload(stage)
        } catch(_:Exception) {false}
        finally {timeout.cancel(false);runCatching {channel.disconnect()}}
    }

    internal fun deleteFile(
        pty:Any,root:String,folderParts:List<String>,name:String,
        expected:SftpFileIdentity?=null,directory:Boolean=false,onResult:(FileRequest,SftpDelete)->Unit,
    ):FileRequest? {
        if(!AgentFiles.entryName(name)) return null
        val attempt=synchronized(lock) {
            active?.takeIf {it.setupComplete && !it.ending && it.channel===pty && it.session?.isConnected==true}
        } ?: return null
        val path=folderParts.toList()
        val request=FileRequest()
        val reported=java.util.concurrent.atomic.AtomicBoolean()
        val removeSent=java.util.concurrent.atomic.AtomicBoolean()
        fun current()=synchronized(lock) {active===attempt && !attempt.ending && attempt.channel===pty}
        val kind=if(directory) "folder" else "file"
        fun ambiguous()=SftpDelete.Failure("Could not confirm whether the $kind was deleted. Refresh the folder before trying again.")
        return try {
            val timeout=deadline.schedule({
                if(!request.canceled && reported.compareAndSet(false,true)) {
                    runCatching {request.markCanceled()?.disconnect()}
                    if(current()) onResult(request,if(removeSent.get()) ambiguous()
                        else SftpDelete.Failure("SFTP timed out before deletion. Refresh the folder before trying again."))
                }
            },20,TimeUnit.SECONDS)
            try {
                filesWorker.execute {
                    var channel:ChannelSftp?=null
                    try {
                        if(request.canceled || !current()) return@execute
                        val result=try {
                            channel=attempt.session?.openChannel("sftp") as? ChannelSftp ?: throw IOException()
                            if(!request.bind(channel)) return@execute
                            channel.connect(5000)
                            AgentSftp(channel).delete(root,path,name,expected,directory,{!request.canceled && current()}) {
                                removeSent.set(true)
                                if(request.canceled || !current()) throw java.io.InterruptedIOException()
                            }
                        } catch(error:Exception) {
                            when {
                                error is SftpException && error.id==ChannelSftp.SSH_FX_PERMISSION_DENIED ->
                                    SftpDelete.Failure("You don’t have permission to delete this $kind.")
                                removeSent.get() -> ambiguous()
                                error is SftpException && error.id==ChannelSftp.SSH_FX_NO_SUCH_FILE ->
                                    SftpDelete.Failure("The $kind no longer exists. Refresh the folder.")
                                else -> SftpDelete.Failure("Could not verify the selected $kind for deletion. Refresh the folder.")
                            }
                        }
                        if(current() && !request.canceled && reported.compareAndSet(false,true)) onResult(request,result)
                    } finally {
                        timeout.cancel(false)
                        runCatching {channel?.disconnect()}
                    }
                }
            } catch(error:RejectedExecutionException) {
                timeout.cancel(false)
                throw error
            }
            request
        } catch(_:RejectedExecutionException) {null}
    }

    internal fun createFolder(
        pty:Any,root:String,folderParts:List<String>,name:String,
        onResult:(FileRequest,SftpMkdir)->Unit,
    ):FileRequest? {
        if(!AgentFiles.newFolderName(name)) return null
        val attempt=synchronized(lock) {
            active?.takeIf {it.setupComplete && !it.ending && it.channel===pty && it.session?.isConnected==true}
        } ?: return null
        val path=folderParts.toList()
        val request=FileRequest()
        val reported=java.util.concurrent.atomic.AtomicBoolean()
        val requestStarted=java.util.concurrent.atomic.AtomicBoolean()
        fun current()=synchronized(lock) {active===attempt && !attempt.ending && attempt.channel===pty}
        fun ambiguous()=SftpMkdir.Failure("Could not confirm whether the folder was created. Refresh the folder before trying again.")
        return try {
            val timeout=deadline.schedule({
                if(!request.canceled && reported.compareAndSet(false,true)) {
                    runCatching {request.markCanceled()?.disconnect()}
                    if(current()) onResult(request,if(requestStarted.get()) ambiguous()
                        else SftpMkdir.Failure("SFTP timed out before folder creation. Refresh the folder before trying again."))
                }
            },20,TimeUnit.SECONDS)
            try {
                filesWorker.execute {
                    var channel:ChannelSftp?=null
                    try {
                        if(request.canceled || !current()) return@execute
                        val result=try {
                            channel=attempt.session?.openChannel("sftp") as? ChannelSftp ?: throw IOException()
                            if(!request.bind(channel)) return@execute
                            channel.connect(5000)
                            AgentSftp(channel).createFolder(root,path,name,{!request.canceled && current()}) {
                                requestStarted.set(true)
                                if(request.canceled || !current()) throw java.io.InterruptedIOException()
                            }
                        } catch(error:Exception) {
                            when {
                                error is SftpException && error.id==ChannelSftp.SSH_FX_PERMISSION_DENIED ->
                                    SftpMkdir.Failure("You don’t have permission to create a folder here.")
                                requestStarted.get() -> ambiguous()
                                error is SftpException && error.id==ChannelSftp.SSH_FX_NO_SUCH_FILE ->
                                    SftpMkdir.Failure("The destination folder no longer exists. Refresh the folder.")
                                else -> SftpMkdir.Failure("Could not verify the destination folder. Refresh the folder.")
                            }
                        }
                        if(current() && !request.canceled && reported.compareAndSet(false,true)) onResult(request,result)
                    } finally {
                        timeout.cancel(false)
                        runCatching {channel?.disconnect()}
                    }
                }
            } catch(error:RejectedExecutionException) {
                timeout.cancel(false)
                throw error
            }
            request
        } catch(_:RejectedExecutionException) {null}
    }

    /** List on a separate non-PTY channel without interrupting current terminal I/O. */
    fun listOnTransport(command: String): Long? = startOperation(command, null)

    /** Stage a new PTY; the current PTY remains active until the replacement commits. */
    fun switchPty(command: String, columns: Int = 80, rows: Int = 24, tui:Boolean=false): Long? =
        startOperation(command, size(columns, rows, 0, 0), tui)

    /** Cancel only the pending operation, not the authenticated transport or current PTY. */
    fun cancelOperation(id: Long): Boolean = synchronized(lock) {
        val operation = active?.operation ?: return@synchronized false
        if (operation.id != id || operation.canceled) return@synchronized false
        operation.canceled = true
        true
    }

    private fun startOperation(command: String, switchSize: Size?, tui:Boolean=false): Long? {
        require(command.isNotBlank() && '\u0000' !in command) { "A terminal command is required." }
        return synchronized(lock) {
            val attempt = active ?: return@synchronized null
            val session = attempt.session ?: return@synchronized null
            val idle = attempt.transportOnly && attempt.channel == null
            val livePty = attempt.pty && attempt.channel?.isConnected == true
            if (permanentlyClosed || !attempt.setupComplete || attempt.ending ||
                attempt.operation != null || !session.isConnected || (!idle && !livePty)
            ) return@synchronized null
            val operation = Operation(++nextOperation, switchSize, tui)
            attempt.operation = operation
            operations.execute { runOperation(attempt, session, operation, command) }
            operation.id
        }
    }

    private fun operationActive(attempt: Attempt, operation: Operation): Boolean = synchronized(lock) {
        active === attempt && !attempt.ending && attempt.operation === operation && !operation.canceled
    }

    private fun runOperation(
        attempt: Attempt, session: com.jcraft.jsch.Session, operation: Operation, command: String
    ) {
        var opened: ChannelExec? = null
        var swapped = false
        var result: Event? = null
        try {
            if (!operationActive(attempt, operation)) return
            val channel = session.openChannel("exec") as ChannelExec
            opened = channel
            channel.setAgentForwarding(false)
            val switchSize = operation.switchSize
            channel.setPty(switchSize != null)
            if (switchSize != null) {
                channel.setPtyType("xterm-256color", switchSize.columns, switchSize.rows, switchSize.px, switchSize.py)
            }
            channel.setCommand(command)
            val stdin = if (switchSize != null) channel.outputStream else null
            channel.setOutputStream(operationSink(attempt, operation, channel, false))
            channel.setErrStream(operationSink(attempt, operation, channel, true))
            channel.connect(CONNECT_MS)
            if (switchSize == null) {
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(NON_PTY_DEADLINE_SECONDS)
                while (operationActive(attempt, operation) && !channel.isClosed &&
                    !operation.overflow && System.nanoTime() < end) Thread.sleep(25)
                result = synchronized(attempt.outputOrder) {
                    when {
                        operation.overflow -> Event.OperationFailed(operation.id, "Session list exceeds 1 MiB.")
                        !channel.isClosed -> Event.OperationFailed(operation.id, "Session list did not finish within 30 seconds.")
                        else -> Event.OperationList(operation.id, operation.stdout.toByteArray(),
                            operation.stderr.toByteArray(), channel.exitStatus.takeIf { it >= 0 })
                    }
                }
            } else {
                // SSH exec acknowledgement and raw-mode setup do not confirm that
                // Herdr granted this attachment. Keep the old PTY until a complete
                // first TUI repaint arrives. Core retains its existing grace period.
                val waitMs = if(operation.tui) TUI_ATTACH_READY_TIMEOUT_MS else SWITCH_GRACE_MS
                val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
                while (operationActive(attempt, operation) && channel.isConnected &&
                    !operation.overflow && !(operation.tui && operation.frameReady) &&
                    System.nanoTime() < end) Thread.sleep(15)
                val old = synchronized(attempt.outputOrder) {
                    val previous = synchronized(lock) {
                        if (active !== attempt || attempt.ending || attempt.operation !== operation ||
                            operation.canceled || !session.isConnected || !channel.isConnected || operation.overflow ||
                            (operation.tui && !operation.frameReady)
                        ) null else {
                            val previous = attempt.channel
                            if (previous == null) attempt.desiredSize = switchSize
                            attempt.pty = true
                            attempt.channel = channel
                            attempt.input = stdin
                            attempt.operation = null
                            swapped = true
                            previous
                        }
                    }
                    if (swapped) {
                        report(attempt, Event.OperationSwitched(operation.id))
                        operation.pending.forEach { report(attempt, it) }
                        operation.pending.clear()
                    }
                    previous
                }
                if (swapped) {
                    if (attempt.desiredSize != switchSize) resizePending(attempt)
                    runCatching { old?.disconnect() }
                } else {
                    val tuiFailure=if(operation.tui) synchronized(attempt.outputOrder) {
                        TuiAgents.attachFailure(operation.stderr.toByteArray())
                    } else null
                    val detail = tuiFailure ?: if(operation.tui && channel.isConnected &&
                        !operation.overflow && !operation.frameReady
                    ) "Herdr did not send its first screen in time. Check the host's Herdr TUI version."
                    else "Could not attach the selected agent."
                    result = Event.OperationFailed(operation.id,
                        if (attempt.channel == null) detail else "$detail The previous terminal was kept.")
                }
            }
        } catch (_: Exception) {
            result = Event.OperationFailed(operation.id, if (operation.switchSize == null)
                "Could not open a session-list channel." else
                if (attempt.channel == null) "Could not attach the selected agent."
                else "Could not attach the selected agent; the previous terminal was kept.")
        } finally {
            if (!swapped) runCatching { opened?.disconnect() }
            val deliver = synchronized(lock) {
                if (attempt.operation === operation) {
                    attempt.operation = null
                    active === attempt && !attempt.ending && !operation.canceled
                } else false
            }
            if (deliver) result?.let { report(attempt, it) }
        }
    }

    private fun operationSink(attempt: Attempt, operation: Operation, channel: ChannelExec, stderr: Boolean) =
        object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                if (count <= 0) return
                synchronized(attempt.outputOrder) {
                    if (!isActive(attempt)) return
                    if (attempt.channel === channel) {
                        val data = bytes.copyOfRange(offset, offset + count)
                        report(attempt, if (stderr) Event.ErrorOutput(data) else Event.Output(data))
                        return
                    }
                    if (!operationActive(attempt, operation) || operation.overflow) return
                    if (operation.switchSize != null) {
                        // A remote SSH PTY can merge command diagnostics into stdout.
                        if (operation.tui && operation.stderr.size()<NON_PTY_STDERR_LIMIT)
                            operation.stderr.write(bytes, offset, minOf(count, NON_PTY_STDERR_LIMIT-operation.stderr.size()))
                        if (count > NON_PTY_STDOUT_LIMIT - operation.pendingBytes) {
                            operation.overflow = true
                        } else {
                            val data = bytes.copyOfRange(offset, offset + count)
                            operation.pending += if (stderr) Event.ErrorOutput(data) else Event.Output(data)
                            operation.pendingBytes += count
                            if (!stderr && operation.tui) operation.observeOutput(bytes,offset,count)
                        }
                    } else if (stderr) {
                        operation.stderr.write(bytes, offset, minOf(count, NON_PTY_STDERR_LIMIT - operation.stderr.size()))
                    } else if (count > NON_PTY_STDOUT_LIMIT - operation.stdout.size()) {
                        operation.overflow = true
                    } else {
                        operation.stdout.write(bytes, offset, count)
                    }
                }
            }
        }

    fun disconnect() = cancel(false)
    override fun close() = cancel(true)

    private fun cancel(close: Boolean) {
        val socket = synchronized(lock) {
            generation++
            val socket = active?.socket
            active = null
            if (close && !permanentlyClosed) {
                permanentlyClosed = true
                // Let queued tasks run their cleanup instead of dropping credential copies.
                reader.shutdown()
                operations.shutdown()
                writer.shutdown()
                filesWorker.shutdown()
                deadline.shutdown()
            }
            socket
        }
        socket?.let(::closeSocket)
    }

    private fun isActive(attempt: Attempt): Boolean = synchronized(lock) {
        active === attempt && !permanentlyClosed
    }

    private fun report(attempt: Attempt, event: Event) {
        if (isCurrent(attempt.generation)) onEvent(attempt.generation, event)
    }

    private fun runAttempt(
        attempt: Attempt, config: SshConfig, key: ByteArray?, passphrase: ByteArray?, command: String
    ) {
        val repository = PinnedHostKey(config.expectedPin)
        val client = JSch()
        var session: com.jcraft.jsch.Session? = null
        var result: Event? = null
        var commandTimer: java.util.concurrent.ScheduledFuture<*>? = null
        val timer = synchronized(lock) {
            if (active === attempt && !permanentlyClosed) {
                deadline.schedule({
                    val socket = synchronized(lock) {
                        if (active !== attempt || attempt.setupComplete) return@schedule
                        attempt.timedOut = true
                        attempt.socket
                    }
                    socket?.let(::closeSocket)
                }, SETUP_DEADLINE_SECONDS, TimeUnit.SECONDS)
            } else null
        }
        try {
            if (timer == null) return
            if (key != null) {
                try {
                    client.addIdentity("workspace", key, null, passphrase)
                } catch (_: Exception) {
                    result = Event.Failed("Could not open the private key. Check its format and passphrase.")
                    return
                } finally {
                    key.fill(0)
                    passphrase?.fill(0)
                }
            }
            if (!isActive(attempt)) return
            session = client.getSession(config.user, config.host, config.port)
            session.setHostKeyRepository(repository)
            session.setConfig("StrictHostKeyChecking", "yes")
            session.setConfig("PreferredAuthentications", "publickey")
            // Use raw keys only so the pin always covers the negotiated wire key blob.
            // No certificate-CA acceptance or host-certificate fallback.
            session.setConfig("server_host_key", "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384," +
                "ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256")
            session.setSocketFactory(object : SocketFactory {
                override fun createSocket(host: String, port: Int): Socket {
                    val socket = Socket()
                    synchronized(lock) {
                        if (active !== attempt || attempt.timedOut) {
                            closeSocket(socket)
                            throw IOException("SSH connection cancelled")
                        }
                        attempt.socket = socket
                    }
                    socket.connect(InetSocketAddress(host, port), CONNECT_MS)
                    return socket
                }
                override fun getInputStream(socket: Socket): InputStream =
                    object : FilterInputStream(socket.getInputStream()) {
                        override fun read(): Int = trackRead { super.read() }
                        override fun read(bytes: ByteArray, offset: Int, size: Int): Int =
                            trackRead { super.read(bytes, offset, size) }

                        private fun trackRead(read: () -> Int): Int {
                            return try {
                                read().also { if (it < 0) attempt.transportReadFailed = true }
                            } catch (e: IOException) {
                                // JSch uses ordinary read timeouts to send its keepalive.
                                if (e !is SocketTimeoutException) attempt.transportReadFailed = true
                                throw e
                            }
                        }
                    }
                override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
            })
            session.connect(CONNECT_MS)
            // Defense in depth; a connection may not open a channel without a matched pin.
            check(repository.accepted)
            client.removeAllIdentity()
            if (!isActive(attempt) || attempt.timedOut) throw IOException("SSH setup interrupted")
            session.setServerAliveInterval(KEEPALIVE_MS)
            session.setServerAliveCountMax(2)
            if (attempt.transportOnly) {
                synchronized(lock) {
                    if (active !== attempt || attempt.timedOut) throw IOException("SSH setup interrupted")
                    attempt.session = session
                    attempt.setupComplete = true
                }
                timer.cancel(false)
                report(attempt, Event.TransportReady)
                // An authenticated transport may deliberately have no channel yet.
                // The first terminal, when selected, is staged by switchPty().
                while (synchronized(lock) {
                    if (active === attempt && session.isConnected && attempt.channel?.isClosed != true) true
                    else { attempt.ending = true; false }
                }) Thread.sleep(50)
                val status = attempt.channel?.exitStatus?.takeIf { it >= 0 }
                result = when {
                    transportLost(attempt, session, status) -> Event.TransportLost
                    !attempt.pty -> Event.Failed("The SSH connection closed before a terminal was opened.")
                    else -> Event.Closed(status)
                }
                return
            }
            val channel = session.openChannel("exec") as ChannelExec
            channel.setPty(attempt.pty)
            channel.setAgentForwarding(false)
            val dimensions = attempt.desiredSize
            if (attempt.pty) {
                channel.setPtyType("xterm-256color", dimensions.columns, dimensions.rows, dimensions.px, dimensions.py)
            }
            channel.setCommand(command)
            val stdin = channel.outputStream
            attempt.channel = channel
            channel.setOutputStream(outputSink(attempt, channel, stderr = false))
            // PTYs generally route both streams to stdout. SSH extended data, if
            // present, remains distinct so a non-PTY caller can safely parse JSON.
            channel.setErrStream(outputSink(attempt, channel, stderr = true))
            channel.connect(CONNECT_MS)
            synchronized(lock) {
                if (active !== attempt || attempt.timedOut) throw IOException("SSH setup interrupted")
                attempt.setupComplete = true
                attempt.session = session
                attempt.input = stdin
                if (!attempt.pty) {
                    commandTimer = deadline.schedule({
                        val socket = synchronized(lock) {
                            if (active !== attempt || attempt.channel?.isClosed != false) return@schedule
                            attempt.commandTimedOut = true
                            attempt.socket
                        }
                        socket?.let(::closeSocket)
                    }, NON_PTY_DEADLINE_SECONDS, TimeUnit.SECONDS)
                }
            }
            timer.cancel(false)
            if (attempt.pty && attempt.desiredSize != dimensions) resizePending(attempt)
            report(attempt, Event.Connected)
            while (synchronized(lock) {
                val current = attempt.channel
                if (active === attempt && session.isConnected && current?.isClosed == false) true
                else { attempt.ending = true; false }
            }) Thread.sleep(50)
            val status = attempt.channel?.exitStatus?.takeIf { it >= 0 }
            result = if (transportLost(attempt, session, status)) Event.TransportLost else Event.Closed(status)
        } catch (_: Exception) {
            val actual = repository.rejected
            result = when {
                actual != null -> Event.RejectedHostKey(actual, config.expectedPin)
                attempt.timedOut -> Event.Failed("SSH setup timed out.")
                repository.accepted && transportLost(attempt, session, attempt.channel?.exitStatus?.takeIf { it >= 0 }) ->
                    Event.TransportLost
                !repository.accepted -> Event.Failed("Could not verify the SSH host. Check the address, port, and server host-key support.")
                session?.isConnected != true -> Event.Failed("SSH authentication failed or the connection was lost. Check the username, key, and passphrase.")
                else -> Event.Failed("Could not open the remote terminal.")
            }
        } finally {
            timer?.cancel(false)
            commandTimer?.cancel(false)
            key?.fill(0)
            passphrase?.fill(0)
            attempt.input = null
            attempt.socket?.let(::closeSocket)
            runCatching { attempt.channel?.disconnect() }
            runCatching { session?.disconnect() }
            runCatching { client.removeAllIdentity() }
            synchronized(lock) { if (active === attempt) active = null }
            val outcome = when {
                attempt.outputExceeded -> Event.Failed("Session list exceeds 1 MiB.")
                attempt.commandTimedOut -> Event.Failed("Session list did not finish within 30 seconds.")
                else -> result
            }
            synchronized(attempt.outputOrder) { outcome?.let { report(attempt, it) } }
        }
    }

    private fun transportLost(attempt: Attempt, session: com.jcraft.jsch.Session?, exitStatus: Int?): Boolean {
        if (!attempt.pty || !attempt.setupComplete || exitStatus != null || session == null || !isActive(attempt)) {
            return false
        }
        // JSch tears down channels before clearing isConnected. If the socket
        // just failed, let that state transition finish before treating this
        // as an ordinary channel close without an exit status.
        if (session.isConnected && (attempt.transportReadFailed || attempt.socket?.isClosed == true)) {
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250)
            while (session.isConnected && isActive(attempt) && System.nanoTime() < until) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        return !session.isConnected && isActive(attempt)
    }

    private fun outputSink(attempt: Attempt, channel: ChannelExec, stderr: Boolean) = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(bytes: ByteArray, offset: Int, count: Int) {
            synchronized(attempt.outputOrder) {
                if (count <= 0 || !isActive(attempt) || attempt.channel !== channel) return
                val admitted = if (attempt.pty) count else if (stderr) {
                    minOf(count, NON_PTY_STDERR_LIMIT - attempt.stderrBytes).also { attempt.stderrBytes += it }
                } else {
                    val remaining = NON_PTY_STDOUT_LIMIT - attempt.stdoutBytes
                    if (count > remaining) {
                        attempt.outputExceeded = true
                        attempt.socket?.let(::closeSocket)
                        return
                    }
                    count.also { attempt.stdoutBytes += it }
                }
                if (admitted > 0) {
                    val data = bytes.copyOfRange(offset, offset + admitted)
                    report(attempt, if (stderr) Event.ErrorOutput(data) else Event.Output(data))
                }
            }
        }
    }

    private fun applySize(channel: ChannelExec, dimensions: Size) =
        channel.setPtySize(dimensions.columns, dimensions.rows, dimensions.px, dimensions.py)

    private fun closeSocket(socket: Socket) { runCatching { socket.close() } }

    private fun size(columns: Int, rows: Int, px: Int, py: Int) =
        Size(columns.coerceIn(1, 1000), rows.coerceIn(1, 1000), px.coerceAtLeast(0), py.coerceAtLeast(0))

    private companion object {
        const val CONNECT_MS = 8_000
        const val SETUP_DEADLINE_SECONDS = 20L
        const val KEEPALIVE_MS = 15_000
        const val SWITCH_GRACE_MS = 200L
        // A stalled or incompatible TUI that never renders is not safe to
        // promote. This is only a failure deadline, not a fixed switch delay.
        const val TUI_ATTACH_READY_TIMEOUT_MS = 12_000L
        const val NON_PTY_DEADLINE_SECONDS = 30L
        const val NON_PTY_STDOUT_LIMIT = 1024 * 1024
        const val NON_PTY_STDERR_LIMIT = 8192
        const val MAX_KEY_BYTES = 128 * 1024
        const val MAX_INPUT_BYTES = 64 * 1024
    }
}

internal fun sshSha256Fingerprint(wireKey: ByteArray): String =
    "SHA256:" + Base64.getEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(wireKey))

internal fun isSshSha256Pin(pin: String): Boolean {
    if (!pin.startsWith("SHA256:") || pin.length != 50) return false
    val encoded = pin.substring(7)
    val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { return false }
    return bytes.size == 32 && Base64.getEncoder().withoutPadding().encodeToString(bytes) == encoded
}

internal class PinnedHostKey(private val expected: String?) : HostKeyRepository {
    var accepted = false
        private set
    var rejected: String? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val actual = sshSha256Fingerprint(key)
        accepted = expected != null && MessageDigest.isEqual(actual.toByteArray(Charsets.US_ASCII),
            expected.toByteArray(Charsets.US_ASCII))
        if (accepted) return HostKeyRepository.OK
        rejected = actual
        return if (expected == null) HostKeyRepository.NOT_INCLUDED else HostKeyRepository.CHANGED
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) = error("This pin repository never adds a host key.")
    override fun remove(host: String?, type: String?) = error("This pin repository is immutable.")
    override fun remove(host: String?, type: String?, key: ByteArray?) = error("This pin repository is immutable.")
    override fun getKnownHostsRepositoryID() = "explicit fingerprint pin"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}
