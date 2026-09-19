package io.adhush.core

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext

/**
 * MQTT 3.1.1 in a hundred lines (ADR 0026), for Hisense VIDAA sets, which
 * take their remote commands over a broker in the television. CONNECT with
 * a name and password, SUBSCRIBE, PUBLISH at QoS 0 or 1, and a reader that
 * hands PUBLISH messages to the caller. TLS trusts the set's certificate on
 * the LAN only, and presents a client identity when the set demands one.
 */
class MiniMqtt(private val host: String, private val port: Int, private val tls: Boolean = true, private val identity: ClientIdentity? = null, private val timeoutMs: Int = 5000) : AutoCloseable {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var packetId = 1

    class Publish(val topic: String, val payload: ByteArray)

    fun connect(clientId: String, username: String?, password: String?, keepAliveS: Int = 60) {
        // Not inside apply: a Socket has a `port` of its own, and it would shadow ours.
        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), timeoutMs)
        plain.soTimeout = timeoutMs
        val s: Socket = if (tls) {
            val ctx: SSLContext = identity?.sslContext() ?: MiniHttp.trustAllContext()
            (ctx.socketFactory.createSocket(plain, host, port, true) as javax.net.ssl.SSLSocket).also { it.startHandshake() }
        } else plain
        socket = s; input = BufferedInputStream(s.getInputStream()); output = s.getOutputStream()
        var flags = 0x02   // clean session
        if (username != null) flags = flags or 0x80
        if (password != null) flags = flags or 0x40
        val body = string("MQTT") + byteArrayOf(4, flags.toByte(), (keepAliveS shr 8).toByte(), keepAliveS.toByte()) + string(clientId) + (username?.let { string(it) } ?: ByteArray(0)) + (password?.let { string(it) } ?: ByteArray(0))
        write(0x10, body)
        val (type, payload) = read() ?: throw ControlError("mqtt: no CONNACK")
        if (type != 0x20 || payload.size < 2) throw ControlError("mqtt: bad CONNACK")
        if (payload[1].toInt() != 0) throw ControlError("mqtt: connection refused, code ${payload[1]}")
    }

    fun subscribe(filter: String, qos: Int = 0) {
        val id = packetId++
        write(0x82, byteArrayOf((id shr 8).toByte(), id.toByte()) + string(filter) + byteArrayOf(qos.toByte()))
    }

    fun publish(topic: String, payload: ByteArray, qos: Int = 0) {
        val id = packetId++
        val head = string(topic) + if (qos > 0) byteArrayOf((id shr 8).toByte(), id.toByte()) else ByteArray(0)
        write(0x30 or (qos shl 1), head + payload)
    }

    /** The next PUBLISH, or null on timeout or close; acks and pings are handled inside. */
    fun next(waitMs: Int = timeoutMs): Publish? {
        socket?.soTimeout = waitMs
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            val (type, payload) = try { read() ?: return null } catch (e: java.net.SocketTimeoutException) { return null }
            when (type and 0xF0) {
                0x30 -> {
                    val qos = (type shr 1) and 3
                    val tlen = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                    val topic = String(payload, 2, tlen, Charsets.UTF_8)
                    var pos = 2 + tlen
                    if (qos > 0) { val id = ((payload[pos].toInt() and 0xFF) shl 8) or (payload[pos + 1].toInt() and 0xFF); pos += 2; write(0x40, byteArrayOf((id shr 8).toByte(), id.toByte())) }
                    return Publish(topic, payload.copyOfRange(pos, payload.size))
                }
                0xC0 -> write(0xD0, ByteArray(0))   // PINGREQ from an odd broker: PINGRESP
                else -> {}                            // SUBACK, PUBACK, PINGRESP: noted, not needed
            }
        }
        return null
    }

    fun ping() = write(0xC0, ByteArray(0))

    private fun write(type: Int, body: ByteArray) {
        val out = output ?: throw ControlError("mqtt not connected")
        val head = java.io.ByteArrayOutputStream(); head.write(type)
        var x = body.size; do { var b = x and 0x7F; x = x shr 7; if (x > 0) b = b or 0x80; head.write(b) } while (x > 0)
        synchronized(this) { out.write(head.toByteArray()); out.write(body); out.flush() }
    }

    private fun read(): Pair<Int, ByteArray>? {
        val i = input ?: return null
        val type = i.read(); if (type < 0) return null
        var mult = 1; var len = 0
        do { val b = i.read(); if (b < 0) return null; len += (b and 0x7F) * mult; mult *= 128 } while (b and 0x80 != 0)
        val body = ByteArray(len); var off = 0
        while (off < len) { val n = i.read(body, off, len - off); if (n < 0) return null; off += n }
        return Pair(type, body)
    }

    override fun close() { runCatching { write(0xE0, ByteArray(0)) }; runCatching { socket?.close() }; socket = null }

    companion object {
        fun string(s: String): ByteArray { val b = s.toByteArray(Charsets.UTF_8); return byteArrayOf((b.size shr 8).toByte(), b.size.toByte()) + b }
        /** The fixed header and remaining length of one packet, for the tests. */
        fun encode(type: Int, body: ByteArray): ByteArray { val head = java.io.ByteArrayOutputStream(); head.write(type); var x = body.size; do { var b = x and 0x7F; x = x shr 7; if (x > 0) b = b or 0x80; head.write(b) } while (x > 0); return head.toByteArray() + body }
    }
}
