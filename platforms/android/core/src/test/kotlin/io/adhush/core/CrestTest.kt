package io.adhush.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ADR 0021: a spot no louder than the show but compressed flat still shows, for half a vote. */
class CrestTest {
    private val rate = 8000
    private val n = 800

    private fun peaky(i: Int): AudioBlock = AudioBlock(i * 0.1, FloatArray(n) { k ->
        val v = 0.1 * sin(2 * PI * 440.0 * (i * n + k) / rate)
        (if (k % 400 == 0) 0.5 else v).toFloat()   // sparse peaks: speech-like dynamics, ~16 dB crest
    }, rate)

    private fun flat(i: Int): AudioBlock = AudioBlock(i * 0.1, FloatArray(n) { k ->
        (0.1 * sin(2 * PI * 440.0 * (i * n + k) / rate)).toFloat()   // the same level, 3 dB crest
    }, rate)

    @Test fun `a compressed ad at programme level raises the vote by half`() {
        val d = LoudnessDetector(LoudnessConfig(windowS = 1.5, baselineS = 30.0)); d.warmup()
        var i = 0
        repeat(150) { d.observeAudio(peaky(i)); i++ }
        assertTrue(d.vote(i * 0.1).confidence < 1e-9, d.vote(i * 0.1).reason)   // the EMA leaves rounding dust
        assertTrue((d.baselineCrestDb ?: 0.0) > 12.0, "baseline crest ${d.baselineCrestDb}")
        repeat(30) { d.observeAudio(flat(i)); i++ }
        val v = d.vote(i * 0.1)
        assertTrue(v.confidence in 0.45..0.5, v.reason)
        assertTrue("crest_drop_db=" in v.reason, v.reason)
    }

    @Test fun `the crest cue can be switched off`() {
        val d = LoudnessDetector(LoudnessConfig(windowS = 1.5, baselineS = 30.0, crestDropDb = 0.0)); d.warmup()
        var i = 0
        repeat(150) { d.observeAudio(peaky(i)); i++ }
        repeat(30) { d.observeAudio(flat(i)); i++ }
        assertTrue(d.vote(i * 0.1).confidence < 1e-9)
    }

    @Test fun `block crest of a sine is three decibels`() {
        assertEquals(3.01, LoudnessDetector.blockCrestDb(flat(0).samples), 0.05)
        assertEquals(0.0, LoudnessDetector.blockCrestDb(FloatArray(100)))
    }
}
