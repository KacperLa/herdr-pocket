package dev.kaspy.herdr

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelDirectStreamLocal
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One independent authenticated transport; never sends data to the terminal's PTY. */
internal class AgentWatchConnection(
    private val config:SshConfig,
    private val namedSession:String?,
    private val key:ByteArray,
    private val passphrase:ByteArray?,
    private val onAlert:(AgentWatchAlert)->Unit,
    private val onState:(AgentWatchState)->Unit,
):AutoCloseable {
    private val closed=AtomicBoolean()
    private val credentialLock=Any()
    private val session=AtomicReference<Session?>()
    private val socket=AtomicReference<Socket?>()
    private val worker=AtomicReference<Thread?>()
    private val reducer=AgentWatchReducer()
    private val deadlines=Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task,"herdr-watch-deadline").apply {isDaemon=true}
    }

    fun run() {
        if(!worker.compareAndSet(null,Thread.currentThread())) return
        var backoff=1500L
        try {
            if(!isSshSha256Pin(config.expectedPin.orEmpty())) { state(AgentWatchState.PIN_FAILED);return }
            if(namedSession!=null && TuiAgents.parseSession(namedSession)!=namedSession) {
                state(AgentWatchState.UNSUPPORTED);return
            }
            while(!closed.get()) {
                val pin=PinnedHostKey(config.expectedPin)
                val ssh=JSch()
                try {
                    state(AgentWatchState.CONNECTING)
                    val peerKey:ByteArray
                    val peerPass:ByteArray?
                    synchronized(credentialLock) {
                        if(closed.get()) return
                        peerKey=key.copyOf()
                        peerPass=passphrase?.copyOf()
                    }
                    try { ssh.addIdentity("agent-watch",peerKey,null,peerPass) }
                    catch(_:JSchException) {throw WatchStopped(AgentWatchState.AUTH_FAILED)}
                    finally {peerKey.fill(0);peerPass?.fill(0)}
                    val transport=ssh.getSession(config.user,config.host,config.port)
                    session.set(transport)
                    if(closed.get()) return
                    transport.setSocketFactory(object:SocketFactory {
                        override fun createSocket(host:String,port:Int):Socket {
                            val opened=Socket()
                            socket.set(opened)
                            if(closed.get()) {
                                opened.close()
                                throw IOException("Watch closed")
                            }
                            opened.connect(InetSocketAddress(host,port),10_000)
                            return opened
                        }
                        override fun getInputStream(s:Socket)=s.getInputStream()
                        override fun getOutputStream(s:Socket)=s.getOutputStream()
                    })
                    transport.setHostKeyRepository(pin)
                    transport.setConfig("StrictHostKeyChecking","yes")
                    transport.setConfig("PreferredAuthentications","publickey")
                    transport.setConfig("server_host_key","ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,"+
                        "ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256")
                    transport.setServerAliveInterval(15_000)
                    transport.setServerAliveCountMax(3)
                    try { transport.connect(10_000) }
                    catch(error:JSchException) {
                        if(pin.rejected!=null) throw WatchStopped(AgentWatchState.PIN_FAILED)
                        val message=error.message.orEmpty()
                        if(pin.accepted && (message.startsWith("Auth fail") || message.startsWith("Auth cancel")))
                            throw WatchStopped(AgentWatchState.AUTH_FAILED)
                        throw error
                    }
                    if(!pin.accepted) throw WatchStopped(AgentWatchState.PIN_FAILED)
                    val path=discover(transport)
                    watch(transport,path) {backoff=1500L}
                } catch(stop:WatchStopped) {
                    state(stop.state);return
                } catch(error:WatchApiError) {
                    if(error.unsupported) {
                        state(AgentWatchState.UNSUPPORTED);return
                    }
                    // Includes events_lost and a pane removed while installing subscriptions.
                } catch(_:InterruptedException) {
                    if(closed.get()) return
                } catch(_:Exception) {
                    // Re-establish after transient failures, but never poll when not subscribed.
                } finally {
                    socket.getAndSet(null)?.let { runCatching { it.close() } }
                    session.getAndSet(null)?.disconnect()
                    runCatching { ssh.removeAllIdentity() }
                }
                if(!closed.get()) {
                    state(AgentWatchState.RETRYING)
                    try { Thread.sleep(backoff) } catch(_:InterruptedException) {}
                    backoff=(backoff*2).coerceAtMost(30_000L)
                }
            }
        } finally {
            close()
        }
    }

    private fun state(value:AgentWatchState) {if(!closed.get()) onState(value)}

    private fun discover(transport:Session):String {
        val command=transport.openChannel("exec") as ChannelExec
        command.setPty(false)
        command.setAgentForwarding(false)
        command.setCommand(TuiAgents.resolve("status server --json",namedSession))
        val input=BufferedInputStream(command.inputStream)
        try {
            return timed(command) {
                command.connect(5000)
                val result=try {AgentWatchProtocol.boundedLine(input,16*1024)} catch(error:IOException) {
                    if(command.exitStatus in listOf(2,127)) throw WatchStopped(AgentWatchState.UNSUPPORTED)
                    throw error
                }
                AgentWatchProtocol.socket(result,namedSession) ?: throw IOException("Herdr is not running")
            }
        } finally { command.disconnect() }
    }

    private fun socket(transport:Session,path:String):ChannelDirectStreamLocal =
        (transport.openChannel("direct-streamlocal@openssh.com") as ChannelDirectStreamLocal).apply {
            setSocketPath(path)
        }

    private fun connect(channel:ChannelDirectStreamLocal,transport:Session) {
        try { channel.connect(5000) } catch(error:JSchException) {
            if(transport.isConnected && !closed.get() &&
                error.message.orEmpty().contains("channel is not opened",ignoreCase=true))
                throw WatchStopped(AgentWatchState.FORWARDING_UNAVAILABLE)
            throw error
        }
    }

    private fun snapshot(transport:Session,path:String):WatchRoster {
        val channel=socket(transport,path)
        val input=BufferedInputStream(channel.inputStream)
        try {
            return timed(channel) {
                connect(channel,transport)
                channel.outputStream.let {it.write(AgentWatchProtocol.requestSnapshot().toByteArray());it.flush()}
                AgentWatchProtocol.snapshot(AgentWatchProtocol.boundedLine(input,1024*1024))
            }
        } finally {channel.disconnect()}
    }

    private fun watch(transport:Session,path:String,healthy:()->Unit) {
        var panes=emptySet<String>()
        while(!closed.get()) {
            val channel=socket(transport,path)
            val dirty=AtomicBoolean()
            val dead=AtomicReference<Exception?>()
            val started=CountDownLatch(1)
            val signal=Semaphore(0)
            fun invalidate() {if(dirty.compareAndSet(false,true)) signal.release()}
            val input=BufferedInputStream(channel.inputStream)
            var reader:Thread?=null
            try {
                timed(channel) {
                    connect(channel,transport)
                    channel.outputStream.let {it.write(AgentWatchProtocol.subscribe(panes).toByteArray());it.flush()}
                }
                reader=Thread({
                    try {
                        AgentWatchProtocol.acknowledged(AgentWatchProtocol.boundedLine(input,32*1024))
                        started.countDown()
                        while(!closed.get()) if(AgentWatchProtocol.invalidates(
                            AgentWatchProtocol.boundedLine(input,256*1024))) invalidate()
                    } catch(error:Exception) {
                        dead.set(error)
                        started.countDown()
                        signal.release()
                    }
                },"herdr-watch-events").apply {isDaemon=true;start()}
                if(!started.await(8,TimeUnit.SECONDS)) throw IOException("Herdr subscription timed out")
                dead.get()?.let {throw it}
                var current=snapshot(transport,path)
                dead.get()?.let {throw it}
                publish(current)
                if(current.panes!=panes) {
                    panes=current.panes
                    continue
                }
                healthy()
                state(AgentWatchState.WATCHING)
                while(!closed.get()) {
                    signal.acquire()
                    dead.get()?.let {throw it}
                    if(closed.get()) break
                    // Collapse bursts, with at most one snapshot in flight.
                    Thread.sleep(150)
                    dirty.set(false)
                    current=snapshot(transport,path)
                    dead.get()?.let {throw it}
                    publish(current)
                    if(current.panes!=panes) {
                        panes=current.panes
                        break
                    }
                }
            } finally {
                channel.disconnect()
                reader?.join(250)
            }
        }
    }

    private fun publish(roster:WatchRoster) {
        if(closed.get()) return
        reducer.accept(roster.agents).forEach {if(!closed.get()) onAlert(it)}
    }

    private fun <T> timed(channel:Channel,action:()->T):T {
        val timeout=deadlines.schedule({channel.disconnect()},10,TimeUnit.SECONDS)
        return try {action()} finally {timeout.cancel(false)}
    }

    override fun close() {
        if(closed.compareAndSet(false,true)) {
            synchronized(credentialLock) {key.fill(0);passphrase?.fill(0)}
            val running=worker.get()
            if(running!=null && running!==Thread.currentThread()) running.interrupt()
            socket.getAndSet(null)?.let { runCatching { it.close() } }
            session.getAndSet(null)?.disconnect()
            deadlines.shutdownNow()
        }
    }
    private class WatchStopped(val state:AgentWatchState):IOException()
}
