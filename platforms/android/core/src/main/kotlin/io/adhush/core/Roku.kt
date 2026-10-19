package io.adhush.core

/** What a Roku says about itself on port 8060. */
data class RokuInfo(val model: String, val vendor: String, val name: String, val isTv: Boolean)

/**
 * Roku's External Control Protocol (ADR 0025): plain HTTP on port 8060, no
 * pairing, `POST /keypress/<Key>`. A Roku TV (TCL, Hisense, Sharp's newer
 * sets, onn.) steps its volume with it; a Roku stick cannot touch the TV's
 * volume and is reported as not a TV.
 */
class RokuEcp(private val host: String, private val port: Int = 8060, private val timeoutMs: Int = 2000) : KeySender {
    private fun key(name: String): Boolean = MiniHttp.post("http://$host:$port/keypress/$name", "", timeoutMs = timeoutMs).ok

    override fun press(key: TvKey, times: Int) {
        val name = when (key) { TvKey.VOLUME_UP -> "VolumeUp"; TvKey.VOLUME_DOWN -> "VolumeDown"; TvKey.MUTE -> "VolumeMute" }
        repeat(times) { if (!key(name)) throw ControlError("roku did not accept $name"); Thread.sleep(PRESS_GAP_MS) }
    }

    /** A remote key by AdHush's name, when Roku has one for it. */
    fun press(key: RemoteKey): Boolean { val name = REMOTE[key] ?: return false; return key(name) }

    companion object {
        const val PRESS_GAP_MS = 80L
        val REMOTE: Map<RemoteKey, String> = mapOf(
            RemoteKey.POWER to "PowerOff", RemoteKey.VOL_UP to "VolumeUp", RemoteKey.VOL_DOWN to "VolumeDown", RemoteKey.MUTE to "VolumeMute",
            RemoteKey.UP to "Up", RemoteKey.DOWN to "Down", RemoteKey.LEFT to "Left", RemoteKey.RIGHT to "Right", RemoteKey.ENTER to "Select",
            RemoteKey.RETURN to "Back", RemoteKey.EXIT to "Home", RemoteKey.MENU to "Home", RemoteKey.SMART to "Home", RemoteKey.INPUT to "InputTuner",
            RemoteKey.REW to "Rev", RemoteKey.PLAY to "Play", RemoteKey.FF to "Fwd", RemoteKey.PAUSE to "Play", RemoteKey.CH_UP to "ChannelUp", RemoteKey.CH_DOWN to "ChannelDown",
            RemoteKey.DISPLAY to "Info",
        )

        fun probe(host: String, timeoutMs: Int = 1500): RokuInfo? {
            val r = try { MiniHttp.get("http://$host:8060/query/device-info", timeoutMs = timeoutMs) } catch (e: Exception) { return null }
            if (!r.ok) return null
            return parseInfo(r.body)
        }

        fun parseInfo(xml: String): RokuInfo? {
            val model = MiniHttp.find(xml, "<model-name>([^<]*)</model-name>") ?: return null
            val vendor = MiniHttp.find(xml, "<vendor-name>([^<]*)</vendor-name>") ?: ""
            val name = MiniHttp.find(xml, "<friendly-device-name>([^<]*)</friendly-device-name>") ?: MiniHttp.find(xml, "<user-device-name>([^<]*)</user-device-name>") ?: ""
            val isTv = MiniHttp.find(xml, "<is-tv>([^<]*)</is-tv>")?.trim() == "true"
            return RokuInfo(model.trim(), vendor.trim(), name.trim(), isTv)
        }
    }
}
