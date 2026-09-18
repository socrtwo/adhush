package io.adhush.core

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.Base64

/**
 * A text-frame WebSocket client in a hundred lines (RFC 6455), for the two
 * brands whose sets speak it — Samsung's remote channel and LG's webOS —
 * without pulling a networking library into the app. Client frames are
 * masked as the RFC demands; pings are answered; fragments and binary
 * frames are ignored, since neither set sends them. TLS trusts the set's
 * own certificate ([MiniHttp.trustAllContext]) on the LAN only.
 */
class MiniWebSocket(private val url: String, private val timeoutMs: Int = 5000) : AutoCloseable {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val random = SecureRandom()

    fun connect(headers: Map<String, String> = emptyMap()) {
        val uri = URI(url)
        val secure = uri.scheme == "wss"
        val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
        val plain = Socket().apply { connect(InetSocketAddress(uri.host, port), timeoutMs); soTimeout = timeoutMs }
        val s: Socket = if (secure) MiniHttp.trustAllContext().socketFactory.createSocket(plain, uri.host, port, true).also { (it as javax.net.ssl.SSLSocket).startHandshake() } else plain
        socket = s
        input = BufferedInputStream(s.getInputStream())
        output = s.getOutputStream()
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also { random.nextBytes(it) })
        val path = (uri.rawPath.ifEmpty { "/" }) + (uri.rawQuery?.let { "?$it" } ?: "")
        val req = StringBuilder()
            .append("GET $path HTTP/1.1\r\n")
            .append("Host: ${uri.host}:$port\r\n")
            .append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
            .append("Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n")
        for ((k, v) in headers) req.append("$k: $v\r\n")
        req.append("\r\n")
        output!!.write(req.toString().toByteArray(Charsets.ISO_8859_1)); output!!.flush()
        val response = readHandshake(input!!)
        if (!response.startsWith("HTTP/1.1 101")) throw ControlError("websocket refused: ${response.lineSequence().firstOrNull()}")
    }

    private fun readHandshake(i: InputStream): String {
        val buf = StringBuilder()
        while (!buf.endsWith("\r\n\r\n")) {
            val b = i.read()
            if (b < 0) throw ControlError("websocket: connection closed during handshake")
            buf.append(b.toChar())
            if (buf.length > 16_384) throw ControlError("websocket: handshake too long")
        }
        return buf.toString()
    }

    fun send(text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        output?.let { it.write(frame(0x1, payload)); it.flush() } ?: throw ControlError("websocket not connected")
    }

    /** The next text message, or null on close or timeout. */
    fun receive(waitMs: Int = timeoutMs): String? {
        val s = socket ?: return null
        val i = input ?: return null
        s.soTimeout = waitMs
        try {
            while (true) {
                val b0 = i.read(); if (b0 < 0) return null
                val opcode = b0 and 0x0F
                val b1 = i.read(); if (b1 < 0) return null
                val masked = (b1 and 0x80) != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = ((i.read() shl 8) or i.read()).toLong()
                else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or i.read().toLong() } }
                val mask = if (masked) ByteArray(4).also { readFully(i, it) } else null
                val data = ByteArray(len.toInt()).also { readFully(i, it) }
                if (mask != null) for (k in data.indices) data[k] = (data[k].toInt() xor mask[k % 4].toInt()).toByte()
                when (opcode) {
                    0x1 -> return String(data, Charsets.UTF_8)
                    0x8 -> return null
                    0x9 -> output?.let { it.write(frame(0xA, data)); it.flush() }
                    else -> {}   // binary, pong, continuation: not spoken by a television
                }
            }
        } catch (e: java.net.SocketTimeoutException) { return null }
    }

    private fun readFully(i: InputStream, into: ByteArray) {
        var off = 0
        while (off < into.size) { val n = i.read(into, off, into.size - off); if (n < 0) throw ControlError("websocket: connection closed"); off += n }
    }

    /** A masked client frame, FIN set. */
    fun frame(opcode: Int, payload: ByteArray): ByteArray {
        val mask = ByteArray(4).also { random.nextBytes(it) }
        val head = ArrayList<Byte>()
        head.add((0x80 or opcode).toByte())
        when {
            payload.size < 126 -> head.add((0x80 or payload.size).toByte())
            payload.size < 65_536 -> { head.add((0x80 or 126).toByte()); head.add((payload.size shr 8).toByte()); head.add(payload.size.toByte()) }
            else -> { head.add((0x80 or 127).toByte()); for (k in 7 downTo 0) head.add((payload.size.toLong() shr (8 * k)).toByte()) }
        }
        for (b in mask) head.add(b)
        val body = ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        return head.toByteArray() + body
    }

    override fun close() { runCatching { output?.let { it.write(frame(0x8, ByteArray(0))); it.flush() } }; runCatching { socket?.close() }; socket = null; input = null; output = null }
}
