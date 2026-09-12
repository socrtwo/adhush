package io.adhush.core

import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic living room: a dark surround, a lit screen with changing content, a crisp bug in one corner. */
class VisionTest {
    private val W = 320; private val H = 240
    private val screen = Box(40, 30, 280, 210)   // 240 × 180 lit
    private val rnd = Random(7)

    /** One camera frame; the bug is a bright ring with a bar, bottom-right of the screen, when [logo]. */
    private fun frame(logo: Boolean, seed: Int): Gray {
        val r = Random(seed)
        val px = FloatArray(W * H) { 12f + r.nextInt(6) }
        // content: smooth blobs that move every frame, 80..200
        val cx = 100 + (seed * 7) % 120; val cy = 80 + (seed * 11) % 90
        for (y in screen.y0 until screen.y1) for (x in screen.x0 until screen.x1) {
            val d = ((x - cx) * (x - cx) + (y - cy) * (y - cy)).toDouble()
            px[y * W + x] = (140 + 60 * sin(d / 900.0 + seed) ).toFloat().coerceIn(80f, 200f)
        }
        if (logo) {
            val lx = screen.x1 - 34; val ly = screen.y1 - 22   // 24 × 14 bug
            for (y in ly until ly + 14) for (x in lx until lx + 24) {
                val ring = (x - lx - 7) * (x - lx - 7) + (y - ly - 7) * (y - ly - 7)
                val on = (ring in 16..36) || (x >= lx + 17 && y in ly + 3..ly + 11)
                if (on) px[y * W + x] = 245f
            }
        }
        return Gray(W, H, px)
    }

    @Test fun `the lit screen is found in the frame`() {
        val b = assertNotNull(Vision.findScreen(frame(true, 1)))
        assertTrue(kotlin.math.abs(b.x0 - screen.x0) <= 8 && kotlin.math.abs(b.y0 - screen.y0) <= 8, "$b")
        assertTrue(kotlin.math.abs(b.x1 - screen.x1) <= 8 && kotlin.math.abs(b.y1 - screen.y1) <= 8, "$b")
        assertNull(Vision.findScreen(Gray(W, H, FloatArray(W * H) { 20f })), "no contrast, no screen")
    }

    @Test fun `one-button calibration finds the bug in the bottom-right and the template round-trips`() {
        val finder = LogoFinder()
        for (i in 0 until 40) assertTrue(finder.feed(frame(true, i)))
        val t = assertNotNull(finder.result(), "no logo found")
        assertEquals("bottom-right", t.roi.corner, "${t.roi}")
        assertTrue(t.roi.x > 0.7 && t.roi.y > 0.7, "${t.roi}")
        assertTrue(t.roi.w < 0.3 && t.roi.h < 0.3, "tight box: ${t.roi}")
        assertTrue(t.stability >= 0.9, "stability ${t.stability}")
        val f = File.createTempFile("logo", ".tsv"); f.deleteOnExit()
        t.save(f)
        val back = assertNotNull(LogoTemplate.load(f))
        assertEquals(t.roi.x, back.roi.x, 1e-4); assertEquals(t.roi.h, back.roi.h, 1e-4); assertEquals(t.edges.size, back.edges.size)
        assertEquals(t.edges[5].toDouble(), back.edges[5].toDouble(), 1e-3)
        // A screen with no bug at all: nothing persistent enough
        val none = LogoFinder(); for (i in 0 until 40) none.feed(frame(false, i))
        assertNull(none.result(), "nothing should be found without a bug")
    }

    @Test fun `absence ramps the vote, presence zeroes it at once, no screen is inert`() {
        val finder = LogoFinder(); for (i in 0 until 40) finder.feed(frame(true, i))
        val det = LogoAbsenceDetector(LogoAbsenceConfig(absenceS = 1.5), finder.result()!!); det.warmup()
        var ts = 0.0
        for (i in 100 until 110) { det.observeFrame(frame(true, i), ts); ts += 0.5 }
        assertTrue(det.active); assertTrue(det.programPresent, "score ${det.lastScore}")
        assertEquals(0.0, det.vote(ts).confidence)
        for (i in 110 until 116) { det.observeFrame(frame(false, i), ts); ts += 0.5 }   // 3 s without the bug
        assertFalse(det.programPresent, "score ${det.lastScore}")
        assertEquals(1.0, det.vote(ts).confidence, det.vote(ts).reason)
        det.observeFrame(frame(true, 200), ts); det.observeFrame(frame(true, 201), ts + 0.5); det.observeFrame(frame(true, 202), ts + 1.0)
        assertEquals(0.0, det.vote(ts + 1.0).confidence, det.vote(ts + 1.0).reason)
        det.observeFrame(Gray(W, H, FloatArray(W * H) { 20f }), ts + 6.0)   // camera covered: re-detect fails
        assertFalse(det.active); assertEquals("no_screen", det.vote(ts + 6.0).reason)
    }

    @Test fun `in the engine, a missing logo ducks the set on its own and its return restores`() {
        val finder = LogoFinder(); for (i in 0 until 40) finder.feed(frame(true, i))
        val logo = LogoAbsenceDetector(template = finder.result()!!)
        val ctl = object : MuteController { var muted = false; override fun mute() { muted = true }; override fun unmute() { muted = false }; override fun state() = muted; override fun close() {} }
        val engine = Assembly.engine(ctl, logo = logo)
        val rate = 48_000; val n = 4_800
        fun tone(i: Int) = AudioBlock(i * 0.1, FloatArray(n) { k -> (0.1 * sin(2 * PI * 440 * (i * n + k) / rate)).toFloat() }, rate)
        var i = 0
        fun run(seconds: Int, logoOn: Boolean) { repeat(seconds * 10) { if (i % 5 == 0) engine.onFrame(frame(logoOn, i), i * 0.1); engine.onAudio(tone(i)); i++ } }
        run(20, true); assertFalse(ctl.muted)
        run(6, false); assertTrue(ctl.muted, "logo absent for 6 s: ${engine.status()}")
        run(4, true); assertFalse(ctl.muted, "logo back: ${engine.status()}")
    }
}
