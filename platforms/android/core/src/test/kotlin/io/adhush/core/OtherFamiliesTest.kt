package io.adhush.core

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR 0026: Android TV's protobuf and pairing hash, Hisense's MQTT, Philips' digest — nothing touches a network but localhost. */
class OtherFamiliesTest {
    @Test fun `protobuf round-trips nested messages, strings and varints`() {
        val inner = MiniProto.Writer().string(1, "androidtvremote").string(2, "AdHush")
        val outer = MiniProto.Writer().int(1, 2).int(2, 200).message(10, inner).bool(8, true).varint(9, 300)
        val m = MiniProto.parse(outer.toByteArray())
        assertEquals(2, m.int(1)); assertEquals(200, m.int(2)); assertEquals(1L, m.long(8)); assertEquals(300L, m.long(9))
        val i = m.message(10); assertNotNull(i); assertEquals("AdHush", i.string(2))
        val framed = MiniProto.framed(outer.toByteArray())
        assertEquals(outer.toByteArray().size, framed[0].toInt())
        assertTrue(MiniProto.readFrame(framed.inputStream())!!.contentEquals(outer.toByteArray()))
        assertNull(MiniProto.readFrame(ByteArray(0).inputStream()))
    }

    @Test fun `a client identity is made, kept, and hashed into a pairing secret`() {
        val id = ClientIdentity.generate()
        assertEquals("CN=AdHush", id.certificate.subjectX500Principal.name)
        val back = ClientIdentity.deserialize(id.serialize())
        assertEquals(id.publicKey.modulus, back.publicKey.modulus)
        assertTrue(ClientIdentity.magnitude(id.publicKey.modulus).size == 256)
        val other = ClientIdentity.generate()
        val s1 = AndroidTvRemote.secret(id.publicKey, other.publicKey, "1A2B3C")
        val s2 = AndroidTvRemote.secret(id.publicKey, other.publicKey, "1A2B3C")
        assertEquals(32, s1.size); assertTrue(s1.contentEquals(s2))
        assertTrue(!s1.contentEquals(AndroidTvRemote.secret(id.publicKey, other.publicKey, "1A2B3D")), "the code is in the hash")
        assertTrue(byteArrayOf(0x2B, 0x3C).contentEquals(AndroidTvRemote.hex("2B3C")))
        assertEquals(164, AndroidTvRemote.REMOTE[RemoteKey.MUTE])
        assertNotNull(id.sslContext())
    }

    @Test fun `MQTT connects, subscribes and receives a publish from a loopback broker`() {
        val server = ServerSocket(0)
        val thread = Thread {
            server.accept().use { s ->
                val i = s.getInputStream(); val o = s.getOutputStream()
                fun packet(): Pair<Int, ByteArray> { val t = i.read(); var mult = 1; var len = 0; do { val b = i.read(); len += (b and 0x7F) * mult; mult *= 128 } while (b and 0x80 != 0); val body = ByteArray(len); var off = 0; while (off < len) off += i.read(body, off, len - off); return Pair(t, body) }
                val (t1, connect) = packet()
                assertEquals(0x10, t1); assertTrue(String(connect).contains("hisenseservice"))
                o.write(MiniMqtt.encode(0x20, byteArrayOf(0, 0))); o.flush()
                val (t2, sub) = packet(); assertEquals(0x82, t2); assertTrue(String(sub).contains("/remoteapp/mobile/#"))
                o.write(MiniMqtt.encode(0x90, byteArrayOf(sub[0], sub[1], 0))); o.flush()
                val (t3, pub) = packet(); assertEquals(0x30, t3 and 0xF0)
                val payload = """{"volume_type":0,"volume_value":13}""".toByteArray()
                o.write(MiniMqtt.encode(0x30, MiniMqtt.string("/remoteapp/mobile/x/platform_service/data/getvolume") + payload)); o.flush()
                assertTrue(pub.isNotEmpty())
            }
        }
        thread.start()
        MiniMqtt("127.0.0.1", server.localPort, tls = false, timeoutMs = 3000).use { m ->
            m.connect("adhush-test\$normal", HisenseVidaa.USERNAME, HisenseVidaa.PASSWORD)
            m.subscribe("/remoteapp/mobile/#")
            m.publish("/remoteapp/tv/platform_service/adhush-test\$normal/actions/getvolume", ByteArray(0))
            val p = m.next(3000)
            assertNotNull(p); assertTrue("getvolume" in p.topic); assertEquals(13, HisenseVidaa.parseVolume(String(p.payload)))
        }
        thread.join(3000); server.close()
        assertTrue(HisenseVidaa.newClientId().endsWith("\$normal"))
    }

    @Test fun `digest authentication matches the RFC 2617 example`() {
        val header = "Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""
        val c = Digest.challenge(header)
        assertEquals("testrealm@host.com", c["realm"]); assertEquals("auth,auth-int", c["qop"])
        val resp = Digest.response("Mufasa", "Circle Of Life", "GET", "/dir/index.html", "testrealm@host.com", "dcd98b7102dd2f0e8b11d0f600bfb0c093", "auth", "00000001", "0a4f113b")
        assertEquals("6629fae49393a05397450978507c4ef1", resp)
        val auth = Digest.authorization("Mufasa", "Circle Of Life", "GET", "/dir/index.html", header)
        assertTrue("response=\"6629fae49393a05397450978507c4ef1\"" in auth && "opaque=" in auth)
        assertEquals(28, PhilipsJointSpace.signature(1234567, "1234").length)
        assertEquals("Mute", PhilipsJointSpace.REMOTE[RemoteKey.MUTE])
        assertEquals("KEY_MUTE", HisenseVidaa.REMOTE[RemoteKey.MUTE])
    }

    @Test fun `the new paths order after the readback ones they beat`() {
        val t = FoundTv("1.2.3.4", "Hisense", "", "")
        t.paths.add(TvPath(TvPathKind.ANDROID_TV, t.ip)); t.paths.add(TvPath(TvPathKind.HISENSE, t.ip)); t.paths.add(TvPath(TvPathKind.UPNP, t.ip, "u"))
        assertEquals(listOf(TvPathKind.HISENSE, TvPathKind.UPNP, TvPathKind.ANDROID_TV), t.ordered().map { it.kind })
        assertEquals(TvPathKind.PHILIPS, TvPathKind.ofWire("philips"))
        assertTrue(TvFinder.BRAND_PORTS.containsAll(listOf(6467, 36669, 1925, 1926)))
    }
}
