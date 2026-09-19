package io.adhush.core

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ADR 0020: the channel's own sting opens and closes every break; learned, then recognised. */
class JingleTest {
    private val rate = 48_000
    private val blockN = 4_800   // 100 ms

    /** A chord of six semitones, so chroma bits are half set and chance agreement sits near 0.5. */
    private fun chord(semitones: List<Int>, n: Int, t0: Double): FloatArray = FloatArray(n) { i ->
        val t = t0 + i / rate.toDouble()
        var v = 0.0
        for (s in semitones) v += sin(2 * PI * 440.0 * 2.0.pow(s / 12.0) * t)
        (0.15 * v / semitones.size).toFloat()
    }

    private class Feeder(val det: JingleDetector) {
        var ts = 0.0; var i = 0
        val rate = 48_000
        fun feed(samples: FloatArray) { det.observeAudio(AudioBlock(ts, samples, rate)); ts += samples.size.toDouble() / rate; i++ }
    }

    /** The same chord for 0.5 s per step: a sting of six steps is three seconds. */
    private fun sting(f: Feeder, steps: List<List<Int>>) { for (c in steps) repeat(5) { f.feed(chord(c, blockN, f.ts)) } }
    private fun programme(f: Feeder, seconds: Double, rnd: Random) {
        var left = seconds
        while (left > 0.0) { val c = (0 until 12).shuffled(rnd).take(6); repeat(5) { f.feed(chord(c, blockN, f.ts)) }; left -= 0.5 }
    }

    private val opener = listOf(listOf(0, 2, 4, 7, 9, 11), listOf(1, 3, 5, 6, 8, 10), listOf(0, 1, 4, 5, 8, 9), listOf(2, 3, 6, 7, 10, 11), listOf(0, 3, 6, 9, 1, 4), listOf(2, 5, 8, 11, 7, 10))
    private val closer = listOf(listOf(0, 1, 2, 3, 4, 5), listOf(6, 7, 8, 9, 10, 11), listOf(0, 2, 4, 6, 8, 10), listOf(1, 3, 5, 7, 9, 11), listOf(0, 1, 6, 7, 2, 8), listOf(3, 4, 9, 10, 5, 11))

    private class MemStore : JingleStore { var saved: List<Jingle> = emptyList(); override fun load() = saved; override fun save(jingles: List<Jingle>) { saved = jingles.map { Jingle(it.id, it.kind, it.blocks, it.hits, it.falseHits, it.createdTs, HashSet(it.hours)) } } }

    private fun learnThree(det: JingleDetector, f: Feeder, rnd: Random, wall: Double? = null) {
        repeat(3) { programme(f, 15.0, rnd); sting(f, opener); val s = f.ts + 1.0; programme(f, 25.0, rnd); sting(f, closer); det.learnBreak(s, f.ts - 1.5, wall); programme(f, 8.0, rnd) }
    }

    @Test fun `a sting transposed a semitone is the same family`() {
        assertEquals(0b010000000000, pitchRotate(0b100000000000, 1))     // class 0 (A) up to class 1
        assertEquals(0b100000000000, pitchRotate(0b000000000001, 1))     // class 11 wraps to class 0
        assertEquals(0b101100110001, pitchRotate(pitchRotate(0b101100110001, 2), -2))
        assertEquals(listOf(1, 1, 2, 3, 3, 4), tempoStretch(listOf(1, 2, 3, 4), 1.5))
        val up = opener.map { step -> step.map { it + 1 } }
        for ((shifts, expect) in listOf(2 to 1.0, 0 to 0.0)) {
            val det = JingleDetector(MemStore(), JingleConfig(pitchShifts = shifts)); det.warmup()
            val f = Feeder(det); val rnd = Random(5)
            learnThree(det, f, rnd)
            assertTrue(det.promoted().any { it.kind == JingleKind.OPEN }, det.describe())
            programme(f, 10.0, rnd)
            assertEquals(0.0, det.vote(f.ts).confidence)
            sting(f, up)
            val v = det.vote(f.ts)
            assertEquals(expect, v.confidence, "shifts=$shifts: ${v.reason}")
            if (expect > 0) assertTrue("family=+1st" in v.reason, v.reason)
        }
    }

    @Test fun `a three-second duck teaches nothing and two hearings in one session count once`() {
        val det = JingleDetector(MemStore()); det.warmup()
        val f = Feeder(det); val rnd = Random(9)
        val wall = 1_800_000_000.0
        // Three "breaks" a few seconds long, as a false fingerprint match makes: no candidates at all.
        repeat(3) { programme(f, 10.0, rnd); sting(f, opener); det.learnBreak(f.ts - 2.0, f.ts + 1.0, wall + it * 20.0) }
        assertEquals("learning (0 candidates; a sting must open 3 breaks)", det.describe())
        // Three real breaks within twenty minutes of each other: one hit, not three.
        repeat(3) { programme(f, 15.0, rnd); sting(f, opener); val s = f.ts + 1.0; programme(f, 25.0, rnd); det.learnBreak(s, f.ts, wall + 100.0 + it * 600.0); programme(f, 8.0, rnd) }
        assertTrue(det.promoted().isEmpty(), det.describe())
        // Two more, each half an hour apart from the last that counted: promoted.
        repeat(2) { programme(f, 15.0, rnd); sting(f, opener); val s = f.ts + 1.0; programme(f, 25.0, rnd); det.learnBreak(s, f.ts, wall + 100.0 + 3600.0 * (it + 1)); programme(f, 8.0, rnd) }
        assertEquals(1, det.promoted().count { it.kind == JingleKind.OPEN }, det.describe())
        det.forgetAll()
        assertTrue(det.promoted().isEmpty())
    }

    @Test fun `two breaks at nine o'clock trust the sting at nine, not at two, and the hours round-trip`() {
        val cal = java.util.Calendar.getInstance()
        cal.set(2026, 8, 18, 9, 5, 0); val nine = cal.timeInMillis / 1000.0
        cal.set(2026, 8, 18, 14, 5, 0); val two = cal.timeInMillis / 1000.0
        assertEquals(9, localHour(nine)); assertEquals(14, localHour(two))
        val store = MemStore()
        val det = JingleDetector(store); det.warmup()
        val f = Feeder(det); val rnd = Random(3)
        repeat(2) { k -> programme(f, 15.0, rnd); sting(f, opener); val s = f.ts + 1.0; programme(f, 25.0, rnd); sting(f, closer); det.learnBreak(s, f.ts - 1.5, nine + k * 2100.0); programme(f, 8.0, rnd) }
        assertTrue(det.promoted().isEmpty(), "two breaks and no clock: not yet")
        det.tick(two); assertTrue(det.promoted().isEmpty(), "not at two o'clock")
        det.tick(nine + 600.0)
        val opens = det.promoted().filter { it.kind == JingleKind.OPEN }
        assertEquals(1, opens.size, det.describe()); assertEquals(setOf(9), opens[0].hours)
        assertTrue("heard at 09h" in det.describe(), det.describe())
        sting(f, opener); assertEquals(1.0, det.vote(f.ts).confidence)
        assertTrue(store.saved.any { it.hours == setOf(9) }, "the store carries the hours")
        // The file store writes v2 rows and still reads v1 rows without hours.
        val dir = kotlin.io.path.createTempDirectory("jingles").toFile()
        val file = java.io.File(dir, "jingles.tsv")
        FileJingleStore(file).save(store.saved)
        assertTrue(file.readText().startsWith("# adhush jingles v2"))
        assertTrue(FileJingleStore(file).load().any { it.hours == setOf(9) })
        file.writeText(file.readLines().joinToString("\n") { it.split('\t').take(7).joinToString("\t") } + "\n")
        val legacy = FileJingleStore(file).load()
        assertTrue(legacy.isNotEmpty() && legacy.all { it.hours.isEmpty() })
        dir.deleteRecursively()
    }

    @Test fun `a sting that opens three breaks is promoted, then ducks on its own and its closer ends the break`() {
        val store = MemStore()
        val det = JingleDetector(store); det.warmup()
        val f = Feeder(det); val rnd = Random(11)
        repeat(3) { k ->
            programme(f, 15.0, rnd)
            sting(f, opener)                       // the break opens with the sting
            val start = f.ts + 1.0                 // the detectors notice a second later
            programme(f, 25.0, rnd)                // the ads themselves, different every time
            sting(f, closer)                       // the break closes with its sting
            val end = f.ts - 1.5                   // the unmute lands mid-closer
            det.learnBreak(start, end)
            programme(f, 8.0, rnd)                 // the closer window is heard now
            if (k < 2) assertTrue(det.promoted().isEmpty(), "not promoted after ${k + 1} break(s): ${det.describe()}")
        }
        val kinds = det.promoted().map { it.kind }.sorted()
        assertEquals(listOf(JingleKind.OPEN, JingleKind.CLOSE).sorted(), kinds, det.describe())
        // Live: the opener fires and holds; random audio does not.
        programme(f, 10.0, rnd)
        assertEquals(0.0, det.vote(f.ts).confidence, det.vote(f.ts).reason)
        sting(f, opener)
        val v = det.vote(f.ts)
        assertEquals(1.0, v.confidence, v.reason); assertTrue(v.reason.startsWith("jingle_open"))
        programme(f, 25.0, rnd)
        assertEquals(0.0, det.vote(f.ts).confidence, "the hold has run out")
        assertFalse(det.programPresent)
        sting(f, closer)
        assertTrue(det.programPresent, "the closer is programme evidence")
        // The file round-trips through the store.
        val again = JingleDetector(store)
        assertEquals(det.promoted().size, again.promoted().size)
    }

    @Test fun `Not an ad after a jingle duck demotes it`() {
        val det = JingleDetector(MemStore()); det.warmup()
        val f = Feeder(det); val rnd = Random(5)
        repeat(3) { programme(f, 12.0, rnd); sting(f, opener); val s = f.ts + 1.0; programme(f, 25.0, rnd); det.learnBreak(s, f.ts); programme(f, 6.0, rnd) }
        assertEquals(1, det.promoted().size)
        sting(f, opener); assertEquals(1.0, det.vote(f.ts).confidence)
        det.userSaysProgramme(f.ts)          // wrong once: falseHits 1 < hits 3, still promoted
        assertEquals(0.0, det.vote(f.ts).confidence)
        assertEquals(1, det.promoted().size)
        repeat(2) { programme(f, 5.0, rnd); sting(f, opener); det.userSaysProgramme(f.ts) }
        assertTrue(det.promoted().isEmpty(), "three wrong calls against three right ones: back to a candidate")
    }

    @Test fun `the clock learns break lengths and lowers the ceiling to what the channel runs`() {
        val store = object : ClockStore { var c: ClockCounts? = null; override fun load() = c; override fun save(counts: ClockCounts) { c = counts } }
        val clock = ClockDetector(store)
        assertEquals(240.0, clock.ceilingS(240.0)); assertEquals(null, clock.remainingS(10.0))
        for (i in 0 until 6) clock.learn(i * 3600.0, i * 3600.0 + 125.0 + i * 5)   // 125..150 s breaks
        assertEquals(6, clock.lengthSamples)
        val ceiling = clock.ceilingS(240.0)
        assertTrue(ceiling in 180.0..210.0, "ceiling $ceiling")
        assertTrue(clock.remainingS(60.0)!! in 60.0..120.0)
        assertEquals(0.0, clock.remainingS(600.0))
        val again = ClockDetector(store); assertEquals(6, again.lengthSamples)
    }

    private class Ctl : MuteController { var muted = false; override fun mute() { muted = true }; override fun unmute() { muted = false }; override fun state() = muted; override fun close() {} }

    @Test fun `plus thirty extends a timed duck and starts one when nothing is ducked`() {
        val ctl = Ctl(); val engine = Assembly.engine(ctl, FileFingerprintStore())
        var ts = 0.0
        fun tick(n: Int) { repeat(n) { engine.onAudio(AudioBlock(ts, FloatArray(4800), 48_000)); ts += 0.1 } }
        tick(5)
        assertTrue(engine.extendDuck(ts, 30.0)); assertTrue(ctl.muted)
        tick(100)
        assertTrue(engine.status().timedS in 19.0..21.0, "${engine.status().timedS}")
        assertTrue(engine.extendDuck(ts, 30.0))
        assertTrue(engine.status().timedS in 49.0..51.0, "${engine.status().timedS}")
        tick(520); assertFalse(ctl.muted)
    }
}
