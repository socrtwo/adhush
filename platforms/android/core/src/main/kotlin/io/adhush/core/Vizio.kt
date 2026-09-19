package io.adhush.core

/**
 * Vizio SmartCast (ADR 0025): HTTPS on port 7345 with the set's own
 * certificate. Pairing shows a PIN on the screen once; the auth token it
 * returns is kept. Keys are one-way, so the volume is stepped.
 */
class VizioSmartCast(private val host: String, private var token: String?, private val port: Int = 7345, private val timeoutMs: Int = 4000) : KeySender {
    val currentToken: String? get() = token
    private fun url(path: String) = "https://$host:$port$path"
    private fun auth(): Map<String, String> = token?.let { mapOf("AUTH" to it) } ?: emptyMap()

    /** Step one: the set shows a PIN; keep the request token for step two. */
    fun pairStart(deviceId: String): String {
        val r = MiniHttp.put(url("/pairing/start"), """{"DEVICE_ID":"$deviceId","DEVICE_NAME":"AdHush"}""", timeoutMs = timeoutMs, trustAll = true)
        return MiniHttp.find(r.body, "\"PAIRING_REQ_TOKEN\"\\s*:\\s*(\\d+)") ?: throw ControlError("vizio: pairing did not start (${r.code})")
    }

    /** Step two: the PIN from the screen; returns the auth token to keep. */
    fun pair(deviceId: String, pin: String, requestToken: String): String {
        val r = MiniHttp.put(url("/pairing/pair"), """{"DEVICE_ID":"$deviceId","CHALLENGE_TYPE":1,"RESPONSE_VALUE":"$pin","PAIRING_REQ_TOKEN":$requestToken}""", timeoutMs = timeoutMs, trustAll = true)
        val t = MiniHttp.find(r.body, "\"AUTH_TOKEN\"\\s*:\\s*\"([^\"]+)\"") ?: throw ControlError("vizio: pairing refused — wrong PIN?")
        token = t
        return t
    }

    private fun key(codeset: Int, code: Int): Boolean {
        val r = MiniHttp.put(url("/key_command/"), """{"KEYLIST":[{"CODESET":$codeset,"CODE":$code,"ACTION":"KEYPRESS"}]}""", auth(), timeoutMs, trustAll = true)
        if (!r.ok) throw ControlError("vizio key: HTTP ${r.code}")
        return "SUCCESS" in r.body
    }

    override fun press(key: TvKey, times: Int) {
        val (set, code) = when (key) { TvKey.VOLUME_UP -> 5 to 1; TvKey.VOLUME_DOWN -> 5 to 0; TvKey.MUTE -> 5 to 4 }
        repeat(times) { if (!key(set, code)) throw ControlError("vizio did not accept the key"); Thread.sleep(PRESS_GAP_MS) }
    }

    fun press(key: RemoteKey): Boolean { val (set, code) = REMOTE[key] ?: return false; return key(set, code) }

    companion object {
        const val PRESS_GAP_MS = 80L
        val REMOTE: Map<RemoteKey, Pair<Int, Int>> = mapOf(
            RemoteKey.VOL_UP to (5 to 1), RemoteKey.VOL_DOWN to (5 to 0), RemoteKey.MUTE to (5 to 4), RemoteKey.POWER to (11 to 2),
            RemoteKey.UP to (3 to 8), RemoteKey.DOWN to (3 to 0), RemoteKey.LEFT to (3 to 1), RemoteKey.RIGHT to (3 to 7), RemoteKey.ENTER to (3 to 2),
            RemoteKey.RETURN to (4 to 0), RemoteKey.EXIT to (9 to 0), RemoteKey.MENU to (4 to 8), RemoteKey.SMART to (4 to 3), RemoteKey.INPUT to (7 to 1),
            RemoteKey.CH_UP to (8 to 1), RemoteKey.CH_DOWN to (8 to 0), RemoteKey.DISPLAY to (4 to 6), RemoteKey.CC to (4 to 4),
            RemoteKey.PLAY to (2 to 3), RemoteKey.PAUSE to (2 to 2), RemoteKey.REW to (2 to 1), RemoteKey.FF to (2 to 0),
        )

        /** No token needed for this one: is there a SmartCast set at this address? */
        fun probe(host: String, timeoutMs: Int = 1500): Boolean {
            val r = try { MiniHttp.get("https://$host:7345/state/device/power_mode", timeoutMs = timeoutMs, trustAll = true) } catch (e: Exception) { return false }
            return r.ok && "SUCCESS" in r.body
        }
    }
}
