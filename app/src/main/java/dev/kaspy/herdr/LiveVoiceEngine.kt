package dev.kaspy.herdr

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/** The owner must request permission and close this engine as soon as its visible route ends. */
internal class LiveVoiceEngine(
    private val endpoint:String,
    bearer:String,
    private val profile:LiveVoiceProfile,
    private val onState:(LiveVoiceState)->Unit,
    private val onError:(String)->Unit,
    private val onLevel:(Float)->Unit={},
    private val onEvent:(LiveVoiceProtocol.Event)->Unit={},
):AutoCloseable {
    private val key=AtomicReference<String?>(bearer)
    private val started=AtomicBoolean()
    private val closed=AtomicBoolean()
    private val finishing=AtomicBoolean()
    private val ready=AtomicBoolean()
    private val state=AtomicReference<LiveVoiceState?>()
    private val socket=LiveVoiceSocket()
    private val chunks=LiveAudioQueue()
    private val audioGate=Any()
    private val capture=AtomicReference<AudioRecord?>()
    private val playback=AtomicReference<AudioTrack?>()
    private val playing=AtomicBoolean()
    private val reader=AtomicReference<Thread?>()
    private val player=AtomicReference<Thread?>()
    private val microphone=AtomicReference<Thread?>()

    fun start() {
        if(!started.compareAndSet(false,true) || closed.get()) return
        update(LiveVoiceState.CONNECTING)
        reader.set(worker("pocket-live-socket",::receive))
    }

    private fun receive() {
        try {
            val address=LiveVoiceProtocol.endpoint(endpoint) ?: throw Setup("Enter a valid secure Live WebSocket URL.")
            val token=key.getAndSet(null)?.takeIf {LiveVoiceProtocol.validCredential(profile,it)} ?: throw Setup("Check the Live Voice credential in Settings.")
            if(closed.get()) return
            socket.connect(address,token)
            socket.text(LiveVoiceProtocol.start(profile))
            var sessionStarted=false
            val initialDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
            while(!closed.get()) {
                if(!sessionStarted && System.nanoTime()>initialDeadline) throw Setup("The Live endpoint did not start a session.")
                val message=socket.read() ?: throw IOException()
                when(val event=LiveVoiceProtocol.parse(message)) {
                    LiveVoiceProtocol.Event.Started -> {
                        if(sessionStarted) throw IOException()
                        sessionStarted=true
                        ready.set(true)
                        socket.allowSessionIdle()
                        update(LiveVoiceState.LISTENING)
                        if(!closed.get()) {
                            player.set(worker("pocket-live-playback",::play))
                            microphone.set(worker("pocket-live-microphone",::record))
                        }
                    }
                    is LiveVoiceProtocol.Event.Audio -> {
                        if(finishing.get()) event.bytes.fill(0)
                        else if(!sessionStarted || !synchronized(audioGate) {
                            chunks.offer(event)
                        }) throw IOException()
                    }
                    is LiveVoiceProtocol.Event.ClearAudio -> if(sessionStarted) clearPlayback(event.epoch)
                    LiveVoiceProtocol.Event.Closed -> return
                    LiveVoiceProtocol.Event.Error -> throw Setup("The endpoint rejected or interrupted the GPT-Live session. Check the service type, API key, and Live endpoint.")
                    is LiveVoiceProtocol.Event.InputTranscript, is LiveVoiceProtocol.Event.Delegation ->
                        if(sessionStarted && !finishing.get()) onEvent(event)
                    LiveVoiceProtocol.Event.Ignore -> Unit
                }
            }
        } catch(error:Setup) {
            fail(error.message.orEmpty())
        } catch(_:Exception) {
            fail("The Live voice connection ended. Check the endpoint and network, then try again.")
        } finally { close() }
    }

    @SuppressLint("MissingPermission") // explicit UI permission gate; AudioRecord also enforces it
    private fun record() {
        var recorder:AudioRecord?=null
        var echo:AcousticEchoCanceler?=null
        val data=ByteArray(1920)
        try {
            if(closed.get()) return
            val rate=LiveVoiceProtocol.SAMPLE_RATE
            val minimum=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
            if(minimum<=0) throw IOException()
            val device=AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,rate,AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,maxOf(minimum,data.size*3))
            recorder=device
            capture.set(device)
            if(device.state!=AudioRecord.STATE_INITIALIZED) throw IOException()
            echo=runCatching {
                if(AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(device.audioSessionId)?.also {it.enabled=true}
                else null
            }.getOrNull()
            synchronized(audioGate) {
                if(closed.get() || finishing.get()) return
                device.startRecording()
                if(device.recordingState!=AudioRecord.RECORDSTATE_RECORDING) throw IOException()
            }
            while(!closed.get() && !finishing.get()) {
                val count=device.read(data,0,data.size,AudioRecord.READ_BLOCKING)
                if(closed.get() || finishing.get()) return
                if(count<0) throw IOException()
                val pcm=count and -2
                if(pcm>0) {
                    if(!playing.get() && !closed.get() && !finishing.get()) onLevel(level(data,pcm))
                    if(!closed.get() && !finishing.get()) socket.text(LiveVoiceProtocol.audio(data,pcm))
                }
            }
        } catch(_:Exception) {
            fail("Microphone capture or the voice connection stopped. Check microphone access and try again.")
        } finally {
            data.fill(0)
            runCatching {echo?.release()}
            runCatching {recorder?.stop()}
            capture.compareAndSet(recorder,null)
            runCatching {recorder?.release()}
        }
    }

    private fun play() {
        var track:AudioTrack?=null
        try {
            if(closed.get()) return
            val rate=LiveVoiceProtocol.SAMPLE_RATE
            val minimum=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)
            if(minimum<=0) throw IOException()
            val format=AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            val sink=AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum,4800)).build()
            track=sink
            playback.set(sink)
            if(sink.state!=AudioTrack.STATE_INITIALIZED) throw IOException()
            synchronized(audioGate) {
                if(closed.get() || finishing.get()) return
                sink.play()
            }
            while(!closed.get() && !finishing.get()) {
                val packet=chunks.poll(150)
                if(packet==null) {
                    if(playing.getAndSet(false)) update(LiveVoiceState.LISTENING)
                    continue
                }
                val data=packet.bytes
                try {
                    var sent=0
                    while(!closed.get() && !finishing.get() && sent<data.size) {
                        val count=synchronized(audioGate) {
                            if(!chunks.current(packet) || closed.get() || finishing.get()) -1
                            else {
                                if(!playing.getAndSet(true)) update(LiveVoiceState.SPEAKING)
                                sink.write(data,sent,data.size-sent,AudioTrack.WRITE_NON_BLOCKING)
                                    .also {if(it<0) throw IOException()}
                            }
                        }
                        if(count==-1) break
                        if(count==0) Thread.sleep(8)
                        else {
                            if(sent==0) onLevel(level(data,data.size))
                            sent+=count
                        }
                    }
                } finally {data.fill(0)}
            }
        } catch(_:Exception) {
            fail("Voice playback stopped. Check your audio output and try again.")
        } finally {
            runCatching {track?.pause()}
            runCatching {track?.flush()}
            playback.compareAndSet(track,null)
            runCatching {track?.release()}
        }
    }

    private fun level(data:ByteArray,size:Int):Float {
        var total=0.0
        var count=0
        for(i in 0 until size-1 step 8) {
            val value=((data[i].toInt() and 255) or (data[i+1].toInt() shl 8)).toShort().toDouble()/32768
            total+=value*value
            count++
        }
        return (sqrt(total/count.coerceAtLeast(1))*4).toFloat().coerceIn(0f,1f)
    }

    private fun worker(label:String,work:()->Unit)=Thread(work,label).apply {isDaemon=true;start()}

    private fun update(next:LiveVoiceState) {
        if(!closed.get() && !finishing.get() && state.getAndSet(next)!=next) onState(next)
    }

    private fun fail(message:String) {
        if(finishing.get()) return
        if(!closed.get()) onError(message)
        close()
    }

    fun commentary(id:String,text:String) {
        if(ready.get() && !closed.get() && !finishing.get()) {
            socket.text(LiveVoiceProtocol.commentary(id,text,"pocket-"+java.util.UUID.randomUUID()))
        } else throw IOException("Voice session is not active")
    }

    /** User Stop can finalize briefly; stale route/background must use force-close instead. */
    fun finish() {
        if(!ready.get()) {close();return}
        if(closed.get() || !finishing.compareAndSet(false,true)) return
        stopAudio()
        microphone.get()?.interrupt()
        player.get()?.interrupt()
        worker("pocket-live-close") {runCatching {socket.text("{\"type\":\"session.close\"}")}}
        worker("pocket-live-close-timeout") {
            Thread.sleep(2000)
            close()
        }
    }

    private fun clearPlayback(remoteEpoch:Long?) {
        synchronized(audioGate) {
            chunks.clear(remoteEpoch)
            playback.get()?.let {
                runCatching {it.pause();it.flush();if(!closed.get() && !finishing.get()) it.play()}
            }
            playing.set(false)
            onLevel(0f)
            update(LiveVoiceState.LISTENING)
        }
    }

    private fun stopAudio() {
        synchronized(audioGate) {
            runCatching {capture.get()?.stop()}
            runCatching {playback.get()?.pause()}
            runCatching {playback.get()?.flush()}
        }
    }

    override fun close() {
        if(!closed.compareAndSet(false,true)) return
        key.set(null)
        socket.close()
        stopAudio()
        reader.get()?.interrupt()
        microphone.get()?.interrupt()
        player.get()?.interrupt()
        chunks.clear()
        onState(LiveVoiceState.STOPPED)
    }

    private class Setup(message:String):IOException(message)
}
