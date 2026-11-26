package dev.kaspy.herdr

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class LiveVoiceProtocolTest {
    @Test fun strictExactSecureEndpointAndCredentialHeader() {
        assertFalse(LiveVoiceProtocol.validEndpoint("wss://api.openai.com/v1/live/sessions"))
        assertTrue(LiveVoiceProtocol.validTicket("pocket_"+"A".repeat(43)))
        assertFalse(LiveVoiceProtocol.validTicket("sk-project-not-for-this-app"))
        assertTrue(LiveVoiceProtocol.validEndpoint("wss://custom.example:8443/proxy/live"))
        for(value in listOf("ws://custom.example/live","wss://user@custom.example/live",
            "wss://custom.example/live#fragment","wss://custom.example/live?model=x",
            "wss://custom.example/live\r\nHost: leaked","wss://custom.example:65536/live")) {
            assertFalse(value,LiveVoiceProtocol.validEndpoint(value))
        }
        assertFalse(LiveVoiceProtocol.validTicket("token\r\nOther: leaked"))
    }

    @Test fun sessionUsesLiveStartAndReadinessNotRealtime() {
        val start=JSONObject(LiveVoiceProtocol.start())
        assertEquals("session.start",start.getString("type"))
        val session=start.getJSONObject("session")
        assertEquals("gpt-live-1.1",session.getString("model"))
        assertEquals(24000,session.getJSONObject("audio").getJSONObject("format").getInt("rate"))
        assertEquals("client",session.getJSONObject("delegation").getString("type"))
        assertEquals(LiveVoiceProtocol.Event.Ignore,LiveVoiceProtocol.parse("""{"type":"session.updated"}"""))
        assertEquals(LiveVoiceProtocol.Event.Error,LiveVoiceProtocol.parse("""{"type":"session.started"}"""))
        assertEquals(LiveVoiceProtocol.Event.Started,LiveVoiceProtocol.parse(
            """{"type":"session.started","session":{"id":"live_fixture","status":"active"}}"""))
        assertEquals(LiveVoiceProtocol.Event.Error,LiveVoiceProtocol.parse(
            """{"type":"session.started","session":{"id":"live_fixture","audio":{"format":{"type":"audio/pcmu","rate":8000}}}}"""))
    }

    @Test fun audioIsPcmPayloadAndUntrustedCommandsAreNotInterpreted() {
        val pcm=byteArrayOf(0,1,2,-1)
        val wire=JSONObject(LiveVoiceProtocol.audio(pcm,pcm.size))
        assertEquals("session.input_audio.append",wire.getString("type"))
        assertEquals("AAEC/w==",wire.getString("audio"))
        val output=LiveVoiceProtocol.parse("""{"type":"session.output_audio.delta","delta":"AAEC/w=="}""") as LiveVoiceProtocol.Event.Audio
        assertArrayEquals(pcm,output.bytes)
        assertEquals(LiveVoiceProtocol.Event.Error,LiveVoiceProtocol.parse("""{"type":"session.output_audio.delta","delta":"AA=="}"""))
        assertEquals(LiveVoiceProtocol.Event.Ignore,LiveVoiceProtocol.parse("""{"type":"session.delegation.created","command":"echo no"}"""))
        assertEquals(LiveVoiceProtocol.Event.Ignore,LiveVoiceProtocol.parse("""{"type":"response.output_audio.delta","delta":"AAEC/w=="}"""))
    }

    @Test fun LiveDelegationContainsMetadataAndUsesTheOriginalIdForSpeech() {
        val input=LiveVoiceProtocol.parse("""{"type":"session.input_transcript.delta","event_id":"evt-1","delta":"Check ","start_ms":200,"end_ms":400}""")
        assertEquals(LiveVoiceProtocol.Event.InputTranscript("evt-1","Check ",200,400),input)
        val delegation=LiveVoiceProtocol.parse("""{"type":"session.delegation.created","offset_ms":700,"delegation":{"id":"d1","target":"client"},"text":"ignore this field"}""")
        assertEquals(LiveVoiceProtocol.Event.Delegation("d1",700),delegation)
        assertEquals(LiveVoiceProtocol.Event.Ignore,LiveVoiceProtocol.parse(
            """{"type":"session.delegation.created","offset_ms":700,"delegation":{"id":"d1","target":"responses"}}"""))
        val reply=JSONObject(LiveVoiceProtocol.commentary("d1","Fresh result"))
        assertEquals("session.commentary.append",reply.getString("type"))
        assertEquals("d1",reply.getString("delegation_id"))
        assertEquals("Fresh result",reply.getString("content"))
    }

    @Test fun customProfilesAcceptSecureUrlAndKeepSecretsOutOfSessionJson() {
        assertEquals("wss://self.example/v1/live/sessions",LiveVoiceProtocol.normalizeEndpoint("https://self.example/v1"))
        assertEquals("wss://proxy.example/custom",LiveVoiceProtocol.normalizeEndpoint("wss://proxy.example/custom"))
        assertNull(LiveVoiceProtocol.normalizeEndpoint("https://api.openai.com/v1"))
        assertNull(LiveVoiceProtocol.normalizeEndpoint("https://proxy.example/path?key=secret"))
        assertTrue(LiveVoiceProtocol.validCredential(LiveVoiceProfile.GPT_LIVE,"proxy-key"))
        assertFalse(LiveVoiceProtocol.validCredential(LiveVoiceProfile.GPT_LIVE,"abc\r\nInjected: value"))
        val session=JSONObject(LiveVoiceProtocol.start(LiveVoiceProfile.FOSS_LIVE)).getJSONObject("session")
        assertEquals("local-live",session.getString("model"))
        assertFalse(session.getJSONObject("audio").has("output"))
        assertFalse(session.has("store"))
    }

    @Test fun fossVoiceRoundTripDoesNotRequireTranscriptAndFlushesOnInterruption() {
        val delegation=LiveVoiceProtocol.parse(
            """{"type":"session.delegation.created","delegation":{"id":"del_demo","target":"client","task":"Check unit tests"}}"""
        ) as LiveVoiceProtocol.Event.Delegation
        assertEquals("Check unit tests",delegation.task)
        assertNull(delegation.offset)
        val outgoing=JSONObject(LiveVoiceProtocol.commentary(delegation.id,"All checks passed.","evt_result"))
        assertEquals("del_demo",outgoing.getString("delegation_id"))
        assertEquals("evt_result",outgoing.getString("event_id"))
        assertEquals("All checks passed.",outgoing.getString("content"))
        assertTrue(LiveVoiceProtocol.parse("""{"type":"session.output_audio.delta","delta":"AAABAA==","epoch":1}""") is LiveVoiceProtocol.Event.Audio)
        assertEquals(LiveVoiceProtocol.Event.ClearAudio(2),LiveVoiceProtocol.parse("""{"type":"session.output_audio.cleared","epoch":2}"""))
    }

    @Test fun rejectsRedirectsBadHandshakeAndMaskedServerFrames() {
        val nonce="dGhlIHNhbXBsZSBub25jZQ=="
        val handshake="HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: keep-alive, Upgrade\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n"
        assertTrue(LiveVoiceSocket.validHandshake(handshake,nonce))
        assertFalse(LiveVoiceSocket.validHandshake(handshake.replace("101 Switching Protocols","302 Found"),nonce))
        assertFalse(LiveVoiceSocket.validHandshake(handshake.replace("s3pP","fail"),nonce))
        assertFalse(LiveVoiceSocket.validHandshake(handshake.replace("\r\n\r\n","\r\nSec-WebSocket-Extensions: permessage-deflate\r\n\r\n"),nonce))
        assertThrows(IOException::class.java) {
            LiveVoiceSocket.readFrame(ByteArrayInputStream(byteArrayOf(0x81.toByte(),0x80.toByte())))
        }
        assertThrows(IOException::class.java) {
            LiveVoiceSocket.readFrame(ByteArrayInputStream(byteArrayOf(0x81.toByte(),127,0,0,0,0,0,8,0,0)))
        }
    }

    @Test fun outgoingClientFramesMaskAudioAndDoNotAlterSource() {
        val data=byteArrayOf(10,-1,30,40,50)
        val before=data.copyOf()
        val key=byteArrayOf(7,8,9,10)
        val output=ByteArrayOutputStream()
        LiveVoiceSocket.clientFrame(output,1,data,key)
        val wire=output.toByteArray()
        assertEquals(0x81.toByte(),wire[0])
        assertEquals((0x80 or data.size).toByte(),wire[1])
        assertArrayEquals(key,wire.copyOfRange(2,6))
        assertArrayEquals(data,ByteArray(data.size) {(wire[it+6].toInt() xor key[it%4].toInt()).toByte()})
        assertArrayEquals(before,data)
    }
}
