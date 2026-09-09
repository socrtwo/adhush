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

/** A fake AQUOS set on loopback: prompts for login, then answers framed commands until the client hangs up. */
private class FakeTv(
    private val login: Pair<String, String>? = null,
    private val volumeQueryAnswers: Boolean = true,
    private val hangUpAfterEach: Boolean = false,
    private val silentOnMute: Boolean = false,
    private val promptDelayMs: Long = 0,
) : AutoCloseable {
    val server = ServerSocket(0)
    val port get() = server.localPort
    val received = ArrayList<String>()
    @Volatile var connections = 0
    @Volatile var volume = 20
    @Volatile var muted = false
    private val worker = thread(isDaemon = true) {
        try {
            while (true) {
                val sock = server.accept()
                connections++
                // Generous: a slow CI runner must not look like a client that went quiet.
                sock.soTimeout = 10_000
                try { serve(sock) } catch (_: Exception) {} finally { runCatching { sock.close() } }
            }
        } catch (_: Exception) {}
    }
    private fun serve(sock: java.net.Socket) {
                val input = sock.getInputStream(); val out = sock.getOutputStream()
                val buf = ByteArray(256)
                if (login != null) {
                    if (promptDelayMs > 0) Thread.sleep(promptDelayMs)
                    out.write("Login:".toByteArray()); out.flush()
                    val id = readLine(input)
                    out.write("Password:".toByteArray()); out.flush()
                    val pw = readLine(input)
                    if (id != login.first || pw != login.second) { out.write("Login incorrect\r\n".toByteArray()); out.flush(); return }
                    out.write("\r\n".toByteArray()); out.flush()
                }
                while (true) {
                    val n = try { input.read(buf) } catch (_: Exception) { -1 }
                    if (n < 0) break
                    val cmd = String(buf, 0, n, US_ASCII)
                    synchronized(received) { received.add(cmd) }
                    val reply = when {
                        cmd == "VOLM?   \r" -> if (volumeQueryAnswers) "$volume\r\n" else "ERR\r\n"
                        cmd.startsWith("VOLM") -> { volume = cmd.substring(4, 8).trim().toInt(); "OK\r\n" }
                        cmd == "MUTE1   \r" -> { muted = true; if (silentOnMute) "" else "OK\r\n" }
                        cmd == "MUTE2   \r" -> { muted = false; if (silentOnMute) "" else "OK\r\n" }
                        cmd == "MUTE?   \r" -> if (muted) "1\r\n" else "2\r\n"
                        else -> "ERR\r\n"
                    }
                    if (reply.isNotEmpty()) { out.write(reply.toByteArray()); out.flush() }
                    if (hangUpAfterEach) break
                }
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
        val transport = SocketTransport("127.0.0.1", tv.port, 3000, Pair("me", "pw"))
        val trace = ArrayList<String>()
        transport.trace = { synchronized(trace) { trace.add(it) } }
        val client = SharpIpClient(transport)
        client.muteOn(); assertTrue(tv.muted, "after MUTE1: $trace")
        client.muteOff(); assertFalse(tv.muted, "after MUTE2: $trace; set got ${tv.received}")
        client.setVolume(4); assertEquals(4, tv.volume, "after VOLM4: $trace")
        assertEquals(4, client.queryVolume())
        assertEquals(false, client.queryMute())
        assertTrue(tv.received.contains("MUTE1   \r"))
        assertEquals(1, tv.connections, "one connection carries every command")
    }

    @Test fun `a set that hangs up is reconnected transparently`() = FakeTv(hangUpAfterEach = true).use { tv ->
        val transport = SocketTransport("127.0.0.1", tv.port, 3000)
        val client = SharpIpClient(transport)
        client.muteOn(); assertTrue(tv.muted)
        Thread.sleep(50)   // let the fake finish closing before the next exchange
        client.muteOff(); assertFalse(tv.muted)
        client.setVolume(9); assertEquals(9, tv.volume)
        assertTrue(transport.connections >= 2, "reopened after the hang-up, got ${transport.connections}")
    }

    @Test fun `a silent set is unconfirmed, ERR is a rejection`() = FakeTv(silentOnMute = true).use { tv ->
        val client = SharpIpClient(SocketTransport("127.0.0.1", tv.port, 300))
        assertFalse(client.muteOn(), "no OK came back, so unconfirmed"); assertTrue(tv.muted)
        assertTrue(client.setVolume(5), "VOLM is still confirmed")
        val errSet = SharpIpClient { "ERR\r\n".toByteArray(US_ASCII) }
        assertFailsWith<ControlError> { errSet.muteOn() }
    }

    @Test fun `escape makes raw replies readable`() {
        assertEquals("OK\\r\\n", Aquos.escape("OK\r\n".toByteArray(US_ASCII)))
        assertEquals("(nothing)", Aquos.escape(ByteArray(0)))
        assertEquals("\\x00", Aquos.escape(byteArrayOf(0)))
    }

    @Test fun `a login prompt slower than the read timeout does not shift every later reply`() =
        FakeTv(login = Pair("me", "pw"), promptDelayMs = 500).use { tv ->
            // The prompt read times out at 200 ms; without settling, the late prompt and
            // acknowledgement would be read as the replies to MUTE1 and MUTE2.
            val client = SharpIpClient(SocketTransport("127.0.0.1", tv.port, 200, Pair("me", "pw"), settleMs = 300))
            assertTrue(client.muteOn(), "MUTE1 confirmed with the set's own OK"); assertTrue(tv.muted)
            assertTrue(client.muteOff()); assertFalse(tv.muted)
            assertEquals(1, tv.connections)
        }

    @Test fun `a refused login is an error, not a silent no-op`() = FakeTv(login = Pair("me", "pw")).use { tv ->
        val client = SharpIpClient(SocketTransport("127.0.0.1", tv.port, 3000, Pair("me", "wrong")))
        assertFailsWith<ControlError> { client.muteOn() }
    }

    @Test fun `unreachable set is a ControlError`() {
        assertFailsWith<ControlError> { SharpIpClient(SocketTransport("127.0.0.1", 1, 300)).muteOn() }
    }

    @Test fun `ducking persists the pre-duck volume, restores it, and the remote wins`() = FakeTv().use { tv ->
        tv.volume = 27
        val persist = MemoryDuckPersistence()
        val ctl = SharpController(SharpIpClient(SocketTransport("127.0.0.1", tv.port, 3000)), persist, duckLevel = 4)
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
        val ctl = SharpController(SharpIpClient(SocketTransport("127.0.0.1", tv.port, 3000)), persist)
        assertTrue(ctl.recoverOnStart())
        assertEquals(31, tv.volume); assertNull(persist.load())
    }

    @Test fun `when the set answers ERR to VOLM the configured normal volume is used`() = FakeTv(volumeQueryAnswers = false).use { tv ->
        val ctl = SharpController(SharpIpClient(SocketTransport("127.0.0.1", tv.port, 3000)), MemoryDuckPersistence(), duckLevel = 3, normalVolume = 22)
        ctl.duck(); assertEquals(3, tv.volume)
        ctl.restore(); assertEquals(22, tv.volume)
    }
}
