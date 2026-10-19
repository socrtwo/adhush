package io.adhush.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ADR 0016: the phone's mic hears the duck; the level detectors compensate or fall silent. */
class DuckCompensationTest {
    private val rate = 48_000
    private val n = 4_800

    /** Alternate blocks 6 dB apart: broadcast audio moves, a fan does not. */
    private fun block(i: Int, amp: Double, hz: Double = 440.0): AudioBlock {
        val swing = if (i % 2 == 0) 1.0 else 0.5
        return AudioBlock(i * 0.1, FloatArray(n) { k -> (amp * swing * sin(2 * PI * hz * (i * n + k) / rate)).toFloat() }, rate)
    }

    private fun feed(d: Detector, from: Int, count: Int, amp: Double): Int { var i = from; repeat(count) { d.observeAudio(block(i, amp)); i++ }; return i }

    @Test fun `a ducked ad still reads as an ad and ducked programme as programme`() {
        val d = LoudnessDetector(LoudnessConfig(windowS = 1.5, baselineS = 30.0)); d.warmup()
        var i = feed(d, 0, 200, 0.1)                       // 20 s of programme
        assertEquals(0.0, d.vote(i * 0.1).confidence)
        i = feed(d, i, 30, 0.25)                            // an ad 8 dB hot
        assertEquals(1.0, d.vote(i * 0.1).confidence)
        d.audioDucked(i * 0.1, true)                        // the app turns the set down ~20 dB
        i = feed(d, i, 10, 0.025)                           // settling: the vote is frozen
        val settling = d.vote(i * 0.1)
        assertEquals(1.0, settling.confidence); assertTrue(settling.reason.startsWith("duck_settling"), settling.reason)
        i = feed(d, i, 30, 0.025)                           // measured: the ducked ad reads on the old scale
        val ducked = d.vote(i * 0.1)
        assertTrue(d.duckOffsetDb in 17.0..23.0, ducked.reason)
        assertEquals(1.0, ducked.confidence); assertTrue("duck_offset_db" in ducked.reason)
        i = feed(d, i, 30, 0.01)                            // the show is back, still ducked
        assertTrue(d.vote(i * 0.1).confidence < 0.05, d.vote(i * 0.1).reason)
        d.audioDucked(i * 0.1, false)
        assertEquals(0.0, d.duckOffsetDb)
        i = feed(d, i, 30, 0.1)
        assertEquals(0.0, d.vote(i * 0.1).confidence)
    }

    @Test fun `buried under the room the loudness detector goes inert`() {
        val d = LoudnessDetector(LoudnessConfig(windowS = 1.5, baselineS = 30.0)); d.warmup()
        var i = feed(d, 0, 200, 0.1)
        i = feed(d, i, 30, 0.25)
        d.audioDucked(i * 0.1, true)
        val rnd = kotlin.random.Random(7)
        repeat(40) { d.observeAudio(AudioBlock(i * 0.1, FloatArray(n) { (0.02 * (rnd.nextDouble() * 2 - 1)).toFloat() }, rate)); i++ }   // the fans, not the set
        assertFalse(d.voting)
        assertTrue(d.vote(i * 0.1).reason.startsWith("ducked_buried"))
        d.audioDucked(i * 0.1, false)
        assertTrue(d.voting)
    }

    @Test fun `mic silence is inert while ducked`() {
        val d = MicSilenceDetector(MicSilenceConfig(warmupS = 1.0)); d.warmup()
        var i = 0
        repeat(30) { d.observeAudio(block(i, 0.05)); i++ }
        d.audioDucked(i * 0.1, true)
        repeat(10) { d.observeAudio(AudioBlock(i * 0.1, FloatArray(n), rate)); i++ }
        assertFalse(d.voting); assertEquals(0.0, d.vote(i * 0.1).confidence)
        d.audioDucked(i * 0.1, false)
        assertTrue(d.voting)
    }

    private class Ctl : MuteController {
        var muted = false
        override fun mute() { muted = true }
        override fun unmute() { muted = false }
        override fun state() = muted
        override fun close() {}
    }

    private class Recorder : Detector {
        override val name = "recorder"
        val ducks = ArrayList<Boolean>()
        override fun warmup() {}
        override fun observeAudio(block: AudioBlock) {}
        override fun vote(ts: Double) = vote(ts, 0.0, "recorder")
        override fun audioDucked(ts: Double, ducked: Boolean) { ducks.add(ducked) }
    }

    @Test fun `the engine tells the detectors about every duck and restore`() {
        val rec = Recorder()
        val engine = Engine(listOf(rec), Fusion(FusionConfig(), mapOf("recorder" to 0.45), listOf("recorder")), AdStateMachine(FusionConfig()), Ctl())
        engine.setOverride(Override.MUTE, 1.0)
        engine.setOverride(Override.UNMUTE, 2.0)
        engine.setOverride(Override.AUTO, 3.0)   // already in step with the machine: nothing sent
        assertEquals(listOf(true, false), rec.ducks)
    }
}
