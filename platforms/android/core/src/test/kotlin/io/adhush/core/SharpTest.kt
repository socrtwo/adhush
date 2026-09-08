package io.adhush.core

import java.net.ServerSocket
import java.nio.charset.StandardCharsets.US_ASCII
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A fake AQUOS set on loopback: prompts for login, then answers one framed command. */
private class FakeTv(private val login: Pair<String, String>? = null, private val volumeQueryAnswers: Boolean = true) : AutoCloseable {
    val server = ServerSocket(0)
    val port get() = server.localPort
    val received = ArrayList<String>()
    var volume = 20
    var muted = false
    private val worker = thread(isDaemon = true) {
        try {
            while (true) {
                val sock = server.accept()
                sock.soTimeout = 2000
                val input = sock.getInputStream(); val out = sock.getOutputStream()
                val buf = ByteArray(256)
                if (login != null) {
                    out.write("Login:".toByteArray()); out.flush()
                    val id = readLine(input)
                    out.write("Password:".toByteArray()); out.flush()
                    val pw = readLine(input)
                    if (id != login.first || pw != login.second) { out.write("Login incorrect\r\n".toByteArray()); out.flush(); sock.close(); continue }
                    out.write("\r\n".toByteArray()); out.flush()
                }
                val n = input.read(buf)
                val cmd = String(buf, 0, n, US_ASCII)
                synchronized(received) { received.add(cmd) }
                val reply = when {
                    cmd == "VOLM?   \r" -> if (volumeQueryAnswers) "$volume\r\n" else "ERR\r\n"
                    cmd.startsWith("VOLM") -> { volume = cmd.substring(4, 8).trim().toInt(); "OK\r\n" }
                    cmd == "MUTE1   \r" -> { muted = true; "OK\r\n" }
                    cmd == "MUTE2   \r" -> { muted = false; "OK\r\n" }
                    cmd == "MUTE?   \r" -> if (muted) "1\r\n" else "2\r\n"
                    else -> "ERR\r\n"
                }
                out.write(reply.toByteArray()); out.flush(); sock.close()
            }
        } catch (_: Exception) {}
    }
    private fun readLine(input: java.io.InputStream): String {
        val sb = StringBuilder()
        while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }
        return sb.toString().trim()
    }
    override fun close() { server.close() }
}

class SharpTest {
    @Test fun `framing is byte-exact per the manual`() {
        assertEquals("MUTE1   \r", String(Aquos.frame("MUTE", "1"), US_ASCII))
        assertEquals("VOLM12  \r", String(Aquos.frame("VOLM", "12"), US_ASCII))
        assertFailsWith<IllegalArgumentException> { Aquos.frame("MUT", "1") }
    }

    @Test fun `login handshake, discrete mute, and volume over a real socket`() = FakeTv(login = Pair("me", "pw")).use { tv ->
        val client = SharpIpClient(SocketTransport("127.0.0.1", tv.port, 1500, Pair("me", "pw")))
        client.muteOn(); assertTrue(tv.muted)
        client.muteOff(); assertFalse(tv.muted)
        client.setVolume(4); assertEquals(4, tv.volume)
        assertEquals(4, client.queryVolume())
        assertEquals(false, client.queryMute())
        assertTrue(tv.received.contains("MUTE1   \r"))
    }

    @Test fun `a refused login is an error, not a silent no-op`() = FakeTv(login = Pair("me", "pw")).use { tv ->
        val client = SharpIpClient(SocketTransport("127.0.0.1", tv.port, 1500, Pair("me", "wrong")))
        assertFailsWith<ControlError> { client.muteOn() }
    }

    @Test fun `unreachable set is a ControlError`() {
        assertFailsWith<ControlError> { SharpIpClient(SocketTransport("127.0.0.1", 1, 300)).muteOn() }
    }

    @Test fun `ducking persists the pre-duck volume, restores it, and the remote wins`() = FakeTv().use { tv ->
        tv.volume = 27
        val persist = MemoryDuckPersistence()
        val ctl = SharpController(SharpIpClient(SocketTransport("127.0.0.1", tv.port, 1500)), persist, duckLevel = 4)
        ctl.duck()
        assertEquals(4, tv.volume); assertEquals(27, persist.load()); assertTrue(ctl.ducked)
        assertFalse(ctl.pollUserOverride(), "still at the duck level: no override")
        tv.volume = 33  // the user grabbed the remote
        assertTrue(ctl.pollUserOverride())
        assertFalse(ctl.ducked); assertNull(persist.load()); assertEquals(33, ctl.normalVolume)
        ctl.duck(); ctl.restore()
        assertEquals(33, tv.volume); assertNull(persist.load())
    }

    @Test fun `a crash while ducked is repaired on the next start`() = FakeTv().use { tv ->
        val persist = MemoryDuckPersistence(); persist.save(31)
        tv.volume = 4
        val ctl = SharpController(SharpIpClient(SocketTransport("127.0.0.1", tv.port, 1500)), persist)
        assertTrue(ctl.recoverOnStart())
        assertEquals(31, tv.volume); assertNull(persist.load())
    }

    @Test fun `when the set answers ERR to VOLM the configured normal volume is used`() = FakeTv(volumeQueryAnswers = false).use { tv ->
        val ctl = SharpController(SharpIpClient(SocketTransport("127.0.0.1", tv.port, 1500)), MemoryDuckPersistence(), duckLevel = 3, normalVolume = 22)
        ctl.duck(); assertEquals(3, tv.volume)
        ctl.restore(); assertEquals(22, tv.volume)
    }
}
