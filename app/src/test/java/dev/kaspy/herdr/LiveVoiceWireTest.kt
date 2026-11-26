package dev.kaspy.herdr

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory

/** Uses freshly generated TLS material and synthetic responses; never contacts a real service. */
class LiveVoiceWireTest {
    @Test fun tlsVoiceAndExtractorRoundTrip() {
        val dir=Files.createTempDirectory("pocket-live-fixture")
        val executor=Executors.newSingleThreadExecutor()
        val defaultContext=SSLContext.getDefault()
        val defaultHttps=HttpsURLConnection.getDefaultSSLSocketFactory()
        try {
            val path=dir.resolve("fixture.p12")
            val keytool=Path.of(System.getProperty("java.home"),"bin","keytool")
            val password="fixture-only".toCharArray()
            val process=ProcessBuilder(keytool.toString(),"-genkeypair","-alias","localhost","-keyalg","EC",
                "-groupname","secp256r1","-dname","CN=localhost","-ext","SAN=dns:localhost",
                "-validity","1","-storetype","PKCS12","-keystore",path.toString(),"-storepass",String(password),
                "-noprompt").redirectErrorStream(true).start()
            assertTrue("keytool timed out",process.waitFor(15,TimeUnit.SECONDS))
            assertEquals(process.inputStream.readBytes().decodeToString(),0,process.exitValue())
            val store=KeyStore.getInstance("PKCS12")
            Files.newInputStream(path).use {store.load(it,password)}
            val certs=KeyStore.getInstance("PKCS12").apply {load(null,null);setCertificateEntry("local",store.getCertificate("localhost"))}
            val kms=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {init(store,password)}
            val tms=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {init(certs)}
            val server=SSLContext.getInstance("TLS").apply {init(kms.keyManagers,null,null)}
            val client=SSLContext.getInstance("TLS").apply {init(null,tms.trustManagers,null)}
            SSLContext.setDefault(client)
            HttpsURLConnection.setDefaultSSLSocketFactory(client.socketFactory)
            val listener=server.serverSocketFactory.createServerSocket(0,5,InetAddress.getLoopbackAddress()) as SSLServerSocket
            listener.use { serverSocket ->
                serverSocket.soTimeout=10_000
                val serve=executor.submit {
                    serverSocket.accept().use { peer ->
                        peer.soTimeout=8000
                        val input=peer.getInputStream()
                        val output=peer.getOutputStream()
                        val headers=LiveVoiceSocket.readHandshake(input).lines()
                        assertEquals("GET /v1/live/sessions HTTP/1.1",headers.first())
                        assertTrue(headers.any {it=="Authorization: Bearer voice-fixture"})
                        val nonce=headers.first {it.startsWith("Sec-WebSocket-Key: ")}.substringAfter(": ")
                        val accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(
                            (nonce+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                        output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                            "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
                        output.flush()
                        val start=readClient(input)
                        assertEquals("local-live",start.getJSONObject("session").getString("model"))
                        assertFalse(start.toString().contains("voice-fixture"))
                        frame(output,"""{"type":"session.started","session":{"id":"fixture","status":"active"}}""")
                        assertEquals("session.input_audio.append",readClient(input).getString("type"))
                        frame(output,"""{"type":"session.delegation.created","delegation":{"id":"del_1","target":"client","task":"Check unit tests"}}""")
                        val result=readClient(input)
                        assertEquals("del_1",result.getString("delegation_id"))
                        assertEquals("Seven checks passed.",result.getString("content"))
                        frame(output,"""{"type":"session.output_audio.delta","delta":"AAABAA==","epoch":1}""")
                        frame(output,"""{"type":"session.output_audio.cleared","epoch":2}""")
                    }
                    for(content in listOf("""{"kind":"completion"}""",
                        """{"speech":"Seven checks passed.","evidence":"All seven checks passed."}""",
                        """{"kind":"none"}""")) {
                        serverSocket.accept().use { peer ->
                            peer.soTimeout=8000
                            val input=peer.getInputStream()
                            val headers=LiveVoiceSocket.readHandshake(input).lines()
                            assertEquals("POST /v1/chat/completions HTTP/1.1",headers.first())
                            assertTrue(headers.any {it.equals("Authorization: Bearer extraction-fixture",ignoreCase=true)})
                            val length=headers.first {it.startsWith("Content-Length: ",true)}.substringAfter(": ").toInt()
                            val message=JSONObject(input.readNBytes(length).decodeToString())
                            assertEquals("fixture-model",message.getString("model"))
                            assertEquals("none",message.getString("reasoning_effort"))
                            assertFalse(message.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
                            assertFalse(message.has("tools"))
                            val terminal=JSONObject(message.getJSONArray("messages").getJSONObject(1).getString("content"))
                            if(content=="""{"kind":"none"}""") {
                                assertEquals("Codex · idle\n\n› Ask Codex to make a change",terminal.getString("screen"))
                                assertFalse(terminal.getBoolean("completed_lifecycle"))
                                assertFalse(terminal.has("before"))
                                assertFalse(terminal.has("submitted"))
                                assertEquals(LiveTurnExtraction.READINESS,
                                    message.getJSONArray("messages").getJSONObject(0).getString("content"))
                            } else assertEquals("All seven checks passed.",terminal.getString("screen"))
                            val data=JSONObject().put("choices",JSONArray().put(JSONObject().put("finish_reason","stop").put(
                                "message",JSONObject().put("content",content)))).toString().toByteArray()
                            peer.getOutputStream().apply {
                                write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n"+
                                    "Content-Length: ${data.size}\r\n\r\n").toByteArray())
                                write(data);flush()
                            }
                        }
                    }
                }
                val base="localhost:${serverSocket.localPort}"
                LiveVoiceSocket().use { voice ->
                    voice.connect(LiveVoiceProtocol.endpoint("wss://$base/v1/live/sessions")!!,"voice-fixture")
                    voice.text(LiveVoiceProtocol.start(LiveVoiceProfile.FOSS_LIVE))
                    assertEquals(LiveVoiceProtocol.Event.Started,LiveVoiceProtocol.parse(voice.read()!!))
                    voice.text(LiveVoiceProtocol.audio(byteArrayOf(1,2),2))
                    val event=LiveVoiceProtocol.parse(voice.read()!!) as LiveVoiceProtocol.Event.Delegation
                    assertEquals("Check unit tests",event.task)
                    assertNull(event.offset)
                    voice.text(LiveVoiceProtocol.commentary(event.id,"Seven checks passed.","evt_result"))
                    assertTrue(LiveVoiceProtocol.parse(voice.read()!!) is LiveVoiceProtocol.Event.Audio)
                    assertEquals(LiveVoiceProtocol.Event.ClearAudio(2),LiveVoiceProtocol.parse(voice.read()!!))
                }
                val diagnostics=LiveDiagnostics()
                val extraction=LiveExtractionClient.create("https://$base/v1/chat/completions","fixture-model","extraction-fixture",
                    disableThinking=true,diagnostics=diagnostics)
                val result=runBlocking {extraction.inspect("Previous reply","All seven checks passed.","Check unit tests",true)}
                assertEquals("Seven checks passed.",result.draft)
                assertNull(runBlocking {
                    LiveExtractionClient.check("https://$base/v1/chat/completions","fixture-model","extraction-fixture",
                        disableThinking=true,diagnostics=diagnostics)
                })
                serve.get(10,TimeUnit.SECONDS)
                val report=diagnostics.text()
                assertTrue(report.contains("origin=live_call stage=detect"))
                assertTrue(report.contains("origin=live_call stage=completion"))
                assertTrue(report.contains("origin=settings_test stage=readiness"))
                assertTrue(report.contains("thinking_off_requested=true http=200"))
                assertFalse(report.contains("extraction-fixture"))
                assertFalse(report.contains("fixture-model"))
                assertFalse(report.contains("localhost"))
                assertFalse(report.contains("All seven checks passed."))
            }
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(defaultHttps)
            SSLContext.setDefault(defaultContext)
            executor.shutdownNow()
            dir.toFile().deleteRecursively()
        }
    }

    private fun readClient(input:InputStream):JSONObject {
        assertEquals(0x81,input.read())
        val size=input.read()
        assertTrue(size and 0x80!=0)
        val n=when(size and 127) {126 -> (input.read() shl 8) or input.read(); 127 -> error("Oversized sample"); else -> size and 127}
        val mask=input.readNBytes(4)
        val bytes=input.readNBytes(n)
        for(i in bytes.indices) bytes[i]=(bytes[i].toInt() xor mask[i%4].toInt()).toByte()
        return JSONObject(bytes.decodeToString())
    }

    private fun frame(output:OutputStream,message:String) {
        val bytes=message.toByteArray()
        output.write(0x81)
        if(bytes.size<126) output.write(bytes.size)
        else {output.write(126);output.write(bytes.size ushr 8);output.write(bytes.size and 255)}
        output.write(bytes)
        output.flush()
    }
}
