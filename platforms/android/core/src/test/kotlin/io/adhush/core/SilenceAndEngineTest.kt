package io.adhush.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeController : MuteController {
    val log = ArrayList<Pair<Double, String>>()
    var muted = false
    var clock = 0.0
    override fun mute() { muted = true; log.add(Pair(clock, "mute")) }
    override fun unmute() { muted = false; log.add(Pair(clock, "unmute")) }
    override fun state() = muted
    override fun close() {}
}

class SilenceAndEngineTest {

    private fun tone(i: Int, amp: Double, hz: Double = 440.0, n: Int = 4_800, rate: Int = 48_000): AudioBlock =
        AudioBlock(i * n.toDouble() / rate, FloatArray(n) { k -> (amp * kotlin.math.sin(2 * Math.PI * hz * (i * n + k) / rate)).toFloat() }, rate)

    @Test fun `loudness baseline ignores the mic start-up ramp and recovers from a stuck elevation`() {
        val d = LoudnessDetector(LoudnessConfig(windowS = 1.5, baselineS = 20.0, maxElevatedS = 60.0)); d.warmup()
        var i = 0
        repeat(5) { d.observeAudio(AudioBlock(i * 0.1, FloatArray(4_800), 48_000)); i++ }   // 0.5 s of nothing
        repeat(150) { d.observeAudio(tone(i, 0.1)); i++ }                                    // 15 s of programme
        val settled = d.vote(i * 0.1)
        assertEquals(0.0, settled.confidence, settled.reason)
        assertTrue(kotlin.math.abs(d.baselineLufs!! - d.lastShortTerm) < 0.2, "baseline taken from the full window")
        repeat(590) { d.observeAudio(tone(i, 0.35, 880.0)); i++ }                            // 59 s hot: frozen
        assertEquals(1.0, d.vote(i * 0.1).confidence)
        repeat(1200) { d.observeAudio(tone(i, 0.35, 880.0)); i++ }                           // two more minutes: follows
        assertTrue(d.vote(i * 0.1).confidence < 0.05, d.vote(i * 0.1).reason)
    }

    private val rate = 48_000
    private val rnd = Random(3)

    /** Room-ish audio: noise floor plus programme tones at a level in dBFS-ish terms. */
    private fun room(i: Int, level: Double, tone: Boolean = true, gap: Boolean = false): FloatArray = FloatArray(4800) { n ->
        val t = (i * 4800 + n) / rate.toDouble()
        val noise = (rnd.nextDouble() * 2 - 1) * 0.0015                     // ~-58 dBFS room floor
        val signal = if (tone && !gap) level * (sin(2 * PI * 200 * t) + 0.6 * sin(2 * PI * 620 * t) + 0.3 * sin(2 * PI * 1400 * t)) else 0.0
        (noise + signal).toFloat()
    }

    @Test fun `mic silence - a gap at the room floor votes, quiet dialogue does not`() {
        val det = MicSilenceDetector(MicSilenceConfig(warmupS = 5.0))
        var i = 0
        repeat(80) { det.observeAudio(AudioBlock(i * 0.1, room(i, 0.05), rate)); i++ }
        assertEquals(0.0, det.vote(i * 0.1).confidence, "programme is not silence")
        repeat(8) { det.observeAudio(AudioBlock(i * 0.1, room(i, 0.05, gap = true), rate)); i++ }
        assertEquals(1.0, det.vote(i * 0.1).confidence, "800 ms at the room floor is a gap")
        repeat(3) { det.observeAudio(AudioBlock(i * 0.1, room(i, 0.05), rate)); i++ }
        val decaying = det.vote(i * 0.1).confidence
        assertTrue(decaying in 0.5..0.95, "a finished gap decays: $decaying")
        // quiet dialogue: a tone 30 dB down still has structure, so flatness rejects it
        repeat(40) { det.observeAudio(AudioBlock(i * 0.1, room(i, 0.0016), rate)); i++ }
        val quietDialogue = det.vote(i * 0.1)
        assertEquals(0.0, quietDialogue.confidence, "quiet but structured audio is not a gap: ${quietDialogue.reason}")
    }

    @Test fun `end to end - an ad pod mutes after the dwell, unmutes after it ends, and is learned`() {
        val ctl = FakeController()
        val store = FileFingerprintStore()
        val engine = Assembly.engine(ctl, store, fusionCfg = FusionConfig(), weights = mapOf("silence" to 0.15, "loudness" to 0.30, "fingerprint" to 0.30))
        var i = 0
        fun feed(count: Int, level: Double, gap: Boolean = false, chord: Boolean = false) = repeat(count) {
            val samples = if (chord) FloatArray(4800) { n -> val t = (i * 4800 + n) / rate.toDouble(); (level * (sin(2 * PI * 330 * t) + sin(2 * PI * 415 * t) + sin(2 * PI * 494 * t) + (rnd.nextDouble() - 0.5) * 0.002)).toFloat() } else room(i, level, gap = gap)
            ctl.clock = i * 0.1
            engine.onAudio(AudioBlock(i * 0.1, samples, rate)); i++
        }
        feed(300, 0.05)                      // 30 s of programme: baseline settles
        feed(6, 0.05, gap = true)            // pod boundary gap
        feed(200, 0.20, chord = true)        // 20 s of loud ad (+12 dB)
        val muteAt = ctl.log.firstOrNull { it.second == "mute" }
        assertTrue(muteAt != null, "never muted: ${engine.status()}")
        assertTrue(muteAt.first in 30.0..34.0, "mute came at ${muteAt.first}s")
        feed(60, 0.05)                       // programme returns
        val unmuteAt = ctl.log.firstOrNull { it.second == "unmute" }
        assertTrue(unmuteAt != null, "never unmuted")
        assertTrue(unmuteAt.first - muteAt.first in 15.0..30.0, "ad ran ${unmuteAt.first - muteAt.first}s")
        assertTrue(unmuteAt.first < 51.5 + 4.0, "unmute lagged: ${unmuteAt.first}")
        assertEquals(1, store.count(), "the fusion-driven ad was learned")
    }

    @Test fun `user reject unmutes at once and a user confirm mutes at once`() {
        val ctl = FakeController()
        val engine = Assembly.engine(ctl)
        assertTrue(engine.confirmAd(1.0)); assertTrue(ctl.muted)
        assertEquals(AdState.AD, engine.status().state)
        assertTrue(engine.rejectAd(2.0)); assertTrue(!ctl.muted)
        assertEquals(AdState.RECOVERY, engine.status().state)
        engine.setOverride(Override.MUTE, 3.0); assertTrue(ctl.muted)
        engine.setOverride(Override.AUTO, 4.0); assertTrue(!ctl.muted)
    }
}
