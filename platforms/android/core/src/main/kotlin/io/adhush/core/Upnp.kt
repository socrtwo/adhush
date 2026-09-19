package io.adhush.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI

/** One answer to an SSDP search. */
data class SsdpHit(val ip: String, val location: String, val server: String, val st: String, val usn: String)

/**
 * SSDP (ADR 0025): one multicast question on 239.255.255.250:1900 and every
 * UPnP device in the house answers with where its description lives. Smart
 * TVs of every brand do; that description names the maker and the model.
 */
object Ssdp {
    const val GROUP = "239.255.255.250"
    const val PORT = 1900

    fun search(timeoutMs: Int = 3000, st: String = "ssdp:all"): List<SsdpHit> {
        val request = "M-SEARCH * HTTP/1.1\r\nHOST: $GROUP:$PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val hits = LinkedHashMap<String, SsdpHit>()
        DatagramSocket().use { sock ->
            sock.soTimeout = 500
            val group = InetAddress.getByName(GROUP)
            repeat(2) { sock.send(DatagramPacket(request, request.size, group, PORT)) }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try { sock.receive(packet) } catch (e: SocketTimeoutException) { continue }
                val hit = parse(String(packet.data, 0, packet.length, Charsets.ISO_8859_1), packet.address.hostAddress ?: continue) ?: continue
                hits.putIfAbsent(hit.location, hit)
            }
        }
        return hits.values.toList()
    }

    fun parse(response: String, ip: String): SsdpHit? {
        if (!response.startsWith("HTTP/1.1 200")) return null
        val headers = HashMap<String, String>()
        for (line in response.lineSequence().drop(1)) {
            val i = line.indexOf(':'); if (i <= 0) continue
            headers[line.substring(0, i).trim().uppercase()] = line.substring(i + 1).trim()
        }
        val location = headers["LOCATION"] ?: return null
        return SsdpHit(ip, location, headers["SERVER"] ?: "", headers["ST"] ?: "", headers["USN"] ?: "")
    }
}

/** What a device description says about itself, and where its volume control lives if it has one. */
data class UpnpDescription(val friendlyName: String, val manufacturer: String, val modelName: String, val renderingControlUrl: String?)

object Upnp {
    fun describe(location: String, timeoutMs: Int = 3000): UpnpDescription? {
        val r = try { MiniHttp.get(location, timeoutMs = timeoutMs) } catch (e: Exception) { return null }
        if (!r.ok) return null
        return parseDescription(r.body, location)
    }

    /** Tag text by name, whatever the namespace prefix. */
    private fun tag(xml: String, name: String): String =
        MiniHttp.find(xml, "<(?:[a-zA-Z0-9]+:)?$name(?:\\s[^>]*)?>([^<]*)</(?:[a-zA-Z0-9]+:)?$name>")?.trim() ?: ""

    fun parseDescription(xml: String, location: String): UpnpDescription {
        val base = tag(xml, "URLBase").ifEmpty { location }
        var control: String? = null
        for (service in Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            val block = service.groupValues[1]
            if ("RenderingControl" in tag(block, "serviceType")) { control = resolve(base, tag(block, "controlURL")); break }
        }
        return UpnpDescription(tag(xml, "friendlyName"), tag(xml, "manufacturer"), tag(xml, "modelName"), control)
    }

    fun resolve(base: String, path: String): String = try { URI(base).resolve(path).toString() } catch (e: Exception) { path }
}

/** A DLNA renderer's RenderingControl, which on most TVs is the set's own volume (Samsung, LG, Sony, Panasonic, Philips). */
class UpnpRenderer(private val controlUrl: String, private val timeoutMs: Int = 3000) : VolumeDevice {
    override val maxVolume: Int get() = 100

    private fun soap(action: String, args: String): String {
        val body = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"><InstanceID>0</InstanceID><Channel>Master</Channel>$args</u:$action></s:Body></s:Envelope>"""
        val r = MiniHttp.post(controlUrl, body, mapOf("SOAPACTION" to "\"urn:schemas-upnp-org:service:RenderingControl:1#$action\""), timeoutMs, contentType = "text/xml; charset=\"utf-8\"")
        if (!r.ok) throw ControlError("upnp $action: HTTP ${r.code}")
        return r.body
    }

    override fun getVolume(): Int? = try { MiniHttp.find(soap("GetVolume", ""), "<CurrentVolume>\\s*(\\d+)\\s*</CurrentVolume>")?.toIntOrNull() } catch (e: ControlError) { null }
    override fun setVolume(level: Int): Boolean { soap("SetVolume", "<DesiredVolume>${level.coerceIn(0, maxVolume)}</DesiredVolume>"); return true }
    override fun setMute(on: Boolean): Boolean { soap("SetMute", "<DesiredMute>${if (on) 1 else 0}</DesiredMute>"); return true }

    companion object { fun parseVolume(body: String): Int? = MiniHttp.find(body, "<CurrentVolume>\\s*(\\d+)\\s*</CurrentVolume>")?.toIntOrNull() }
}
