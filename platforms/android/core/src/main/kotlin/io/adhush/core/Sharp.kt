package io.adhush.core

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets.US_ASCII

/**
 * Sharp AQUOS IP control (LC-xxLE830U manual pp. 58–59): four command
 * characters, four space-padded parameter characters, CR; the set answers
 * OK or ERR — or, on some firmware, nothing at all; MUTE takes 1 = on / 2 =
 * off; VOLM takes 0–60. The optional login handshake is answered once per
 * connection, exactly as control/network_ip.py's perform_login does.
 */
object Aquos {
    const val DEFAULT_PORT = 10002
    private val LOGIN_TERMINATOR = "\r\n".toByteArray(US_ASCII)
    private val LOGIN_REJECTED = listOf("login incorrect", "denied", "invalid")

    fun frame(command: String, parameter: String): ByteArray {
        require(command.length == 4) { "AQUOS command must be 4 characters: $command" }
        require(parameter.length <= 4) { "AQUOS parameter longer than 4: $parameter" }
        return (command + parameter.padEnd(4) + "\r").toByteArray(US_ASCII)
    }

    /**
     * Best-effort read: silent firmware is normal, so a timeout yields empty.
     * End of stream is different — the set hung up — and is an EOFException.
     */
    fun readSome(input: InputStream, buf: ByteArray = ByteArray(4096)): ByteArray = try {
        val n = input.read(buf)
        if (n < 0) throw EOFException("the set closed the connection")
        buf.copyOf(n)
    } catch (_: SocketTimeoutException) { ByteArray(0) }

    /** Printable form of raw bytes for diagnostics: CR, LF and non-ASCII are escaped. */
    fun escape(bytes: ByteArray): String = if (bytes.isEmpty()) "(nothing)" else buildString {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            when {
                v == '\r'.code -> append("\\r")
                v == '\n'.code -> append("\\n")
                v < 0x20 || v > 0x7e -> append("\\x%02x".format(v))
                else -> append(v.toChar())
            }
        }
    }

    fun performLogin(
        input: InputStream, output: OutputStream, loginId: String, password: String,
        trace: ((String) -> Unit)? = null,
    ): ByteArray {
        trace?.invoke("login prompt: " + escape(readSome(input)))
        output.write(loginId.toByteArray(US_ASCII) + LOGIN_TERMINATOR); output.flush()
        trace?.invoke("password prompt: " + escape(readSome(input)))
        output.write(password.toByteArray(US_ASCII) + LOGIN_TERMINATOR); output.flush()
        val ack = readSome(input)
        trace?.invoke("login reply: " + escape(ack))
        val text = String(ack, US_ASCII).trim().lowercase()
        if (LOGIN_REJECTED.any { it in text }) throw ControlError("tv rejected IP control login for '$loginId'")
        return ack
    }
}

/** One command exchange; injectable so the client is testable without a set. */
fun interface AquosTransport { fun exchange(payload: ByteArray): ByteArray }

/**
 * One connection, kept open across commands and reopened when the set drops
 * it (its 3-minute idle disconnect, a power cycle, Wi-Fi). The first phone
 * test showed why: the LC-46LE830U answered the first connection and then
 * ignored the ones opened moments later for the next commands, so a
 * connection per command does not work on this firmware. Exchanges are
 * serialised; [trace] receives every connect, prompt and raw reply.
 */
class SocketTransport(
    private val host: String,
    private val port: Int,
    private val timeoutMs: Int = 2000,
    private val login: Pair<String, String>? = null,
) : AquosTransport, AutoCloseable {
    @Volatile var trace: ((String) -> Unit)? = null
    private var sock: Socket? = null
    private var silentInARow = 0
    /** How many connections were opened; tests read it. */
    var connections = 0
        private set

    @Synchronized
    override fun exchange(payload: ByteArray): ByteArray {
        var attempt = 0
        while (true) {
            try {
                val s = sock ?: open().also { sock = it }
                val input = s.getInputStream()
                drainStale(input)
                s.getOutputStream().apply { write(payload); flush() }
                val reply = Aquos.readSome(input)
                trace?.invoke(Aquos.escape(payload) + " -> " + Aquos.escape(reply))
                // A half-open connection looks exactly like a silent set; after a
                // few silent replies in a row, reconnect rather than keep guessing.
                silentInARow = if (reply.isEmpty()) silentInARow + 1 else 0
                if (silentInARow >= SILENT_LIMIT) { silentInARow = 0; drop() }
                return reply
            } catch (e: ControlError) {
                drop(); throw e
            } catch (e: IOException) {
                drop()
                trace?.invoke("connection lost: ${e.message}")
                if (++attempt >= 2) throw ControlError("tv $host:$port unreachable: ${e.message}", e)
            } catch (e: Exception) {
                drop(); throw ControlError("tv $host:$port: ${e.message}", e)
            }
        }
    }

    private fun open(): Socket {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            connections++
            trace?.invoke("connected to $host:$port (connection $connections)")
            login?.let { Aquos.performLogin(s.getInputStream(), s.getOutputStream(), it.first, it.second, trace) }
        } catch (e: Exception) {
            runCatching { s.close() }; throw e
        }
        return s
    }

    /** A late reply to an earlier command must not be read as this one's answer. */
    private fun drainStale(input: InputStream) {
        while (input.available() > 0) {
            val stale = Aquos.readSome(input)
            if (stale.isEmpty()) break
            trace?.invoke("late reply discarded: " + Aquos.escape(stale))
        }
    }

    @Synchronized fun drop() { sock?.let { runCatching { it.close() } }; sock = null }
    override fun close() = drop()

    private companion object { const val SILENT_LIMIT = 3 }
}

class SharpIpClient(private val transport: AquosTransport) {
    fun send(command: String, parameter: String): String =
        String(transport.exchange(Aquos.frame(command, parameter)), US_ASCII).trim()

    /** True when the set said OK, false when it said nothing (silent firmware); ERR is a ControlError. */
    private fun expectOk(command: String, parameter: String): Boolean {
        val reply = send(command, parameter).uppercase()
        if ("ERR" in reply) throw ControlError("tv rejected $command$parameter: '$reply'")
        return "OK" in reply
    }

    fun muteOn(): Boolean = expectOk("MUTE", "1")
    fun muteOff(): Boolean = expectOk("MUTE", "2")
    fun setVolume(level: Int): Boolean { require(level in 0..60); return expectOk("VOLM", level.toString()) }
    /** Present volume, or null when the set answers ERR / nothing. */
    fun queryVolume(): Int? = send("VOLM", "?").filter { it.isDigit() }.toIntOrNull()?.takeIf { it in 0..60 }
    /** Mute state via MUTE?: 1 = on, 2 = off, else null. */
    fun queryMute(): Boolean? = when (send("MUTE", "?").trim()) { "1" -> true; "2" -> false; else -> null }
}

/** Survives a crash: the pre-duck volume must outlive the process (design: fail-safe). */
interface DuckPersistence { fun save(volume: Int); fun load(): Int?; fun clear() }
class MemoryDuckPersistence : DuckPersistence {
    private var v: Int? = null
    override fun save(volume: Int) { v = volume }
    override fun load(): Int? = v
    override fun clear() { v = null }
}

/**
 * Ducks instead of muting so the microphone keeps hearing the set, and restores
 * on every path back. mute()/unmute() are the MuteController face the engine
 * sees. The remote always wins: pollUserOverride() notices a volume that is
 * not the duck level and stands down.
 */
class SharpController(
    private val client: SharpIpClient,
    private val persistence: DuckPersistence,
    val duckLevel: Int = 4,
    var normalVolume: Int = 20,
    private val useMuteInstead: Boolean = false,
) : MuteController {
    var ducked = false
        private set

    /** Call once at start-up: a saved pre-duck volume means we died ducked. */
    fun recoverOnStart(): Boolean {
        val saved = persistence.load() ?: return false
        normalVolume = saved
        restore()
        return true
    }

    fun duck() {
        if (!useMuteInstead) {
            client.queryVolume()?.let { if (it != duckLevel) normalVolume = it }
            persistence.save(normalVolume)
            client.setVolume(duckLevel)
        } else {
            persistence.save(normalVolume)
            client.muteOn()
        }
        ducked = true
    }

    fun restore() {
        val target = persistence.load() ?: normalVolume
        if (!useMuteInstead) client.setVolume(target) else client.muteOff()
        normalVolume = target
        persistence.clear()
        ducked = false
    }

    /** While ducked: did the user touch the remote? Then adopt their level and stand down. */
    fun pollUserOverride(): Boolean {
        if (!ducked || useMuteInstead) return false
        val now = client.queryVolume() ?: return false
        if (now == duckLevel) return false
        normalVolume = now
        persistence.clear()
        ducked = false
        return true
    }

    /** While at rest: follow the user's volume so a later restore lands on it. */
    fun trackNormal() { if (!ducked) client.queryVolume()?.let { normalVolume = it } }

    override fun mute() = duck()
    override fun unmute() = restore()
    override fun state(): Boolean? = ducked
    override fun close() { if (ducked) runCatching { restore() } }
}
