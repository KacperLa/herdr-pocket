package dev.kaspy.herdr

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs

private const val CAPTURE_RUNNING = 0
private const val CAPTURE_FINISH = 1
private const val CAPTURE_CANCEL = 2
internal const val DICTATION_HZ = 16_000
internal const val MAX_DICTATION_MINUTES = 5
private const val MAX_PCM_BYTES = DICTATION_HZ * 2 * 60 * MAX_DICTATION_MINUTES

/** The only raw audio copy is a bounded buffer in process memory. */
internal class DictationPcm(val bytes: ByteArray, val length: Int, val peak: Int) : Closeable {
    fun copyForReplay() = DictationPcm(bytes.copyOf(length), length, peak)
    override fun close() { bytes.fill(0) }
}

/** One worker owns the platform recorder and releases it before any speech service starts. */
internal class DictationCapture(
    private val main: Executor,
    private val onStarted: () -> Unit,
    private val onComplete: (DictationPcm?, Boolean) -> Unit,
) {
    private val state = AtomicInteger(CAPTURE_RUNNING)

    fun start() { Thread(::capture, "pocket-dictation-capture").start() }
    fun finish() { state.compareAndSet(CAPTURE_RUNNING, CAPTURE_FINISH) }
    fun cancel() { state.set(CAPTURE_CANCEL) }

    @SuppressLint("MissingPermission") // Checked on the main thread before start; a revoked grant fails closed.
    private fun capture() {
        var audio: AudioRecord? = null
        var bytes: ByteArray? = null
        var size = 0
        var peak = 0
        val samples = ShortArray(2048)
        var success = false
        try {
            if (state.get() == CAPTURE_CANCEL) return
            val format = AudioFormat.ENCODING_PCM_16BIT
            val channel = AudioFormat.CHANNEL_IN_MONO
            val minimum = AudioRecord.getMinBufferSize(DICTATION_HZ, channel, format)
            if (minimum <= 0) return
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, DICTATION_HZ, channel, format,
                maxOf(minimum * 2, 8192),
            )
            audio = recorder
            if (recorder.state != AudioRecord.STATE_INITIALIZED || state.get() == CAPTURE_CANCEL) return
            val buffer = ByteArray(MAX_PCM_BYTES)
            bytes = buffer
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) return
            main.execute(onStarted)
            while (state.get() == CAPTURE_RUNNING && size < buffer.size) {
                val count = recorder.read(samples, 0, minOf(samples.size, (buffer.size - size) / 2),
                    AudioRecord.READ_NON_BLOCKING)
                if (count < 0) return
                if (count == 0) Thread.sleep(12)
                else for (i in 0 until count) {
                    val sample = samples[i].toInt()
                    peak = maxOf(peak, abs(sample))
                    buffer[size++] = sample.toByte()
                    buffer[size++] = (sample shr 8).toByte()
                }
            }
            success = size > 0
        } catch (_: RuntimeException) {
            // Includes a revoked permission, disconnected audio device, or unavailable mic.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            samples.fill(0)
            audio?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            val capped = size == MAX_PCM_BYTES
            val captured = bytes?.takeIf { success && state.get() != CAPTURE_CANCEL }?.let { DictationPcm(it, size, peak) }
            if (captured == null) bytes?.fill(0)
            main.execute {
                if (state.get() == CAPTURE_CANCEL) captured?.close()
                else onComplete(captured, capped)
            }
        }
    }
}

/** Feed the recorded PCM only after Stop. The pipe's EOF ends the segmented request. */
internal class DictationAudioPipe private constructor(
    val source: ParcelFileDescriptor,
    private val output: ParcelFileDescriptor,
    private val pcm: DictationPcm,
) : Closeable {
    private val reader = ParcelFileDescriptor.AutoCloseInputStream(source)
    @Volatile private var closed = false
    @Volatile private var wroteAllAudio = false
    private var started = false

    fun start(realTime: Boolean, main: Executor, onFailure: () -> Unit) {
        if (closed || started) return
        val worker = Thread({
            var success = false
            try {
                val fd = output.fileDescriptor
                // Never block indefinitely if a provider claims support but does not read the pipe.
                Os.fcntlInt(fd, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
                var sent = 0
                val chunk = 1024 // Exactly 32 ms of mono PCM at real-time speed.
                while (!closed && sent < pcm.length) {
                    val end = minOf(sent + chunk, pcm.length)
                    val nextChunk = System.nanoTime() + dictationReplayDelayNanos(end - sent, realTime)
                    while (!closed && sent < end) {
                        val count = try {
                            Os.write(fd, pcm.bytes, sent, end - sent)
                        } catch (e: ErrnoException) {
                            if (e.errno != OsConstants.EAGAIN && e.errno != OsConstants.EINTR) throw e
                            0
                        }
                        if (count == 0) Thread.sleep(5) else sent += count
                    }
                    // Wait even after the last frame so EOF does not race the tail of the recording.
                    while (!closed) {
                        val remaining = nextChunk - System.nanoTime()
                        if (remaining <= 0) break
                        LockSupport.parkNanos(remaining)
                    }
                }
                success = !closed && sent == pcm.length
                wroteAllAudio = success
            } catch (_: ErrnoException) {
            } catch (_: IOException) {
            } catch (_: RuntimeException) {
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                runCatching { output.close() }
                pcm.close()
                if (!success && !closed) main.execute(onFailure)
            }
        }, "pocket-dictation-audio-source")
        worker.start()
        started = true
    }

    fun consumedAllAudio(): Boolean = recordedDictationCanSubmit(
        wroteAllAudio, runCatching { reader.available() }.getOrNull(),
    )

    override fun close() {
        closed = true
        runCatching { reader.close() }
        if (!started) {
            runCatching { output.close() }
            pcm.close()
        }
    }

    companion object {
        fun open(pcm: DictationPcm): DictationAudioPipe {
            val (source, output) = ParcelFileDescriptor.createPipe()
            return DictationAudioPipe(source, output, pcm)
        }
    }
}
