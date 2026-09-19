package io.adhush.core

import java.util.Base64

data class SamsungInfo(val name: String, val model: String, val type: String)

/**
 * Samsung's remote-control channel (ADR 0025): a WebSocket on port 8002
 * (8001 on 2016–2017 sets) that sends key presses as the handset would.
 * The first connection makes the set ask "Allow AdHush?"; the token it then
 * hands back is kept so it never asks again. One-way: volume is stepped.
 */
class SamsungTizen(private val host: String, private var token: String?, private val name: String = "AdHush", private val timeoutMs: Int = 5000) : KeySender, AutoCloseable {
    private var ws: MiniWebSocket? = null

    /** Connects (the set may show its prompt); returns the token to keep, or null when it refused. */
    fun connect(waitForPromptMs: Int = 30_000): String? {
        val b64 = Base64.getEncoder().encodeToString(name.toByteArray(Charsets.UTF_8))
        val tokenPart = token?.let { "&token=$it" } ?: ""
        val urls = listOf("wss://$host:8002/api/v2/channels/samsung.remote.control?name=$b64$tokenPart", "ws://$host:8001/api/v2/channels/samsung.remote.control?name=$b64")
        var lastError: Exception? = null
        for (url in urls) {
            val sock = MiniWebSocket(url, timeoutMs)
            try {
                sock.connect()
                val first = sock.receive(waitForPromptMs) ?: throw ControlError("samsung: no answer (denied on the set?)")
                if ("ms.channel.connect" !in first) throw ControlError("samsung: unexpected greeting")
                MiniHttp.find(first, "\"token\"\\s*:\\s*\"([^\"]+)\"")?.let { token = it }
                ws = sock
                return token
            } catch (e: Exception) { lastError = e; sock.close() }
        }
        throw ControlError("samsung: ${lastError?.message}")
    }

    val currentToken: String? get() = token

    private fun ensure() { if (ws == null) connect(10_000) }

    fun key(name: String): Boolean {
        ensure()
        val msg = """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"$name","Option":"false","TypeOfRemote":"SendRemoteKey"}}"""
        try { ws!!.send(msg) } catch (e: Exception) { ws?.close(); ws = null; throw ControlError("samsung: ${e.message}") }
        return true
    }

    override fun press(key: TvKey, times: Int) {
        val name = when (key) { TvKey.VOLUME_UP -> "KEY_VOLUP"; TvKey.VOLUME_DOWN -> "KEY_VOLDOWN"; TvKey.MUTE -> "KEY_MUTE" }
        repeat(times) { key(name); Thread.sleep(PRESS_GAP_MS) }
    }

    fun press(key: RemoteKey): Boolean { val name = REMOTE[key] ?: return false; return key(name) }

    override fun close() { ws?.close(); ws = null }

    companion object {
        const val PRESS_GAP_MS = 80L
        val REMOTE: Map<RemoteKey, String> = mapOf(
            RemoteKey.POWER to "KEY_POWER", RemoteKey.VOL_UP to "KEY_VOLUP", RemoteKey.VOL_DOWN to "KEY_VOLDOWN", RemoteKey.MUTE to "KEY_MUTE",
            RemoteKey.UP to "KEY_UP", RemoteKey.DOWN to "KEY_DOWN", RemoteKey.LEFT to "KEY_LEFT", RemoteKey.RIGHT to "KEY_RIGHT", RemoteKey.ENTER to "KEY_ENTER",
            RemoteKey.RETURN to "KEY_RETURN", RemoteKey.EXIT to "KEY_EXIT", RemoteKey.MENU to "KEY_MENU", RemoteKey.SMART to "KEY_HOME", RemoteKey.INPUT to "KEY_SOURCE",
            RemoteKey.CH_UP to "KEY_CHUP", RemoteKey.CH_DOWN to "KEY_CHDOWN", RemoteKey.DISPLAY to "KEY_INFO", RemoteKey.CC to "KEY_CAPTION",
            RemoteKey.DIGIT_0 to "KEY_0", RemoteKey.DIGIT_1 to "KEY_1", RemoteKey.DIGIT_2 to "KEY_2", RemoteKey.DIGIT_3 to "KEY_3", RemoteKey.DIGIT_4 to "KEY_4",
            RemoteKey.DIGIT_5 to "KEY_5", RemoteKey.DIGIT_6 to "KEY_6", RemoteKey.DIGIT_7 to "KEY_7", RemoteKey.DIGIT_8 to "KEY_8", RemoteKey.DIGIT_9 to "KEY_9",
            RemoteKey.REW to "KEY_REWIND", RemoteKey.PLAY to "KEY_PLAY", RemoteKey.FF to "KEY_FF", RemoteKey.PAUSE to "KEY_PAUSE", RemoteKey.STOP to "KEY_STOP",
            RemoteKey.RED to "KEY_RED", RemoteKey.GREEN to "KEY_GREEN", RemoteKey.YELLOW to "KEY_YELLOW", RemoteKey.BLUE to "KEY_BLUE",
        )

        fun probe(host: String, timeoutMs: Int = 1500): SamsungInfo? {
            val r = try { MiniHttp.get("http://$host:8001/api/v2/", timeoutMs = timeoutMs) } catch (e: Exception) { return null }
            if (!r.ok) return null
            return parseInfo(r.body)
        }

        fun parseInfo(json: String): SamsungInfo? {
            val device = Regex("\"device\"\\s*:\\s*\\{(.*?)}", RegexOption.DOT_MATCHES_ALL).find(json)?.groupValues?.get(1) ?: return null
            val name = MiniHttp.find(device, "\"name\"\\s*:\\s*\"([^\"]*)\"") ?: ""
            val model = MiniHttp.find(device, "\"modelName\"\\s*:\\s*\"([^\"]*)\"") ?: ""
            val type = MiniHttp.find(device, "\"type\"\\s*:\\s*\"([^\"]*)\"") ?: ""
            return SamsungInfo(name, model, type)
        }
    }
}
