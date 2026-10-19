package io.adhush.core

/**
 * Hisense VIDAA (ADR 0026): the set runs an MQTT broker on port 36669 over
 * TLS; a client connects with Hisense's service name and password, and a
 * newer set shows a four-digit code the first time and expects it back.
 * Volume is read and set exactly through the platform service; keys go
 * through the remote service. Sets from about 2022 on also demand a
 * client certificate Hisense issues, which AdHush does not carry: those
 * refuse the TLS handshake, and the wizard says so.
 */
class HisenseVidaa(private val host: String, private val clientId: String, private val identity: ClientIdentity? = null, private val timeoutMs: Int = 5000) : VolumeDevice {
    override val maxVolume: Int get() = 100
    private var mqtt: MiniMqtt? = null

    private fun topic(service: String, action: String) = "/remoteapp/tv/$service/$clientId/actions/$action"

    /** Connects and asks the set its state; returns true when it is already authorised, false when it wants its code. */
    fun connect(): Boolean {
        close()
        val m = MiniMqtt(host, 36669, tls = true, identity = identity, timeoutMs = timeoutMs)
        m.connect(clientId, USERNAME, PASSWORD)
        m.subscribe("/remoteapp/mobile/#")
        mqtt = m
        m.publish(topic("ui_service", "gettvstate"), ByteArray(0))
        val deadline = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < deadline) {
            val p = m.next(1000) ?: continue
            val text = String(p.payload, Charsets.UTF_8)
            if ("authentication" in p.topic && "\"result\"" in text && MiniHttp.find(text, "\"result\"\\s*:\\s*(\\d+)") != "1") return false
            if ("tvstate" in p.topic) return true
        }
        return true   // older sets answer nothing and simply obey
    }

    /** The four digits from the screen; true when the set accepted them. */
    fun authenticate(code: String): Boolean {
        val m = m()
        m.publish(topic("ui_service", "authenticationcode"), """{"authNum":"$code"}""".toByteArray())
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            val p = m.next(1000) ?: continue
            if ("authenticationcode" in p.topic || "authentication" in p.topic) return MiniHttp.find(String(p.payload), "\"result\"\\s*:\\s*(\\d+)") == "1"
        }
        return false
    }

    private fun m(): MiniMqtt = mqtt ?: run { connect(); mqtt ?: throw ControlError("hisense: not connected") }

    fun key(name: String) { m().publish(topic("remote_service", "sendkey"), name.toByteArray()) }

    fun press(key: RemoteKey): Boolean { val name = REMOTE[key] ?: return false; key(name); return true }

    override fun getVolume(): Int? {
        val m = try { m() } catch (e: ControlError) { return null }
        m.publish(topic("platform_service", "getvolume"), ByteArray(0))
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            val p = m.next(1000) ?: continue
            if ("volume" in p.topic) return parseVolume(String(p.payload))
        }
        return null
    }

    override fun setVolume(level: Int): Boolean { m().publish(topic("platform_service", "changevolume"), level.coerceIn(0, 100).toString().toByteArray()); return true }
    override fun setMute(on: Boolean): Boolean { key("KEY_MUTE"); return true }
    override fun close() { mqtt?.close(); mqtt = null }

    companion object {
        const val USERNAME = "hisenseservice"
        const val PASSWORD = "multimqttservice"
        val REMOTE: Map<RemoteKey, String> = mapOf(
            RemoteKey.POWER to "KEY_POWER", RemoteKey.VOL_UP to "KEY_VOLUMEUP", RemoteKey.VOL_DOWN to "KEY_VOLUMEDOWN", RemoteKey.MUTE to "KEY_MUTE",
            RemoteKey.UP to "KEY_UP", RemoteKey.DOWN to "KEY_DOWN", RemoteKey.LEFT to "KEY_LEFT", RemoteKey.RIGHT to "KEY_RIGHT", RemoteKey.ENTER to "KEY_OK",
            RemoteKey.RETURN to "KEY_RETURNS", RemoteKey.EXIT to "KEY_EXIT", RemoteKey.MENU to "KEY_MENU", RemoteKey.SMART to "KEY_HOME", RemoteKey.INPUT to "KEY_SOURCE",
            RemoteKey.CH_UP to "KEY_CHANNELUP", RemoteKey.CH_DOWN to "KEY_CHANNELDOWN", RemoteKey.PLAY to "KEY_PLAY", RemoteKey.PAUSE to "KEY_PAUSE", RemoteKey.STOP to "KEY_STOP",
            RemoteKey.FF to "KEY_FORWARDS", RemoteKey.REW to "KEY_BACK",
            RemoteKey.DIGIT_0 to "KEY_0", RemoteKey.DIGIT_1 to "KEY_1", RemoteKey.DIGIT_2 to "KEY_2", RemoteKey.DIGIT_3 to "KEY_3", RemoteKey.DIGIT_4 to "KEY_4",
            RemoteKey.DIGIT_5 to "KEY_5", RemoteKey.DIGIT_6 to "KEY_6", RemoteKey.DIGIT_7 to "KEY_7", RemoteKey.DIGIT_8 to "KEY_8", RemoteKey.DIGIT_9 to "KEY_9",
        )
        fun parseVolume(json: String): Int? = MiniHttp.find(json, "\"volume_value\"\\s*:\\s*(\\d+)")?.toIntOrNull()
        fun newClientId(): String = "adhush-" + java.util.UUID.randomUUID().toString().take(8) + "\$normal"
        fun probe(host: String, timeoutMs: Int = 400): Boolean = try { java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, 36669), timeoutMs) }; true } catch (e: Exception) { false }
    }
}
