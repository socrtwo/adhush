package io.adhush.core

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class PhilipsInfo(val version: Int, val name: String, val model: String)

/**
 * Philips JointSpace (ADR 0026). Version 1 (2011–2016 sets, some later):
 * plain HTTP on port 1925, no pairing, `/1/audio/volume` reads and writes
 * the level. Version 6 (the Android-based sets): HTTPS on 1926 with
 * digest authentication, paired once — the set shows a PIN, the grant is
 * signed with Philips' published secret — after which the same volume
 * calls work. Both read the volume back, so this is a [VolumeDevice].
 */
class PhilipsJointSpace(private val host: String, private val version: Int, private val deviceId: String = "", private val key: String = "", private val timeoutMs: Int = 4000) : VolumeDevice {
    override val maxVolume: Int get() = max
    private var max = 60

    private fun base() = if (version >= 6) "https://$host:1926/6" else "http://$host:1925/1"

    private fun call(method: String, path: String, body: String? = null, user: String = deviceId, password: String = key): MiniHttp.Response {
        val url = base() + path
        var r = MiniHttp.request(method, url, body, timeoutMs = timeoutMs, trustAll = true)
        if (r.code == 401 && user.isNotBlank()) {
            val challenge = r.header("WWW-Authenticate") ?: throw ControlError("philips: 401 without a challenge")
            val uri = "/6$path"
            r = MiniHttp.request(method, url, body, mapOf("Authorization" to Digest.authorization(user, password, method, uri, challenge)), timeoutMs, trustAll = true)
        }
        if (!r.ok) throw ControlError("philips $path: HTTP ${r.code}")
        return r
    }

    override fun getVolume(): Int? = try {
        val body = call("GET", "/audio/volume").body
        MiniHttp.find(body, "\"max\"\\s*:\\s*(\\d+)")?.toIntOrNull()?.let { max = it }
        MiniHttp.find(body, "\"current\"\\s*:\\s*(\\d+)")?.toIntOrNull()
    } catch (e: ControlError) { null }

    override fun setVolume(level: Int): Boolean { call("POST", "/audio/volume", """{"current":${level.coerceIn(0, max)},"muted":false}"""); return true }
    override fun setMute(on: Boolean): Boolean { call("POST", "/audio/volume", """{"muted":$on}"""); return true }

    fun key(name: String): Boolean { call("POST", "/input/key", """{"key":"$name"}"""); return true }
    fun press(key: RemoteKey): Boolean { val name = REMOTE[key] ?: return false; return key(name) }

    // ---- pairing, version 6 ----

    class PairingStart(val timestamp: Long, val authKey: String)

    private fun device(id: String) = """{"device_name":"AdHush","device_os":"Android","app_name":"AdHush","type":"native","app_id":"io.adhush","id":"$id"}"""

    /** Step one: the set shows a PIN; keep what it returned for step two. */
    fun pairRequest(id: String): PairingStart {
        val r = MiniHttp.post("https://$host:1926/6/pair/request", """{"scope":["read","write","control"],"device":${device(id)}}""", timeoutMs = timeoutMs, trustAll = true)
        val ts = MiniHttp.find(r.body, "\"timestamp\"\\s*:\\s*(\\d+)")?.toLongOrNull() ?: throw ControlError("philips: pairing did not start (${r.code})")
        val authKey = MiniHttp.find(r.body, "\"auth_key\"\\s*:\\s*\"([^\"]+)\"") ?: throw ControlError("philips: no auth key")
        return PairingStart(ts, authKey)
    }

    /** Step two: the PIN from the screen; on success the credentials are (id, authKey). */
    fun pairGrant(id: String, start: PairingStart, pin: String): Boolean {
        val body = """{"auth":{"auth_timestamp":${start.timestamp},"pin":"$pin","auth_signature":"${signature(start.timestamp, pin)}","auth_AppId":"1"},"device":${device(id)}}"""
        val url = "https://$host:1926/6/pair/grant"
        var r = MiniHttp.post(url, body, timeoutMs = timeoutMs, trustAll = true)
        if (r.code == 401) {
            val challenge = r.header("WWW-Authenticate") ?: return false
            r = MiniHttp.post(url, body, mapOf("Authorization" to Digest.authorization(id, start.authKey, "POST", "/6/pair/grant", challenge)), timeoutMs, trustAll = true)
        }
        return r.ok && "SUCCESS" in r.body
    }

    companion object {
        /** Philips' published pairing secret, the same in every client that pairs with these sets. */
        const val SECRET_B64 = "ZmVay1EQVFOaZhwQ4Kv81ypLAZNczV9sG4KkseXWn1NEk6cXmPKO/MCa9sryslvLCFMnNe4Z4CPXzToowvhHvA=="
        fun signature(timestamp: Long, pin: String): String {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(Base64.getDecoder().decode(SECRET_B64), "HmacSHA1"))
            return Base64.getEncoder().encodeToString(mac.doFinal("$timestamp$pin".toByteArray(Charsets.UTF_8)))
        }
        val REMOTE: Map<RemoteKey, String> = mapOf(
            RemoteKey.POWER to "Standby", RemoteKey.VOL_UP to "VolumeUp", RemoteKey.VOL_DOWN to "VolumeDown", RemoteKey.MUTE to "Mute",
            RemoteKey.UP to "CursorUp", RemoteKey.DOWN to "CursorDown", RemoteKey.LEFT to "CursorLeft", RemoteKey.RIGHT to "CursorRight", RemoteKey.ENTER to "Confirm",
            RemoteKey.RETURN to "Back", RemoteKey.EXIT to "Home", RemoteKey.MENU to "Options", RemoteKey.SMART to "Home", RemoteKey.INPUT to "Source",
            RemoteKey.CH_UP to "ChannelStepUp", RemoteKey.CH_DOWN to "ChannelStepDown", RemoteKey.DISPLAY to "Info", RemoteKey.CC to "Subtitle",
            RemoteKey.DIGIT_0 to "Digit0", RemoteKey.DIGIT_1 to "Digit1", RemoteKey.DIGIT_2 to "Digit2", RemoteKey.DIGIT_3 to "Digit3", RemoteKey.DIGIT_4 to "Digit4",
            RemoteKey.DIGIT_5 to "Digit5", RemoteKey.DIGIT_6 to "Digit6", RemoteKey.DIGIT_7 to "Digit7", RemoteKey.DIGIT_8 to "Digit8", RemoteKey.DIGIT_9 to "Digit9",
            RemoteKey.PLAY to "Play", RemoteKey.PAUSE to "Pause", RemoteKey.STOP to "Stop", RemoteKey.REW to "Rewind", RemoteKey.FF to "FastForward",
            RemoteKey.RED to "RedColour", RemoteKey.GREEN to "GreenColour", RemoteKey.YELLOW to "YellowColour", RemoteKey.BLUE to "BlueColour",
        )

        /** Which JointSpace, if any: 6 on 1926, else 1 on 1925. */
        fun probe(host: String, timeoutMs: Int = 1500): PhilipsInfo? {
            for ((version, url) in listOf(6 to "https://$host:1926/6/system", 1 to "http://$host:1925/1/system")) {
                val r = try { MiniHttp.get(url, timeoutMs = timeoutMs, trustAll = true) } catch (e: Exception) { continue }
                if (!r.ok) continue
                val name = MiniHttp.find(r.body, "\"name\"\\s*:\\s*\"([^\"]*)\"") ?: ""
                val model = MiniHttp.find(r.body, "\"model\"\\s*:\\s*\"([^\"]*)\"") ?: MiniHttp.find(r.body, "\"softwareversion\"\\s*:\\s*\"([^\"]*)\"") ?: ""
                return PhilipsInfo(version, name, model)
            }
            return null
        }
    }
}
