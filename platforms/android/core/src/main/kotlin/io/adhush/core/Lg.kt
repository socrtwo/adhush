package io.adhush.core

/**
 * LG webOS (ADR 0025): a WebSocket on port 3000 (3001 with TLS on newer
 * firmware) speaking SSAP. The first connection registers the app and the
 * set asks the owner to allow it; the client key it hands back is kept.
 * Volume is read and set exactly, so this is a [VolumeDevice].
 */
class LgWebOs(private val host: String, private var clientKey: String?, private val timeoutMs: Int = 5000) : VolumeDevice {
    override val maxVolume: Int get() = 100
    private var ws: MiniWebSocket? = null
    private var id = 1
    val currentKey: String? get() = clientKey

    /** Registers (the set may show its prompt); returns the client key to keep. */
    fun connect(waitForPromptMs: Int = 60_000): String? {
        var lastError: Exception? = null
        for (url in listOf("ws://$host:3000", "wss://$host:3001")) {
            val sock = MiniWebSocket(url, timeoutMs)
            try {
                sock.connect()
                val keyPart = clientKey?.let { "\"client-key\":\"$it\"," } ?: ""
                sock.send("""{"type":"register","id":"register_0","payload":{$keyPart"forcePairing":false,"pairingType":"PROMPT","manifest":$MANIFEST}}""")
                val deadline = System.currentTimeMillis() + waitForPromptMs
                while (System.currentTimeMillis() < deadline) {
                    val msg = sock.receive(5000) ?: continue
                    if ("\"registered\"" in msg) { MiniHttp.find(msg, "\"client-key\"\\s*:\\s*\"([^\"]+)\"")?.let { clientKey = it }; ws = sock; return clientKey }
                    if ("\"error\"" in msg && "403" in msg) throw ControlError("lg: the set refused the pairing")
                }
                throw ControlError("lg: no answer to the pairing prompt")
            } catch (e: Exception) { lastError = e; sock.close() }
        }
        throw ControlError("lg: ${lastError?.message}")
    }

    private fun request(uri: String, payload: String = "{}"): String {
        if (ws == null) connect(10_000)
        val myId = "req_${id++}"
        try {
            ws!!.send("""{"type":"request","id":"$myId","uri":"$uri","payload":$payload}""")
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val msg = ws!!.receive(timeoutMs) ?: break
                if ("\"$myId\"" in msg) return msg
            }
        } catch (e: Exception) { ws?.close(); ws = null; throw ControlError("lg: ${e.message}") }
        throw ControlError("lg: no reply to $uri")
    }

    override fun getVolume(): Int? = try { parseVolume(request("ssap://audio/getVolume")) } catch (e: ControlError) { null }
    override fun setVolume(level: Int): Boolean { request("ssap://audio/setVolume", """{"volume":${level.coerceIn(0, 100)}}"""); return true }
    override fun setMute(on: Boolean): Boolean { request("ssap://audio/setMute", """{"mute":$on}"""); return true }
    override fun close() { ws?.close(); ws = null }

    companion object {
        fun parseVolume(msg: String): Int? = MiniHttp.find(msg, "\"volume\"\\s*:\\s*(\\d+)")?.toIntOrNull()
        /** The permissions AdHush needs, and no more: audio, and the set's own description. */
        const val MANIFEST = """{"manifestVersion":1,"appVersion":"1.0","signed":{"created":"20260101","appId":"io.adhush","vendorId":"io.adhush","localizedAppNames":{"":"AdHush"},"localizedVendorNames":{"":"AdHush"},"permissions":["TEST_SECURE","CONTROL_INPUT_TEXT","CONTROL_MOUSE_AND_KEYBOARD","READ_INSTALLED_APPS","READ_LGE_SDX","READ_NOTIFICATIONS","SEARCH","WRITE_SETTINGS","WRITE_NOTIFICATION_ALERT","CONTROL_POWER","READ_CURRENT_CHANNEL","READ_RUNNING_APPS","READ_UPDATE_INFO","UPDATE_FROM_REMOTE_APP","READ_LGE_TV_INPUT_EVENTS","READ_TV_CURRENT_TIME"],"serial":"adhush"},"permissions":["LAUNCH","LAUNCH_WEBAPP","APP_TO_APP","CLOSE","TEST_OPEN","TEST_PROTECTED","CONTROL_AUDIO","CONTROL_DISPLAY","CONTROL_INPUT_JOYSTICK","CONTROL_INPUT_MEDIA_RECORDING","CONTROL_INPUT_MEDIA_PLAYBACK","CONTROL_INPUT_TV","CONTROL_POWER","READ_APP_STATUS","READ_CURRENT_CHANNEL","READ_INPUT_DEVICE_LIST","READ_NETWORK_STATE","READ_RUNNING_APPS","READ_TV_CHANNEL_LIST","WRITE_NOTIFICATION_TOAST","READ_POWER_STATE","READ_COUNTRY_INFO"],"signatures":[{"signatureVersion":1,"signature":"eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbmctY2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9.hrVRgjCwXVvE2OOSpDZ58hR+59aFNwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRyaMOv5zWSrthlf7G128qvIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4RlL48eb3lLKqTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu+WD4OO2A27Pq1n50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM2DXzdKX9NmmyqzJ3o/0lkk/N97gfVRLW5hA29yeAwaCViZNCP8iC9aO0q9fQojoa7NQnAtw=="}]}"""
    }
}
