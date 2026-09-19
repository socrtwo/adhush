package io.adhush.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ADR 0023 on the phone: gaps on the fifteen-second grid, and the rating box through the camera. */
class AdUnitsAndRatingTest {
    private val rate = 8000
    private val n = 800

    private fun tone(ts: Double) = AudioBlock(ts, FloatArray(n) { k -> (0.2 * sin(2 * PI * 440.0 * k / rate)).toFloat() }, rate)
    private val rng = java.util.Random(7)
    /** A broadcast gap heard by a room mic: near-digital silence, spectrally flat. */
    private fun quiet(ts: Double) = AudioBlock(ts, FloatArray(n) { ((rng.nextDouble() - 0.5) * 2e-5).toFloat() }, rate)

    @Test fun `gaps on the grid vote and an off-grid gap breaks the rhythm`() {
        val d = AdUnitsDetector(AdUnitsConfig(holdS = 35.0)); d.warmup()
        var ts = 0.0
        fun gap(at: Double) {
            while (ts < at) { d.observeAudio(tone(ts)); ts += 0.1 }
            repeat(6) { d.observeAudio(quiet(ts)); ts += 0.1 }
        }
        gap(20.0)                       // the floor needs warming first
        assertFalse(d.voting)
        gap(50.0)
        val one = d.vote(ts)
        assertTrue(d.voting, one.reason); assertEquals(0.5, one.confidence); assertTrue("units=30" in one.reason, one.reason)
        gap(65.3)
        val two = d.vote(ts)
        assertEquals(1.0, two.confidence); assertEquals(listOf(30.0, 15.0), d.units)
        gap(87.0)                       // 21.7 s: off the grid
        assertFalse(d.voting)
        assertEquals(30.0, AdUnitsDetector.unitFit(30.9, 1.2)); assertEquals(null, AdUnitsDetector.unitFit(22.0, 1.2))
    }

    private fun frame(w: Int, h: Int, screen: Box, box: Boolean): Gray {
        val px = FloatArray(w * h) { 20f }
        for (y in screen.y0 until screen.y1) for (x in screen.x0 until screen.x1) px[y * w + x] = 120f
        if (box) {
            // a 2:1 white box with dark text, in the screen's upper left
            val bx0 = screen.x0 + 6; val by0 = screen.y0 + 6
            for (y in by0 until by0 + 12) for (x in bx0 until bx0 + 26) px[y * w + x] = 245f
            for (y in by0 + 4 until by0 + 7) for (x in bx0 + 4 until bx0 + 22) px[y * w + x] = 30f
        }
        return Gray(w, h, px)
    }

    @Test fun `a box that appears after a break is programme evidence and scenery is not`() {
        val screen = Box(40, 30, 280, 165)
        val d = RatingBugDetector({ screen }, RatingBugConfig(appearAfterS = 2.0, minPresentS = 0.5, holdS = 6.0)); d.warmup()
        var ts = 0.0
        repeat(12) { d.observeFrame(frame(320, 200, screen, false), ts); ts += 0.5 }   // 6 s of plain programme
        assertFalse(d.programPresent)
        repeat(4) { d.observeFrame(frame(320, 200, screen, true), ts); ts += 0.5 }      // the box for 2 s
        assertTrue(d.programPresent, d.vote(ts).reason); assertTrue(d.voting)
        assertEquals(1, d.sightings)
        repeat(20) { d.observeFrame(frame(320, 200, screen, false), ts); ts += 0.5 }    // 10 s later: hold over
        assertFalse(d.programPresent); assertFalse(d.voting)

        val scenery = RatingBugDetector({ screen }, RatingBugConfig(appearAfterS = 2.0, minPresentS = 0.5)); scenery.warmup()
        var t = 0.0
        repeat(30) { scenery.observeFrame(frame(320, 200, screen, true), t); t += 0.5 }
        assertFalse(scenery.programPresent)   // it never appeared: it was always there
    }

    @Test fun `no screen means no opinion`() {
        val d = RatingBugDetector({ null }); d.warmup()
        d.observeFrame(Gray(8, 8, FloatArray(64) { 245f }), 0.0)
        assertFalse(d.voting)
    }
}
