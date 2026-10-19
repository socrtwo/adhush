package io.adhush.core

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One way in to one set. */
enum class TvPathKind(val wire: String, val label: String, val exact: Boolean) {
    SHARP("ip", "Sharp control port", true),
    SONY("sony", "Sony Bravia (needs the pre-shared key)", true),
    LG("lg", "LG webOS (allow it on the set once)", true),
    UPNP("upnp", "UPnP renderer", true),
    SAMSUNG("samsung", "Samsung remote channel (allow it on the set once)", false),
    ROKU("roku", "Roku TV", false),
    VIZIO("vizio", "Vizio SmartCast (PIN on the screen once)", false),
    ANDROID_TV("androidtv", "Android TV / Google TV (a code on the screen once)", true),
    HISENSE("hisense", "Hisense VIDAA (a code on the screen once)", true),
    PHILIPS("philips", "Philips JointSpace (a PIN on the screen once on the newer sets)", true);

    companion object { fun ofWire(w: String): TvPathKind? = entries.firstOrNull { it.wire == w } }
}

data class TvPath(val kind: TvPathKind, val ip: String, val detail: String = "")

class FoundTv(val ip: String, var brand: String, var model: String, var name: String) {
    val paths = ArrayList<TvPath>()
    /** Exact-volume paths first, then key paths, in the order the wizard tries them. */
    fun ordered(): List<TvPath> = paths.sortedBy { PRIORITY.indexOf(it.kind) }
    override fun toString() = "$brand ${model.ifBlank { name }} at $ip: " + paths.joinToString { it.kind.label }
    companion object { val PRIORITY = listOf(TvPathKind.SHARP, TvPathKind.SONY, TvPathKind.LG, TvPathKind.PHILIPS, TvPathKind.HISENSE, TvPathKind.UPNP, TvPathKind.ANDROID_TV, TvPathKind.SAMSUNG, TvPathKind.ROKU, TvPathKind.VIZIO) }
}

/**
 * Finds the sets on the network and every way in to each (ADR 0025): one SSDP
 * search names the makers and finds the renderers; each address is then
 * asked the brand questions that need no key — a Roku's device info, a
 * Samsung's `/api/v2/`, a Sony's interface information, a Vizio's power
 * state, an LG's open port 3000, a Sharp's `VOLM?`. With nothing on SSDP the
 * subnet is swept for the brand ports. Nothing here changes a set.
 */
object TvFinder {
    val BRAND_PORTS = listOf(10002, 8060, 8001, 3000, 7345, 80, 6467, 36669, 1925, 1926)

    /** A maker's name from what SSDP and the description say. */
    fun inferBrand(server: String, manufacturer: String, model: String): String {
        val text = "$server $manufacturer $model".lowercase()
        return when {
            "samsung" in text -> "Samsung"
            "webos" in text || " lg " in " $text " || "lg electronics" in text || text.startsWith("lg") -> "LG"
            "sony" in text || "bravia" in text -> "Sony"
            "roku" in text -> "Roku TV"
            "vizio" in text -> "Vizio"
            "sharp" in text || "aquos" in text -> "Sharp"
            "panasonic" in text || "viera" in text -> "Panasonic"
            "philips" in text -> "Philips"
            "hisense" in text -> "Hisense"
            "tcl" in text -> "TCL"
            "toshiba" in text -> "Toshiba"
            manufacturer.isNotBlank() -> manufacturer.trim().split(" ").first()
            else -> ""
        }
    }

    fun find(
        subnetBase: String?,
        sharpPort: Int = 10002,
        sharpLogin: Pair<String, String>? = null,
        log: (String) -> Unit = {},
        ssdpMs: Int = 3000,
    ): List<FoundTv> {
        val found = LinkedHashMap<String, FoundTv>()
        fun tv(ip: String): FoundTv = found.getOrPut(ip) { FoundTv(ip, "", "", "") }

        // 1. SSDP: makers, models and renderers.
        val hits = try { Ssdp.search(ssdpMs) } catch (e: Exception) { log("ssdp: ${e.message}"); emptyList() }
        log("ssdp: ${hits.size} answer(s)")
        for (hit in hits) {
            val desc = Upnp.describe(hit.location) ?: continue
            val brand = inferBrand(hit.server, desc.manufacturer, desc.modelName)
            val t = tv(hit.ip)
            if (t.brand.isBlank()) t.brand = brand
            if (t.model.isBlank()) t.model = desc.modelName
            if (t.name.isBlank()) t.name = desc.friendlyName
            if (desc.renderingControlUrl != null && t.paths.none { it.kind == TvPathKind.UPNP }) t.paths.add(TvPath(TvPathKind.UPNP, hit.ip, desc.renderingControlUrl))
        }

        // 2. The brand questions, on every address SSDP named — and, with none, on the whole subnet.
        val candidates = LinkedHashSet(found.keys)
        if (candidates.isEmpty() && subnetBase != null) {
            log("nothing on ssdp: sweeping $subnetBase.0/24 for the brand ports")
            candidates.addAll(sweep(subnetBase, BRAND_PORTS + sharpPort))
        }
        val pool = Executors.newFixedThreadPool(8)
        for (ip in candidates) pool.execute { probe(tv(ip), sharpPort, sharpLogin) }
        pool.shutdown(); pool.awaitTermination(60, TimeUnit.SECONDS)
        val sets = found.values.filter { it.paths.isNotEmpty() || it.brand.isNotBlank() }
        for (s in sets) log(s.toString())
        return sets.sortedByDescending { it.paths.size }
    }

    /** Addresses on the subnet with any of the ports open (300 ms each, 32 at a time). */
    fun sweep(subnetBase: String, ports: List<Int>): List<String> {
        val open = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
        val pool = Executors.newFixedThreadPool(32)
        for (i in 1..254) for (port in ports.distinct()) pool.execute {
            val ip = "$subnetBase.$i"
            try { Socket().use { it.connect(InetSocketAddress(ip, port), 300) }; open.add(ip) } catch (e: Exception) { /* closed */ }
        }
        pool.shutdown(); pool.awaitTermination(40, TimeUnit.SECONDS)
        return open.toList().sortedBy { it.substringAfterLast('.').toInt() }
    }

    private fun portOpen(ip: String, port: Int, ms: Int = 400): Boolean = try { Socket().use { it.connect(InetSocketAddress(ip, port), ms) }; true } catch (e: Exception) { false }

    /** The brand questions for one address the owner typed; null when nothing answers. */
    fun probeHost(ip: String, sharpPort: Int = 10002, sharpLogin: Pair<String, String>? = null): FoundTv? {
        val t = FoundTv(ip, "", "", "")
        probe(t, sharpPort, sharpLogin)
        return if (t.paths.isEmpty() && t.brand.isBlank()) null else t
    }

    private fun probe(t: FoundTv, sharpPort: Int, sharpLogin: Pair<String, String>?) {
        val ip = t.ip
        fun add(kind: TvPathKind, detail: String = "") { synchronized(t) { if (t.paths.none { it.kind == kind }) t.paths.add(TvPath(kind, ip, detail)) } }
        RokuEcp.probe(ip)?.let { info -> synchronized(t) { if (t.brand.isBlank() || t.brand == "Roku TV") t.brand = "Roku TV"; if (t.model.isBlank()) t.model = info.model; if (t.name.isBlank()) t.name = info.name }; if (info.isTv) add(TvPathKind.ROKU) }
        SamsungTizen.probe(ip)?.let { info -> synchronized(t) { t.brand = "Samsung"; if (t.model.isBlank()) t.model = info.model; if (t.name.isBlank()) t.name = info.name }; add(TvPathKind.SAMSUNG) }
        if (portOpen(ip, 80)) SonyBravia.probe(ip)?.let { info -> synchronized(t) { t.brand = "Sony"; if (t.model.isBlank()) t.model = info.model }; add(TvPathKind.SONY) }
        if (portOpen(ip, 7345) && VizioSmartCast.probe(ip)) { synchronized(t) { t.brand = "Vizio" }; add(TvPathKind.VIZIO) }
        if (portOpen(ip, 3000) || portOpen(ip, 3001)) { synchronized(t) { if (t.brand.isBlank()) t.brand = "LG" }; if (t.brand == "LG") add(TvPathKind.LG) }
        if (AndroidTvRemote.probe(ip)) { synchronized(t) { if (t.brand.isBlank()) t.brand = "Android TV" }; add(TvPathKind.ANDROID_TV) }
        if (HisenseVidaa.probe(ip)) { synchronized(t) { if (t.brand.isBlank() || t.brand == "Android TV") t.brand = "Hisense" }; add(TvPathKind.HISENSE) }
        if (portOpen(ip, 1926) || portOpen(ip, 1925)) PhilipsJointSpace.probe(ip)?.let { info -> synchronized(t) { t.brand = "Philips"; if (t.model.isBlank()) t.model = info.model; if (t.name.isBlank()) t.name = info.name }; add(TvPathKind.PHILIPS, info.version.toString()) }
        if (portOpen(ip, sharpPort)) {
            val vol = try { SocketTransport(ip, sharpPort, 1500, sharpLogin).use { SharpIpClient(it).queryVolume() } } catch (e: Exception) { null }
            if (vol != null) { synchronized(t) { t.brand = "Sharp" }; add(TvPathKind.SHARP, vol.toString()) }
        }
    }
}
