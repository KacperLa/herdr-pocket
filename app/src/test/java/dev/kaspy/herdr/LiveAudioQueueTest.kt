package dev.kaspy.herdr

import org.junit.Assert.*
import org.junit.Test

class LiveAudioQueueTest {
    @Test fun interruptionInvalidatesInFlightClearsPendingAndDropsLateAudio() {
        val queue=LiveAudioQueue()
        val first=byteArrayOf(1,2)
        val pending=byteArrayOf(3,4)
        assertTrue(queue.offer(LiveVoiceProtocol.Event.Audio(first,1)))
        assertTrue(queue.offer(LiveVoiceProtocol.Event.Audio(pending,1)))
        val playing=queue.poll(0)!!
        assertTrue(queue.current(playing))
        queue.clear(2)
        assertFalse(queue.current(playing))
        assertArrayEquals(byteArrayOf(0,0),pending)
        assertNull(queue.poll(0))
        val late=byteArrayOf(5,6)
        assertTrue(queue.offer(LiveVoiceProtocol.Event.Audio(late,1)))
        assertArrayEquals(byteArrayOf(0,0),late)
        assertNull(queue.poll(0))
        val fresh=byteArrayOf(7,8)
        assertTrue(queue.offer(LiveVoiceProtocol.Event.Audio(fresh,2)))
        assertArrayEquals(fresh,queue.poll(0)!!.bytes)
    }
}
