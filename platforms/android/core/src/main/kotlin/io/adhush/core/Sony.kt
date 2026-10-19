package io.adhush.core

data class SonyInfo(val model: String, val category: String)

/**
 * Sony Bravia's JSON-RPC (ADR 0025): `POST /sony/audio` with the pre-shared
 * key the owner set under Network → Home Network → IP Control → Pre-Shared
 * Key. Volume is read and set exactly; mute is a call of its own.
 */
class SonyBravia(private val host: String, private val psk: String, private val timeoutMs: Int = 3000) : VolumeDevice {
    override val maxVolume: Int get() = max
    private var max = 100
    private var id = 1

    private fun rpc(service: String, method: String, params: String, version: String): String {
        val body = """{"method":"$method","id":${id++},"params":[$params],"version":"$version"}"""
        val r = MiniHttp.post("http://$host/sony/$service", body, mapOf("X-Auth-PSK" to psk), timeoutMs)
        if (!r.ok) throw ControlError("sony $method: HTTP ${r.code}")
        MiniHttp.find(r.body, "\"error\"\\s*:\\s*\\[\\s*(\\d+)")?.let { throw ControlError("sony $method: error $it") }
        return r.body
    }

    override fun getVolume(): Int? = try {
        val body = rpc("audio", "getVolumeInformation", "", "1.0")
        parseVolume(body)?.also { (v, m) -> max = m }?.first
    } catch (e: ControlError) { null }

    override fun setVolume(level: Int): Boolean { rpc("audio", "setAudioVolume", """{"target":"speaker","volume":"${level.coerceIn(0, max)}"}""", "1.0"); return true }
    override fun setMute(on: Boolean): Boolean { rpc("audio", "setAudioMute", """{"status":$on}""", "1.0"); return true }

    companion object {
        /** (volume, maxVolume) of the speaker target, or the first target listed. */
        fun parseVolume(body: String): Pair<Int, Int>? {
            val speaker = Regex("\\{[^{}]*\"target\"\\s*:\\s*\"speaker\"[^{}]*}").find(body)?.value ?: Regex("\\{[^{}]*\"volume\"[^{}]*}").find(body)?.value ?: return null
            val v = MiniHttp.find(speaker, "\"volume\"\\s*:\\s*(\\d+)")?.toIntOrNull() ?: return null
            val m = MiniHttp.find(speaker, "\"maxVolume\"\\s*:\\s*(\\d+)")?.toIntOrNull() ?: 100
            return Pair(v, m)
        }

        /** No key needed for this one: is there a Bravia at this address? */
        fun probe(host: String, timeoutMs: Int = 1500): SonyInfo? {
            val body = """{"method":"getInterfaceInformation","id":1,"params":[],"version":"1.0"}"""
            val r = try { MiniHttp.post("http://$host/sony/system", body, timeoutMs = timeoutMs) } catch (e: Exception) { return null }
            if (!r.ok) return null
            val model = MiniHttp.find(r.body, "\"modelName\"\\s*:\\s*\"([^\"]*)\"") ?: return null
            return SonyInfo(model, MiniHttp.find(r.body, "\"productCategory\"\\s*:\\s*\"([^\"]*)\"") ?: "")
        }
    }
}
