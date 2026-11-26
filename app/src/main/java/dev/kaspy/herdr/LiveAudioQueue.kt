package dev.kaspy.herdr

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** The reader and player use the same gate when touching the device and queue epochs. */
internal class LiveAudioQueue {
    class Packet internal constructor(val bytes:ByteArray,internal val generation:Long)
    private val pending=ArrayBlockingQueue<Packet>(24)
    private var generation=0L
    private var latestRemoteEpoch:Long?=null

    @Synchronized fun offer(audio:LiveVoiceProtocol.Event.Audio):Boolean {
        val newest=latestRemoteEpoch
        if(newest!=null && audio.epoch!=null && audio.epoch<newest) {
            audio.bytes.fill(0)
            return true // Delayed audio from an already cleared turn.
        }
        if(!pending.offer(Packet(audio.bytes,generation))) {
            audio.bytes.fill(0)
            return false
        }
        return true
    }
    fun poll(timeoutMs:Long)=pending.poll(timeoutMs,TimeUnit.MILLISECONDS)
    @Synchronized fun current(packet:Packet)=packet.generation==generation

    @Synchronized fun clear(remoteEpoch:Long?=null) {
        val newest=latestRemoteEpoch
        if(remoteEpoch!=null && (newest==null || remoteEpoch>newest)) latestRemoteEpoch=remoteEpoch
        generation++
        while(true) {val packet=pending.poll() ?: break;packet.bytes.fill(0)}
    }
}
