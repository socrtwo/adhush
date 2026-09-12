package io.adhush.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR 0007: the Kotlin port must agree with the Python core on shared
 * fixtures. tools/gen_conformance.py wrote conformance.txt from the Python
 * detectors; this regenerates the same signal and checks every block.
 */
class ConformanceTest {
    private val rate = 48_000
    private val block = 4_800

    /** Must match tools/gen_conformance.py:block_samples exactly. */
    private fun signal(index: Int): FloatArray {
        val out = FloatArray(block)
        for (i in 0 until block) {
            val n = (index * block + i).toDouble()
            val t = n / rate
            var x = 0.05 * sin(2 * PI * 220.0 * t) + 0.03 * sin(2 * PI * 440.0 * t)
            x += 0.02 * sin(2 * PI * 1000.0 * t)
            if (index in 60 until 90) x = 4.0 * x + 0.02 * sin(2 * PI * 3000.0 * t)
            if (index in 40 until 45) x = 0.0
            out[i] = x.toFloat()
        }
        return out
    }

    private val lines: List<String> by lazy {
        javaClass.getResourceAsStream("/conformance.txt")!!.bufferedReader().readLines().filter { !it.startsWith("#") }
    }

    @Test fun `per-block primitives and loudness agree with the python core`() {
        val loud = LoudnessDetector()
        var worstLufs = 0.0; var chromaMismatch = 0
        for (line in lines.filter { it.startsWith("block ") }) {
            val f = line.split(" ")
            val i = f[1].toInt(); val ts = f[2].toDouble()
            val samples = signal(i)
            assertEquals(f[3].toDouble(), Dsp.blockDbfs(samples), 1e-6, "dbfs block $i")
            val flat = f[4].toDouble()
            assertTrue(abs(Dsp.spectralFlatness(samples) - flat) <= 1e-6 * maxOf(1.0, flat) + 1e-9, "flatness block $i")
            loud.observeAudio(AudioBlock(ts, samples, rate))
            val vote = loud.vote(ts)
            val dLufs = abs(loud.lastShortTerm - f[5].toDouble())
            worstLufs = maxOf(worstLufs, dLufs)
            assertTrue(dLufs < 1e-6, "short-term LUFS block $i: kotlin=${loud.lastShortTerm} python=${f[5]}")
            assertEquals(f[6].toDouble(), vote.confidence, 1e-7, "loudness confidence block $i")
            if (Chroma.chromaBits(samples, rate) != f[7].toInt()) chromaMismatch++
        }
        assertEquals(0, chromaMismatch, "chroma signatures differ from python on $chromaMismatch blocks")
        println("conformance: worst short-term LUFS delta = $worstLufs")
    }

    @Test fun `fusion and the state machine replay the python decisions exactly`() {
        val fusion = Fusion(FusionConfig(), mapOf("a" to 0.15, "b" to 0.15, "c" to 0.30), listOf("a", "b", "c"))
        val machine = AdStateMachine(FusionConfig())
        var ticks = 0; var actions = 0
        for (line in lines.filter { it.startsWith("tick ") }) {
            val f = line.split(" ")
            val ts = f[2].toDouble()
            val votes = listOf(DetectorVote("a", ts, f[3].toDouble(), "x"), DetectorVote("b", ts, f[4].toDouble(), "x"), DetectorVote("c", ts, f[5].toDouble(), "x"))
            val decision = fusion.combine(votes, ts)
            val action = machine.update(decision, promote = f[6] == "1", fpHold = f[7] == "1", programEvidence = false)
            assertEquals(f[8] == "1", decision.mute, "mute side at tick ${f[1]}")
            assertEquals(f[9].toDouble(), decision.confidence, 1e-9, "confidence at tick ${f[1]}")
            assertEquals(f[10], machine.state.wire, "state at tick ${f[1]}")
            assertEquals(f[11], action?.wire ?: "-", "action at tick ${f[1]}")
            ticks++; if (action != null) actions++
        }
        assertTrue(ticks > 100 && actions == 4, "replayed $ticks ticks, $actions actions")
    }
}
