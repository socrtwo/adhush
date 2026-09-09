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
    private val wantCrlf: Boolean = false,
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
                    val id = readField(sock)
                    out.write("\r\nPassword:".toByteArray()); out.flush()
                    val pw = readField(sock)
                    if (id != login.first || pw != login.second) {
                        out.write("\r\nUser Name or Password mismatch. Connection Closed.\r\n".toByteArray()); out.flush()
                        // Hang up like the set does, with a FIN the client can read past — unread
                        // input would turn the close into a reset that discards our last words.
                        while (input.available() > 0) input.read()
                        sock.shutdownOutput(); Thread.sleep(50)
                        return
                    }
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
    /**
     * Like the real set: a field ends at CR and nothing is stripped, so a client
     * that sends CRLF hands the LF to the next field. With [wantCrlf] the fake
     * instead insists on an LF after the CR, refusing when none follows.
     */
    private fun readField(sock: java.net.Socket): String? {
        val input = sock.getInputStream()
        val sb = StringBuilder()
        while (true) { val c = input.read(); if (c < 0) return null; if (c == '\r'.code) break; sb.append(c.toChar()) }
        if (wantCrlf) {
            val was = sock.soTimeout; sock.soTimeout = 200
            val next = try { input.read() } catch (_: Exception) { -1 } finally { sock.soTimeout = was }
            if (next != '\n'.code) return null
        }
        return sb.toString()
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

    @Test fun `a refused login is an error naming the set's words, not a silent no-op`() = FakeTv(login = Pair("me", "pw")).use { tv ->
        val transport = SocketTransport("127.0.0.1", tv.port, 3000, Pair("me", "wrong"))
        val e = assertFailsWith<ControlError> { SharpIpClient(transport).muteOn() }
        // The refusal text, or — when the hang-up beats it — the hang-up itself; never "unreachable".
        assertTrue("rejected IP control login" in (e.message ?: ""), e.message)
        assertTrue("mismatch" in (e.message ?: "") || "hung up" in (e.message ?: ""), e.message)
        assertEquals(2, transport.connections, "CR refused, CRLF tried once, then given up")
    }

    @Test fun `a set that wants CRLF-terminated login fields gets them on the second connection`() =
        FakeTv(login = Pair("me", "pw"), wantCrlf = true).use { tv ->
            val transport = SocketTransport("127.0.0.1", tv.port, 3000, Pair("me", "pw"))
            val trace = ArrayList<String>(); transport.trace = { synchronized(trace) { trace.add(it) } }
            val client = SharpIpClient(transport)
            try { assertTrue(client.muteOn()) } catch (e: ControlError) { throw AssertionError("${e.message}; trace=$trace", e) }
            assertTrue(tv.muted)
            assertEquals(2, transport.connections)
            assertTrue(transport.terminator.contentEquals(Aquos.CRLF))
            client.muteOff(); assertFalse(tv.muted)
            assertEquals(2, transport.connections, "the answer is remembered")
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
