package io.adhush.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptTest {
    private val vocab = (0 until 400).map { "w$it" }
    private fun programme(rnd: Random, n: Int) = List(n) { vocab[rnd.nextInt(vocab.size)] }
    private val adA = (0 until 70).map { "a$it" }      // ~30 s at 0.43 s/word
    private val adB = (0 until 40).map { "b$it" }      // a 15 s spot
    private val rate = 0.43

    /** An hour: programme with A airing at 5, 20, 50 min and B at 12 and 40 min. */
    private fun hour(seed: Int = 1): List<Word> {
        val rnd = Random(seed); val out = ArrayList<Word>(); var t = 0.0
        fun say(ws: List<String>) { for (w in ws) { out.add(Word(t, w)); t += rate } }
        val plan = listOf(5 to adA, 12 to adB, 20 to adA, 40 to adB, 50 to adA).sortedBy { it.first }
        var nextIdx = 0
        while (t < 3600) {
            if (nextIdx < plan.size && t >= plan[nextIdx].first * 60.0) { say(plan[nextIdx].second); nextIdx++ }
            else say(programme(rnd, 30))
        }
        return out
    }

    @Test fun `repetition finds the two spots in an hour and nothing else`() {
        val store = FileScriptStore()
        val added = RepeatLearner(store).learn(hour())
        assertEquals(2, added.size, "found: ${store.all().map { it.words.take(3) }}")
        val a = store.all().first { it.words.first() == "a0" }; val b = store.all().first { it.words.first() == "b0" }
        assertEquals(70, a.words.size); assertEquals(40, b.words.size)
        assertTrue(a.sampleCount >= 2, "A aired three times, folded: ${a.sampleCount}")
        assertEquals(69 * rate, a.durationS, 0.5)
    }

    @Test fun `live words match a script despite recogniser errors, hold through, and release after`() {
        val store = FileScriptStore()
        RepeatLearner(store).learn(hour())
        val det = TranscriptDetector(store); det.warmup()
        val rnd = Random(9); var t = 10_000.0
        fun hear(ws: List<String>, corrupt: Boolean = false) { for (w in ws) { det.observeWord(Word(t, if (corrupt && rnd.nextInt(100) < 15) "x${rnd.nextInt(99)}" else w)); t += rate } }
        hear(programme(rnd, 200))
        assertEquals(0.0, det.vote(t).confidence, det.lastReason)
        val adStart = t
        var firedAt = -1.0
        for (w in adA) { det.observeWord(Word(t, if (rnd.nextInt(100) < 15) "x${rnd.nextInt(99)}" else w)); if (firedAt < 0 && det.vote(t).confidence == 1.0) firedAt = t; t += rate }
        assertTrue(firedAt > 0, "never matched A with 15% word errors")
        assertTrue(firedAt - adStart < 7.0, "matched within 7 s of the ad starting (${firedAt - adStart})")
        assertEquals(1.0, det.vote(t).confidence, "still held at the ad's last word")
        val adEnd = t
        hear(programme(rnd, 100))
        assertEquals(0.0, det.vote(t).confidence)
        // find when it released: no later than grace after the last word
        var probe = adEnd; while (det.vote(probe).confidence == 1.0 && probe < adEnd + 20) probe += 0.25
        assertTrue(probe - adEnd <= 8.5, "released within grace (${probe - adEnd})")
    }

    @Test fun `boilerplate holds on its own and teach mode saves the bracketed words`() {
        val store = FileScriptStore()
        val det = TranscriptDetector(store); det.warmup()
        var t = 0.0
        for (w in listOf("the", "senator", "said", "ask", "your", "doctor", "about")) { det.observeWord(Word(t, w)); t += rate }
        assertEquals(1.0, det.vote(t).confidence); assertEquals("boilerplate", det.lastReason)
        assertEquals(0.0, det.vote(t + 16).confidence)
        val start = t + 30
        var tt = start
        for (w in adB) { det.observeWord(Word(tt, w)); tt += rate }
        assertNotNull(det.learnWindow(start, tt))
        assertEquals(1, store.count()); assertEquals(40, store.all().single().words.size)
        assertNull(det.learnWindow(tt + 100, tt + 101), "nothing heard there")
    }

    @Test fun `the script store round-trips`(): Unit = kotlin.io.path.createTempDirectory().toFile().let { dir ->
        val f = java.io.File(dir, "scripts.tsv")
        val a = FileScriptStore(f); val id = a.add(listOf("ask", "your", "doctor", "today"), 3.5); a.bump(id, 4.5)
        val b = FileScriptStore(f)
        assertEquals(Script(id, listOf("ask", "your", "doctor", "today"), 4.0, 2), b.all().single())
        assertFalse(b.all().isEmpty())
    }

    @Test fun `in the engine a known script ducks the set on its own`() {
        val store = FileScriptStore(); RepeatLearner(store).learn(hour())
        val det = TranscriptDetector(store)
        val ctl = object : MuteController { var muted = false; override fun mute() { muted = true }; override fun unmute() { muted = false }; override fun state() = muted; override fun close() {} }
        val engine = Assembly.engine(ctl, transcript = det)
        // Audio that never repeats, so the fingerprint learned from the transcript-driven duck cannot re-match the show.
        val rate48 = 48_000; val n = 4_800; val rnd = Random(3)
        fun chord(i: Int): AudioBlock { val pcs = List(3) { rnd.nextInt(12) }; return AudioBlock(i * 0.1, FloatArray(n) { k -> var v = 0.0; for (pc in pcs) v += kotlin.math.sin(2 * Math.PI * 261.63 * Math.pow(2.0, pc / 12.0) * (i * n + k) / rate48); (0.1 * v).toFloat() }, rate48) }
        var i = 0
        repeat(200) { engine.onAudio(chord(i)); i++ }
        assertFalse(ctl.muted)
        var w = 0
        repeat(300) { if (i % 4 == 0 && w < adA.size) { engine.onWords(listOf(Word(i * 0.1, adA[w]))); w++ }; engine.onAudio(chord(i)); i++ }
        assertTrue(ctl.muted, "script heard: ${engine.status()}")
        repeat(300) { engine.onAudio(chord(i)); i++ }
        assertFalse(ctl.muted, "released after the grace: ${engine.status()}")
    }
}
