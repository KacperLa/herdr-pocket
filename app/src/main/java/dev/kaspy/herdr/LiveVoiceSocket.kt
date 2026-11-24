package dev.kaspy.herdr

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Narrow RFC 6455 text client; no redirects, extensions, cookies, persistence, or certificate override. */
internal class LiveVoiceSocket:AutoCloseable {
    private val closed=AtomicBoolean()
    private val transport=AtomicReference<Socket?>()
    private val random=SecureRandom()
    private var input:InputStream?=null
    private var output:OutputStream?=null

    fun connect(endpoint:URI,ticket:String) {
        require(LiveVoiceProtocol.endpoint(endpoint.toASCIIString())==endpoint && LiveVoiceProtocol.validTicket(ticket))
        val host=endpoint.host.removeSurrounding("[","]")
        val port=if(endpoint.port==-1) 443 else endpoint.port
        val raw=Socket()
        transport.set(raw)
        if(closed.get()) {raw.close();throw EOFException()}
        try {
            raw.connect(InetSocketAddress(host,port),10_000)
            val socket=(SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw,host,port,true) as SSLSocket
            if(!transport.compareAndSet(raw,socket) || closed.get()) {socket.close();throw EOFException()}
            socket.soTimeout=15_000
            socket.sslParameters=socket.sslParameters.apply {endpointIdentificationAlgorithm="HTTPS"}
            socket.startHandshake()
            val inbound=BufferedInputStream(socket.inputStream)
            val outbound=BufferedOutputStream(socket.outputStream)
            val nonce=Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
            val request="GET ${endpoint.rawPath} HTTP/1.1\r\nHost: ${endpoint.rawAuthority}\r\n"+
                "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $nonce\r\n"+
                "Sec-WebSocket-Version: 13\r\nAuthorization: Bearer $ticket\r\n\r\n"
            outbound.write(request.toByteArray(Charsets.US_ASCII))
            outbound.flush()
            val headers=readHandshake(inbound)
            if(!validHandshake(headers,nonce)) throw IOException("WebSocket upgrade rejected")
            input=inbound
            output=outbound
        } catch(error:Exception) {close();throw error}
    }

    fun allowSessionIdle() {transport.get()?.soTimeout=120_000}

    fun text(value:String)=send(1,value.toByteArray(Charsets.UTF_8))
    private fun send(opcode:Int,data:ByteArray) {
        synchronized(this) {
            if(closed.get()) throw EOFException()
            clientFrame(output ?: throw EOFException(),opcode,data,ByteArray(4).also(random::nextBytes))
        }
    }

    fun read():String? {
        val source=input ?: throw EOFException()
        var fragments:ByteArrayOutputStream?=null
        var pieces=0
        while(!closed.get()) {
            val frame=readFrame(source)
            when(frame.opcode) {
                8 -> {if(frame.data.size==1) throw IOException("Invalid close frame");return null}
                9 -> send(10,frame.data)
                10 -> continue
                1 -> {
                    if(fragments!=null) throw IOException("Invalid fragment sequence")
                    if(frame.final) return utf8(frame.data)
                    fragments=ByteArrayOutputStream().also { it.write(frame.data) }
                }
                0 -> {
                    val pending=fragments ?: throw IOException("Unexpected continuation")
                    if(++pieces>4096 || pending.size()+frame.data.size>LiveVoiceProtocol.MAX_MESSAGE)
                        throw IOException("Oversized WebSocket message")
                    pending.write(frame.data)
                    if(frame.final) return utf8(pending.toByteArray())
                }
                else -> throw IOException("Unsupported WebSocket frame")
            }
        }
        return null
    }

    override fun close() {
        closed.set(true)
        transport.getAndSet(null)?.let {runCatching {it.close()}}
    }

    internal data class Frame(val final:Boolean,val opcode:Int,val data:ByteArray)

    companion object {
        private const val GUID="258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private fun byte(source:InputStream)=source.read().also {if(it<0) throw EOFException()}
        private fun utf8(bytes:ByteArray)=Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()

        internal fun readHandshake(source:InputStream):String {
            val bytes=ByteArrayOutputStream()
            var ending=0
            while(bytes.size()<16*1024) {
                val b=byte(source)
                if(b>127 || b<32 && b !in listOf(9,10,13)) throw IOException("Invalid upgrade headers")
                bytes.write(b)
                ending=(ending shl 8) or b
                if(ending==0x0d0a0d0a) return bytes.toString("US-ASCII")
            }
            throw IOException("Oversized upgrade headers")
        }

        internal fun validHandshake(response:String,nonce:String):Boolean {
            val lines=response.removeSuffix("\r\n\r\n").split("\r\n")
            if(!Regex("HTTP/1\\.1 101(?: .*)?").matches(lines.firstOrNull().orEmpty())) return false
            val headers=lines.drop(1).map {line ->
                val pos=line.indexOf(':')
                if(pos<=0 || line.first().isWhitespace()) return false
                line.substring(0,pos).lowercase(Locale.ROOT) to line.substring(pos+1).trim()
            }.groupBy({it.first},{it.second})
            fun includes(header:String,value:String)=headers[header].orEmpty().any { line ->
                line.split(',').any {it.trim().equals(value,ignoreCase=true)}
            }
            val expected=Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((nonce+GUID).toByteArray(Charsets.US_ASCII)))
            return includes("upgrade","websocket") && includes("connection","upgrade") &&
                headers["sec-websocket-accept"]?.singleOrNull()==expected &&
                !headers.containsKey("sec-websocket-extensions") && !headers.containsKey("sec-websocket-protocol")
        }

        internal fun readFrame(source:InputStream):Frame {
            val first=byte(source)
            val second=byte(source)
            if(first and 0x70!=0 || second and 0x80!=0) throw IOException("Invalid WebSocket frame")
            val final=first and 0x80!=0
            val opcode=first and 15
            val small=second and 127
            val size=when(small) {
                126 -> ((byte(source) shl 8) or byte(source)).also {if(it<126) throw IOException()}
                127 -> {
                    var length=0L
                    repeat(8) {length=(length shl 8) or byte(source).toLong()}
                    if(length !in 65_536L..LiveVoiceProtocol.MAX_MESSAGE.toLong()) throw IOException()
                    length.toInt()
                }
                else -> small
            }
            if(opcode>=8 && (!final || size>125)) throw IOException("Invalid control frame")
            val data=ByteArray(size)
            var at=0
            while(at<size) {
                val count=source.read(data,at,size-at)
                if(count<=0) throw EOFException()
                at+=count
            }
            return Frame(final,opcode,data)
        }

        internal fun clientFrame(output:OutputStream,opcode:Int,data:ByteArray,key:ByteArray) {
            require(data.size<=LiveVoiceProtocol.MAX_MESSAGE && key.size==4 && (opcode<8 || data.size<=125))
            output.write(0x80 or opcode)
            val size=data.size
            when {
                size<126 -> output.write(0x80 or size)
                size<=65535 -> { output.write(254);output.write(size ushr 8);output.write(size and 255) }
                else -> {
                    output.write(255)
                    for(i in 7 downTo 0) output.write(((size.toLong() ushr (i*8)) and 255).toInt())
                }
            }
            output.write(key)
            val masked=ByteArray(size) {index -> (data[index].toInt() xor key[index%4].toInt()).toByte()}
            output.write(masked)
            output.flush()
        }
    }
}
