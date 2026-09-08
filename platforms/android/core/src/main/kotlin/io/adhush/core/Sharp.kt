package io.adhush.core

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets.US_ASCII

/**
 * Sharp AQUOS IP control (LC-xxLE830U manual pp. 58–59): four command
 * characters, four space-padded parameter characters, CR; the set answers
 * OK or ERR; MUTE takes 1 = on / 2 = off; VOLM takes 0–60. Each command opens
 * its own connection — which is what makes the set's 3-minute idle
 * disconnect harmless — and answers the optional login handshake first,
 * exactly as control/network_ip.py's perform_login does.
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

    /** Best-effort read: silent firmware is normal, so a timeout yields empty. */
    fun readSome(input: InputStream, buf: ByteArray = ByteArray(4096)): ByteArray = try {
        val n = input.read(buf)
        if (n <= 0) ByteArray(0) else buf.copyOf(n)
    } catch (_: SocketTimeoutException) { ByteArray(0) }

    fun performLogin(input: InputStream, output: OutputStream, loginId: String, password: String): ByteArray {
        readSome(input)  // "Login:"
        output.write(loginId.toByteArray(US_ASCII) + LOGIN_TERMINATOR); output.flush()
        readSome(input)  // "Password:"
        output.write(password.toByteArray(US_ASCII) + LOGIN_TERMINATOR); output.flush()
        val ack = readSome(input)
        val text = String(ack, US_ASCII).trim().lowercase()
        if (LOGIN_REJECTED.any { it in text }) throw ControlError("tv rejected IP control login for '$loginId'")
        return ack
    }
}

/** One command exchange; injectable so the client is testable without a set. */
fun interface AquosTransport { fun exchange(payload: ByteArray): ByteArray }

class SocketTransport(
    private val host: String,
    private val port: Int,
    private val timeoutMs: Int = 2000,
    private val login: Pair<String, String>? = null,
) : AquosTransport {
    override fun exchange(payload: ByteArray): ByteArray = try {
        Socket().use { sock ->
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            sock.soTimeout = timeoutMs
            val input = sock.getInputStream(); val output = sock.getOutputStream()
            login?.let { Aquos.performLogin(input, output, it.first, it.second) }
            output.write(payload); output.flush()
            Aquos.readSome(input)
        }
    } catch (e: ControlError) { throw e } catch (e: Exception) { throw ControlError("tv $host:$port unreachable: ${e.message}", e) }
}

class SharpIpClient(private val transport: AquosTransport) {
    fun send(command: String, parameter: String): String =
        String(transport.exchange(Aquos.frame(command, parameter)), US_ASCII).trim()

    private fun expectOk(command: String, parameter: String) {
        val reply = send(command, parameter)
        if ("OK" !in reply) throw ControlError("tv rejected $command$parameter: '$reply'")
    }

    fun muteOn() = expectOk("MUTE", "1")
    fun muteOff() = expectOk("MUTE", "2")
    fun setVolume(level: Int) { require(level in 0..60); expectOk("VOLM", level.toString()) }
    /** Present volume, or null when the set answers ERR / nothing (untested on real firmware). */
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
