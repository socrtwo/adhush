package io.adhush.core

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ADR 0027: a short noisy burst over the bed, then the level moves — a segment stinger. */
class StingerTest {
    private val rate = 48_000

    private class Feeder(val det: StingerDetector) {
        var ts = 0.0
        fun feed(samples: FloatArray) {
            var i = 0
            while (i < samples.size) {   // 50 ms blocks, like the microphone
                val n = minOf(2_400, samples.size - i)
                det.observeAudio(AudioBlock(ts, samples.copyOfRange(i, i + n), 48_000)); ts += n / 48_000.0; i += n
            }
        }
    }

    private fun tone(f: Feeder, seconds: Double, dbfs: Double) {
        val amp = 10.0.pow(dbfs / 20.0) * sqrt(2.0)
        val n = (seconds * rate).toInt(); val t0 = f.ts
        f.feed(FloatArray(n) { i -> (amp * sin(2 * PI * 440.0 * (t0 + i / rate.toDouble()))).toFloat() })
    }

    private fun gaussian(rnd: Random): Double {
        var u = 0.0; while (u == 0.0) u = rnd.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * PI * rnd.nextDouble())
    }

    private fun noise(f: Feeder, seconds: Double, dbfs: Double, rnd: Random) {
        val amp = 10.0.pow(dbfs / 20.0)
        f.feed(FloatArray((seconds * rate).toInt()) { (amp * gaussian(rnd)).toFloat() })
    }

    @Test fun `a burst then a level step fires and decays`() {
        val det = StingerDetector(); det.warmup()
        val f = Feeder(det); val rnd = Random(1)
        tone(f, 3.0, -20.0)
        assertFalse(det.voting); assertEquals(0.0, det.vote(f.ts).confidence)
        noise(f, 0.3, -8.0, rnd)
        tone(f, 1.2, -12.0)
        val v = det.vote(f.ts)
        assertTrue(det.voting && v.confidence > 0.8, v.reason)
        assertTrue(v.reason.startsWith("stinger burst_db=+") && "step_db=+" in v.reason, v.reason)
        tone(f, 3.0, -12.0)
        assertFalse(det.voting); assertEquals(0.0, det.vote(f.ts).confidence)
        assertEquals("1 stinger heard", det.describe())
    }

    @Test fun `a level step without a burst is loudness's business`() {
        val det = StingerDetector(); det.warmup()
        val f = Feeder(det)
        tone(f, 3.0, -20.0); tone(f, 2.0, -8.0)
        assertFalse(det.voting, det.vote(f.ts).reason)
    }

    @Test fun `applause and a burst back to the same bed do not fire`() {
        val det = StingerDetector(); det.warmup()
        val f = Feeder(det); val rnd = Random(2)
        tone(f, 3.0, -20.0)
        noise(f, 3.0, -8.0, rnd)              // far too long for a stinger
        assertFalse(det.voting)
        tone(f, 3.0, -20.0)
        noise(f, 0.3, -13.0, rnd)             // +7 dB, and the bed comes back unchanged
        tone(f, 2.0, -20.0)
        assertFalse(det.voting, det.vote(f.ts).reason)
    }

    @Test fun `our own duck is ignored`() {
        val det = StingerDetector(); det.warmup()
        val f = Feeder(det); val rnd = Random(3)
        tone(f, 3.0, -20.0)
        det.audioDucked(f.ts, true)
        noise(f, 0.3, -8.0, rnd); tone(f, 1.2, -12.0)
        assertFalse(det.voting)
    }
}
