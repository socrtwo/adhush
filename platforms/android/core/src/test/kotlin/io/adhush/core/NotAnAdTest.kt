package io.adhush.core

import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The 0.14 log: the set ducked again seconds after every "Not an ad" because
 * the camera called the logo gone without ever having seen it. These pin the
 * fixes: no absence vote before a sighting, no vote at all on a partial
 * screen, a search window that follows a wandering box, and a quiet period
 * after the user's word.
 */
class NotAnAdTest {
    private val W = 320; private val H = 240
    private val screen = Box(40, 30, 280, 210)

    private fun frame(logo: Boolean, seed: Int, screenBox: Box = screen, logoShift: Int = 0): Gray {
        val r = Random(seed)
        val px = FloatArray(W * H) { 12f + r.nextInt(6) }
        val cx = 100 + (seed * 7) % 120; val cy = 80 + (seed * 11) % 90
        for (y in screenBox.y0 until screenBox.y1) for (x in screenBox.x0 until screenBox.x1) {
            val d = ((x - cx) * (x - cx) + (y - cy) * (y - cy)).toDouble()
            px[y * W + x] = (140 + 60 * sin(d / 900.0 + seed)).toFloat().coerceIn(80f, 200f)
        }
        if (logo) {
            val lx = screenBox.x1 - 34 + logoShift; val ly = screenBox.y1 - 22 + logoShift
            for (y in ly until ly + 14) for (x in lx until lx + 24) {
                val ring = (x - lx - 7) * (x - lx - 7) + (y - ly - 7) * (y - ly - 7)
                if ((ring in 16..36) || (x >= lx + 17 && y in ly + 3..ly + 11)) px[y * W + x] = 245f
            }
        }
        return Gray(W, H, px)
    }

    private fun template(): LogoTemplate {
        val finder = LogoFinder(); for (i in 0 until 40) finder.feed(frame(true, i))
        return assertNotNull(finder.result())
    }

    @Test fun `a logo never seen is never called absent`() {
        val det = LogoAbsenceDetector(HANDHELD_LOGO_CONFIG, template()); det.warmup()
        var ts = 0.0
        for (i in 0 until 20) { det.observeFrame(frame(false, 500 + i), ts); ts += 0.5 }   // ten seconds with no bug at all
        assertTrue(det.active, "the screen is in view")
        assertFalse(det.voting, "but the bug was never sighted, so the detector has no say")
        assertEquals(0.0, det.vote(ts).confidence, det.vote(ts).reason)
        assertEquals("looking for the bug", det.describe())
        for (i in 0 until 6) { det.observeFrame(frame(true, 600 + i), ts); ts += 0.5 }      // now it is seen
        assertTrue(det.sighted); assertTrue(det.voting); assertEquals("bug seen", det.describe())
        for (i in 0 until 8) { det.observeFrame(frame(false, 700 + i), ts); ts += 0.5 }     // and now it is gone
        assertEquals(1.0, det.vote(ts).confidence, det.vote(ts).reason)
        assertEquals("bug gone", det.describe())
        // "Not an ad": the belief is dropped and a fresh sighting is demanded.
        det.userSaysProgramme(ts)
        assertFalse(det.voting); assertEquals(0.0, det.vote(ts).confidence)
        for (i in 0 until 8) { det.observeFrame(frame(false, 800 + i), ts); ts += 0.5 }
        assertEquals(0.0, det.vote(ts).confidence, "still no sighting, still no vote: ${det.vote(ts).reason}")
    }

    @Test fun `half a TV in the picture is inert, not absent`() {
        val det = LogoAbsenceDetector(HANDHELD_LOGO_CONFIG, template()); det.warmup()
        var ts = 0.0
        for (i in 0 until 6) { det.observeFrame(frame(true, 900 + i), ts); ts += 0.5 }
        assertTrue(det.voting)
        // The phone drifts so the screen runs off the left edge of the frame: no bug in the crop, but no vote either.
        val partial = Box(0, 30, 200, 210)
        for (i in 0 until 10) { det.observeFrame(frame(false, 1000 + i, screenBox = partial), ts); ts += 0.5 }
        assertFalse(det.active); assertEquals("partial_screen", det.inertReason)
        assertFalse(det.voting); assertEquals(0.0, det.vote(ts).confidence)
        assertEquals("whole TV not in view", det.describe())
        assertFalse(Vision.screenComplete(Box(0, 10, 100, 60), 320, 240))
        assertFalse(Vision.screenComplete(Box(10, 10, 60, 100), 320, 240), "portrait box is not a TV")
        assertTrue(Vision.screenComplete(Box(10, 10, 170, 100), 320, 240))
        // The finder does not learn from partial screens either.
        val finder = LogoFinder()
        assertFalse(finder.feed(frame(true, 1, screenBox = partial))); assertEquals(1, finder.partialFrames); assertTrue(finder.lastPartial)
    }

    @Test fun `the search window follows a bug that sits a few pixels off`() {
        val t = template()
        val det = LogoAbsenceDetector(HANDHELD_LOGO_CONFIG, t); det.warmup()
        var ts = 0.0
        for (i in 0 until 8) { det.observeFrame(frame(true, 1100 + i, logoShift = -3), ts); ts += 0.5 }
        assertTrue(det.programPresent, "score ${det.lastScore} offset ${det.lastOffset}")
        assertTrue(det.lastOffset.first < 0 || det.lastOffset.second < 0, "the box slid towards the bug: ${det.lastOffset}")
        val fixed = LogoAbsenceDetector(HANDHELD_LOGO_CONFIG.copy(searchPx = 0), t); fixed.warmup()
        ts = 0.0
        for (i in 0 until 8) { fixed.observeFrame(frame(true, 1100 + i, logoShift = -3), ts); ts += 0.5 }
        assertTrue(fixed.lastScore < det.lastScore, "sliding helps: ${fixed.lastScore} vs ${det.lastScore}")
        // The window must not conjure a bug out of programme content.
        for (i in 0 until 10) { det.observeFrame(frame(false, 1200 + i), ts); ts += 0.5 }
        assertEquals(1.0, det.vote(ts).confidence, det.vote(ts).reason)
    }

    private class Ctl : MuteController {
        var muted = false; var mutes = 0
        override fun mute() { muted = true; mutes++ }
        override fun unmute() { muted = false }
        override fun state() = muted
        override fun close() {}
    }

    /** A detector that says "ad" whenever told to. */
    private class Puppet(override val name: String) : Detector {
        var saying = 0.0; var told = 0
        override fun warmup() {}
        override fun observeAudio(block: AudioBlock) {}
        override fun vote(ts: Double) = vote(ts, saying, "puppet")
        override fun userSaysProgramme(ts: Double) { told++; saying = 0.0 }
    }

    @Test fun `after Not an ad nothing ducks for a minute, then the detectors count again`() {
        val ctl = Ctl()
        val puppet = Puppet("puppet")
        val fusion = Fusion(FusionConfig(), mapOf("puppet" to 0.45), listOf("puppet"))
        val engine = Engine(listOf(puppet), fusion, AdStateMachine(FusionConfig()), ctl, notAdQuietS = 60.0)
        var ts = 0.0
        fun tick(n: Int) { repeat(n) { engine.onAudio(AudioBlock(ts, FloatArray(4800), 48_000)); ts += 0.1 } }
        puppet.saying = 1.0; tick(15)
        assertTrue(ctl.muted, "the puppet ducks the set on its own")
        assertTrue(engine.rejectAd(ts))
        assertFalse(ctl.muted); assertEquals(1, puppet.told, "the detector was told it was wrong")
        assertTrue(engine.status().quietS > 55.0)
        puppet.saying = 1.0; tick(300)   // thirty seconds of the puppet insisting
        assertFalse(ctl.muted, "the quiet period holds"); assertEquals(1, ctl.mutes)
        assertEquals(listOf("user:not_ad_quiet"), engine.status().reasons)
        tick(320)                        // past the minute
        assertTrue(ctl.muted, "after the quiet period the detectors count again"); assertEquals(2, ctl.mutes)
        assertEquals(0.0, engine.status().quietS)
    }

    @Test fun `methods can be switched off one by one but not all at once`() {
        val ctl = Ctl()
        val e = Assembly.engine(ctl, silence = false, loudness = true, fingerprints = false)
        assertEquals(listOf("loudness", "stinger"), e.status().detectors)   // the stinger rides on loudness (ADR 0027)
        val e2 = Assembly.engine(ctl, silence = false, loudness = false, fingerprints = true)
        assertEquals(listOf("fingerprint"), e2.status().detectors)
        val store = FileScriptStore()
        val e3 = Assembly.engine(ctl, silence = false, loudness = false, fingerprints = false, captions = TranscriptDetector(store, name = "captions"))
        assertEquals(listOf("captions"), e3.status().detectors)
        assertTrue(runCatching { Assembly.engine(ctl, silence = false, loudness = false, fingerprints = false) }.isFailure, "nothing on is refused")
        // Captions feed their own detector and learn into the shared store.
        val words = (0 until 30).map { Word(it * 0.4, "cap$it") }
        e3.onCaptions(words); e3.onCaptions(words.map { Word(it.ts + 300.0, it.text) })
        assertEquals(1, e3.learnScriptsFromTranscript()); assertEquals(1, store.count())
    }
}
