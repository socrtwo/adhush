package io.adhush.core

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

/**
 * Android TV / Google TV's remote protocol, version 2 (ADR 0026): Sony's
 * newer Bravias, TCL and Hisense with Google TV, Chromecast with Google TV,
 * Philips' Android sets, the Shield. Two TLS ports, both authenticating the
 * *client* by certificate ([ClientIdentity]): 6467 pairs once — the set
 * shows a six-character code and the secret is a hash over both sides'
 * RSA keys and the code — and 6466 carries the remote session, where the
 * set reports its volume, so this is a [VolumeDevice]: the level is read
 * from the set's own reports and set by stepping the volume keys.
 */
class AndroidTvRemote(private val host: String, private val identity: ClientIdentity, private val clientName: String = "AdHush", private val timeoutMs: Int = 8000) : VolumeDevice {
    private var socket: SSLSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var reader: Thread? = null
    @Volatile private var level: Int? = null
    @Volatile private var max = 100
    @Volatile private var muted = false
    @Volatile private var active = false
    override val maxVolume: Int get() = max
    val isMuted: Boolean get() = muted

    private fun open(port: Int): SSLSocket {
        val s = identity.sslContext().socketFactory.createSocket() as SSLSocket
        s.connect(java.net.InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        s.startHandshake()
        return s
    }

    private fun serverKey(s: SSLSocket): RSAPublicKey = s.session.peerCertificates[0].publicKey as RSAPublicKey

    // ---- pairing, port 6467 ----

    /** Pairs once. [code] is asked for when the set shows its six characters; returns false when the set refused. */
    fun pair(code: () -> String?): Boolean {
        open(6467).use { s ->
            val i = BufferedInputStream(s.getInputStream()); val o = s.getOutputStream()
            fun send(w: MiniProto.Writer) { o.write(MiniProto.framed(w.toByteArray())); o.flush() }
            fun recv(): MiniProto.Message { val f = MiniProto.readFrame(i) ?: throw ControlError("android tv: the set closed the pairing"); val m = MiniProto.parse(f); val status = m.int(2) ?: 200; if (status != 200) throw ControlError("android tv: pairing status $status"); return m }
            send(pairing().message(10, MiniProto.Writer().string(1, "androidtvremote").string(2, clientName)))
            recv()   // request ack
            send(pairing().message(20, MiniProto.Writer().message(2, encoding()).int(3, 1)))
            recv()   // the set's own options
            send(pairing().message(30, MiniProto.Writer().message(1, encoding()).int(2, 1)))
            recv()   // configuration ack: the code is on the screen now
            val typed = code()?.trim()?.uppercase() ?: return false
            if (typed.length != 6) throw ControlError("android tv: the code has six characters")
            val secret = secret(identity.publicKey, serverKey(s), typed)
            send(pairing().message(40, MiniProto.Writer().bytes(1, secret)))
            val ack = recv()
            return ack.has(41)
        }
    }

    private fun pairing() = MiniProto.Writer().int(1, 2).int(2, 200)
    private fun encoding() = MiniProto.Writer().int(1, 3).int(2, 6)   // hexadecimal, six symbols

    // ---- the session, port 6466 ----

    fun connect() {
        close()
        val s = open(6466)
        socket = s; input = BufferedInputStream(s.getInputStream()); output = s.getOutputStream()
        val t = Thread({ readLoop() }, "adhush-androidtv").apply { isDaemon = true }
        reader = t; t.start()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!active && System.currentTimeMillis() < deadline) Thread.sleep(50)
        if (!active) throw ControlError("android tv: the set did not open the session (paired?)")
    }

    private fun send(w: MiniProto.Writer) { val o = output ?: throw ControlError("android tv: not connected"); synchronized(o) { o.write(MiniProto.framed(w.toByteArray())); o.flush() } }

    private fun readLoop() {
        val i = input ?: return
        try {
            while (true) {
                val frame = MiniProto.readFrame(i) ?: break
                val m = MiniProto.parse(frame)
                when {
                    m.has(1) -> send(MiniProto.Writer().message(1, MiniProto.Writer().int(1, 622).message(2, MiniProto.Writer().string(1, "AdHush").string(2, "AdHush").int(3, 1).string(4, "1").string(5, "io.adhush").string(6, "1.0"))))
                    m.has(2) -> { send(MiniProto.Writer().message(2, MiniProto.Writer().int(1, 622))); active = true }
                    m.has(8) -> send(MiniProto.Writer().message(9, MiniProto.Writer().int(1, m.message(8)?.int(1) ?: 0)))
                    m.has(50) -> m.message(50)?.let { v -> v.int(6)?.let { if (it > 0) max = it }; v.int(7)?.let { level = it }; muted = (v.long(8) ?: 0L) != 0L }
                    else -> {}
                }
            }
        } catch (e: Exception) { /* the socket closed: connect() again next time */ }
        active = false
    }

    fun key(code: Int) { send(MiniProto.Writer().message(10, MiniProto.Writer().int(1, code).int(2, 3))) }

    fun press(key: RemoteKey): Boolean { val code = REMOTE[key] ?: return false; key(code); return true }

    override fun getVolume(): Int? { if (!active) runCatching { connect() }; return level }

    /** Stepped from the set's last report; the report after the steps confirms. */
    override fun setVolume(level: Int): Boolean {
        if (!active) connect()
        val from = this.level ?: return false
        val target = level.coerceIn(0, max)
        val steps = target - from
        val code = if (steps > 0) KEY_VOLUME_UP else KEY_VOLUME_DOWN
        repeat(kotlin.math.abs(steps)) { key(code); Thread.sleep(PRESS_GAP_MS) }
        return true
    }

    override fun setMute(on: Boolean): Boolean { if (!active) connect(); if (muted != on) key(KEY_VOLUME_MUTE); return true }

    override fun close() { runCatching { socket?.close() }; socket = null; input = null; output = null; active = false }

    companion object {
        const val PRESS_GAP_MS = 60L
        const val KEY_VOLUME_UP = 24
        const val KEY_VOLUME_DOWN = 25
        const val KEY_VOLUME_MUTE = 164
        val REMOTE: Map<RemoteKey, Int> = mapOf(
            RemoteKey.POWER to 26, RemoteKey.VOL_UP to 24, RemoteKey.VOL_DOWN to 25, RemoteKey.MUTE to 164,
            RemoteKey.UP to 19, RemoteKey.DOWN to 20, RemoteKey.LEFT to 21, RemoteKey.RIGHT to 22, RemoteKey.ENTER to 23,
            RemoteKey.RETURN to 4, RemoteKey.EXIT to 3, RemoteKey.MENU to 82, RemoteKey.SMART to 3, RemoteKey.INPUT to 178,
            RemoteKey.CH_UP to 166, RemoteKey.CH_DOWN to 167, RemoteKey.DISPLAY to 165, RemoteKey.CC to 175,
            RemoteKey.DIGIT_0 to 7, RemoteKey.DIGIT_1 to 8, RemoteKey.DIGIT_2 to 9, RemoteKey.DIGIT_3 to 10, RemoteKey.DIGIT_4 to 11,
            RemoteKey.DIGIT_5 to 12, RemoteKey.DIGIT_6 to 13, RemoteKey.DIGIT_7 to 14, RemoteKey.DIGIT_8 to 15, RemoteKey.DIGIT_9 to 16,
            RemoteKey.PLAY to 126, RemoteKey.PAUSE to 127, RemoteKey.STOP to 86, RemoteKey.REW to 89, RemoteKey.FF to 90,
            RemoteKey.RED to 183, RemoteKey.GREEN to 184, RemoteKey.YELLOW to 185, RemoteKey.BLUE to 186,
        )

        /** SHA-256 over the client's modulus and exponent, the set's modulus and exponent, and the last four hex characters of the code. */
        fun secret(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(ClientIdentity.magnitude(client.modulus)); md.update(ClientIdentity.magnitude(client.publicExponent))
            md.update(ClientIdentity.magnitude(server.modulus)); md.update(ClientIdentity.magnitude(server.publicExponent))
            md.update(hex(code.substring(2)))
            return md.digest()
        }

        fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

        /** Is something listening on the pairing port? */
        fun probe(host: String, timeoutMs: Int = 400): Boolean = try { java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, 6467), timeoutMs) }; true } catch (e: Exception) { false }
    }
}
