package io.adhush.core

import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR 0025: every brand's infrared, the generic level controller, and the drivers' parsing. Nothing here touches a network but localhost. */
class AnyBrandTest {
    // ---- infrared ----
    @Test fun `NEC is a 9 ms leader and 32 bits with the inverses`() {
        val p = NecIr.press(0x04, 0x09)   // LG mute
        assertEquals(2 + 64 + 1, p.size)
        assertEquals(9000, p[0]); assertEquals(4500, p[1]); assertEquals(560, p.last())
        val spaces = (0 until 32).map { p[3 + 2 * it] }
        val bits = spaces.map { if (it == 1690) 1 else 0 }
        assertEquals(lsbFirst(0x04, 8) + lsbFirst(0xFB, 8) + lsbFirst(0x09, 8) + lsbFirst(0xF6, 8), bits)
    }

    @Test fun `Samsung sends the address twice, un-inverted`() {
        val p = SamsungIr.press(0x07, 0x0F)
        assertEquals(4500, p[0]); assertEquals(4500, p[1])
        val bits = (0 until 32).map { if (p[3 + 2 * it] == 1690) 1 else 0 }
        assertEquals(lsbFirst(0x07, 8) + lsbFirst(0x07, 8) + lsbFirst(0x0F, 8) + lsbFirst(0xF0, 8), bits)
    }

    @Test fun `Sony SIRC is twelve bits sent three times at 45 ms`() {
        val f = SircIr.frame(1, 20)
        assertEquals(2 + 24, f.size)
        assertEquals(2400, f[0])
        val marks = (0 until 12).map { f[2 + 2 * it] }
        assertEquals(lsbFirst(20, 7) + lsbFirst(1, 5), marks.map { if (it == 1200) 1 else 0 })
        val p = SircIr.press(1, 20)
        assertEquals(3 * f.size, p.size)
        assertEquals(45_000, f.sum() - f.last() + p[f.size - 1], "the gap pads each frame to 45 ms")
    }

    @Test fun `RC-5 is fourteen Manchester bits starting with a mark`() {
        val p = Rc5Ir.press(0, 13)
        assertTrue(p.all { it == 889 || it == 1778 }, p.joinToString())
        assertTrue(p.sum() <= 14 * 1778 && p.sum() >= 13 * 1778 - 889)
    }

    @Test fun `Panasonic carries the vendor code and a parity byte`() {
        val p = PanasonicIr.press(0x80, 0x32)
        assertEquals(2 + 96 + 1, p.size)
        val bits = (0 until 48).map { if (p[3 + 2 * it] == 1300) 1 else 0 }
        assertEquals(lsbFirst(0x02, 8) + lsbFirst(0x20, 8), bits.take(16))
        assertEquals(lsbFirst(0x80 xor 0x32, 8), bits.takeLast(8))
    }

    @Test fun `the code table tries the named brand first`() {
        assertEquals("sony", IrCodeSets.ordered("Sony Bravia").first().name)
        assertEquals(IrCodeSets.KNOWN.size, IrCodeSets.ordered("nobody").size)
        assertEquals("sharp", IrCodeSets.ordered(null).first().name)
        assertEquals(38_000, IrCodeSets.byName("lg")!!.protocol.carrierHz)
        assertTrue(IrCodeSets.byName("vizio")!!.pattern(TvKey.MUTE).contentEquals(IrCodeSets.byName("lg")!!.pattern(TvKey.MUTE)))
    }

    // ---- the generic level controller ----
    private class FakeSet(var volume: Int = 20, override val maxVolume: Int = 100) : VolumeDevice {
        var muted = false
        override fun getVolume(): Int? = volume
        override fun setVolume(level: Int): Boolean { volume = level; return true }
        override fun setMute(on: Boolean): Boolean { muted = on; return true }
    }

    @Test fun `a level controller ducks to the level and restores, and the remote wins`() {
        val set = FakeSet(volume = 23)
        val c = LevelController(set, MemoryDuckPersistence(), duckLevel = 4, normalVolume = 20)
        c.duck()
        assertEquals(4, set.volume); assertTrue(c.ducked); assertEquals(23, c.normalVolume)
        set.volume = 30                               // the user turned it up while ducked
        assertTrue(c.pollUserOverride()); assertFalse(c.ducked); assertEquals(30, c.normalVolume)
        c.duck(); c.restore()
        assertEquals(30, set.volume)
        val muter = LevelController(set, MemoryDuckPersistence(), useMuteInstead = true)
        muter.mute(); assertTrue(set.muted); muter.unmute(); assertFalse(set.muted)
    }

    @Test fun `the Sharp client is a volume device`() {
        val fake = object : AquosTransport { override fun exchange(payload: ByteArray): ByteArray = if (String(payload).startsWith("VOLM?")) "19\r".toByteArray() else "OK\r".toByteArray() }
        val client = SharpIpClient(fake)
        assertEquals(60, client.maxVolume); assertEquals(19, client.getVolume()); assertTrue(client.setMute(true))
        val c = SharpController(client, MemoryDuckPersistence())
        c.duck(); assertTrue(c.ducked); assertEquals(19, c.normalVolume)
    }

    // ---- parsing ----
    @Test fun `a UPnP description names the maker and the renderer`() {
        val xml = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><URLBase>http://192.168.1.20:9197/</URLBase><device><friendlyName>[TV] Living room</friendlyName><manufacturer>Samsung Electronics</manufacturer><modelName>UN55TU8000</modelName><serviceList><service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/upnp/control/RenderingControl1</controlURL></service></serviceList></device></root>"""
        val d = Upnp.parseDescription(xml, "http://192.168.1.20:9197/dmr")
        assertEquals("Samsung Electronics", d.manufacturer); assertEquals("UN55TU8000", d.modelName)
        assertEquals("http://192.168.1.20:9197/upnp/control/RenderingControl1", d.renderingControlUrl)
        assertEquals("Samsung", TvFinder.inferBrand("", d.manufacturer, d.modelName))
        assertEquals(17, UpnpRenderer.parseVolume("<u:GetVolumeResponse><CurrentVolume>17</CurrentVolume></u:GetVolumeResponse>"))
        val hit = Ssdp.parse("HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.30:1914/\r\nSERVER: WebOS/5.0 UPnP/1.0\r\nST: upnp:rootdevice\r\n\r\n", "192.168.1.30")
        assertNotNull(hit); assertEquals("LG", TvFinder.inferBrand(hit.server, "", ""))
        assertNull(Ssdp.parse("NOTIFY * HTTP/1.1\r\n", "1.2.3.4"))
    }

    @Test fun `brand inference from the other makers`() {
        assertEquals("Sony", TvFinder.inferBrand("", "Sony Corporation", "KD-55X85K"))
        assertEquals("Roku TV", TvFinder.inferBrand("Roku/12.0 UPnP/1.0", "", ""))
        assertEquals("Panasonic", TvFinder.inferBrand("", "Panasonic", "Viera"))
        assertEquals("Vizio", TvFinder.inferBrand("", "VIZIO", "V505-J09"))
        assertEquals("Acme", TvFinder.inferBrand("", "Acme Displays", ""))
        assertEquals("", TvFinder.inferBrand("", "", ""))
    }

    @Test fun `Roku, Samsung, Sony and LG answers are read`() {
        val roku = RokuEcp.parseInfo("<device-info><model-name>55S405</model-name><vendor-name>TCL</vendor-name><friendly-device-name>Den TV</friendly-device-name><is-tv>true</is-tv></device-info>")
        assertNotNull(roku); assertEquals("TCL", roku.vendor); assertTrue(roku.isTv)
        assertEquals("VolumeMute", RokuEcp.REMOTE[RemoteKey.MUTE])
        val samsung = SamsungTizen.parseInfo("""{"device":{"name":"[TV] Samsung 7 Series","modelName":"UN65TU7000","type":"Samsung SmartTV"},"id":"uuid:1"}""")
        assertNotNull(samsung); assertEquals("UN65TU7000", samsung.model)
        assertEquals(Pair(31, 100), SonyBravia.parseVolume("""{"result":[[{"target":"speaker","volume":31,"mute":false,"maxVolume":100,"minVolume":0}]],"id":1}"""))
        assertEquals(12, LgWebOs.parseVolume("""{"type":"response","id":"req_1","payload":{"returnValue":true,"volume":12,"muted":false}}"""))
        assertEquals("KEY_MUTE", SamsungTizen.REMOTE[RemoteKey.MUTE])
        assertEquals(5 to 4, VizioSmartCast.REMOTE[RemoteKey.MUTE])
    }

    @Test fun `found sets order exact-volume paths first`() {
        val t = FoundTv("1.2.3.4", "Samsung", "X", "")
        t.paths.add(TvPath(TvPathKind.SAMSUNG, t.ip)); t.paths.add(TvPath(TvPathKind.UPNP, t.ip, "http://x"))
        assertEquals(listOf(TvPathKind.UPNP, TvPathKind.SAMSUNG), t.ordered().map { it.kind })
        assertEquals(TvPathKind.ROKU, TvPathKind.ofWire("roku"))
    }

    // ---- the WebSocket client against a loopback server ----
    @Test fun `the websocket client shakes hands, masks, and reads an echo`() {
        val server = ServerSocket(0)
        val thread = Thread {
            server.accept().use { s ->
                val input = s.getInputStream(); val out = s.getOutputStream()
                val req = StringBuilder()
                while (!req.endsWith("\r\n\r\n")) req.append(input.read().toChar())
                val key = Regex("Sec-WebSocket-Key: (\\S+)").find(req)!!.groupValues[1]
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray()); out.flush()
                // read one masked text frame from the client
                val b0 = input.read(); val b1 = input.read()
                assertEquals(0x81, b0); assertTrue(b1 and 0x80 != 0, "client frames are masked")
                val len = b1 and 0x7F
                val mask = ByteArray(4).also { input.read(it) }
                val data = ByteArray(len).also { input.read(it) }
                val text = String(ByteArray(len) { (data[it].toInt() xor mask[it % 4].toInt()).toByte() })
                // echo it back, unmasked, as a server does
                val payload = "echo:$text".toByteArray()
                out.write(byteArrayOf(0x81.toByte(), payload.size.toByte()) + payload); out.flush()
            }
        }
        thread.start()
        MiniWebSocket("ws://127.0.0.1:${server.localPort}/chat?x=1", 3000).use { ws ->
            ws.connect()
            ws.send("hello")
            assertEquals("echo:hello", ws.receive(3000))
        }
        thread.join(3000); server.close()
    }

    @Test fun `MiniHttp find reads a group`() {
        assertEquals("42", MiniHttp.find("""{"a":{"volume":42}}""", "\"volume\"\\s*:\\s*(\\d+)"))
        assertNull(MiniHttp.find("nothing", "(\\d+)"))
        ByteArrayInputStream(ByteArray(0)).close()
    }
}
