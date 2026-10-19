package io.adhush.core

import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JudgeAndTickerTest {
    @Test fun `the one-line answer parses in every shape a model produces`() {
        assertEquals(Verdict(true, 0.9, "drug side effects"), Verdict.parse("COMMERCIAL 0.9: drug side effects"))
        assertEquals(Verdict(false, 0.8, "anchors discussing the vote"), Verdict.parse("show 0.8: anchors discussing the vote"))
        assertEquals(true, Verdict.parse("{\"answer\": \"COMMERCIAL 1.0: call now\"}")!!.commercial)
        assertEquals(0.7, Verdict.parse("SHOW")!!.confidence)
        assertNull(Verdict.parse("I am not sure what this is."))
        assertFalse(Verdict.parse("PROGRAM 0.6: weather report")!!.commercial)
    }

    private class FakeJudge(var answer: String = "SHOW 0.9: news") : TranscriptJudge {
        val asked = ArrayList<String>()
        override fun judge(transcript: String, channel: String): Verdict? { asked.add(transcript); return Verdict.parse(answer) }
    }

    private fun words(t0: Double, n: Int, prefix: String = "w") = (0 until n).map { Word(t0 + it * 0.4, "$prefix$it") }

    @Test fun `tie-breaker asks only when the others are unsure, and a commercial answer holds then decays`() {
        val judge = FakeJudge()
        val det = JudgeDetector("judge_test", JudgeConfig(), judge, runAsync = { it.run() }); det.warmup()
        var ts = 0.0
        for (w in words(0.0, 30)) det.observeWord(w)
        ts = 12.0
        det.hint(ts, 0.05, muted = false)                     // everyone agrees it is the show: not worth asking yet
        assertEquals(0, judge.asked.size)
        det.hint(ts, 0.4, muted = false)                      // now in doubt: ask
        assertEquals(1, judge.asked.size)
        assertTrue(judge.asked[0].startsWith("w0 w1 w2"))
        assertEquals(0.0, det.vote(ts).confidence)
        assertEquals("show 90% — news", det.describe())
        // A commercial answer: the vote goes up for holdS, then lapses.
        judge.answer = "COMMERCIAL 0.95: ask your doctor"
        for (w in words(ts, 20, "c")) det.observeWord(w)
        ts += 10.5
        det.hint(ts, 0.4, muted = false)
        assertEquals(2, judge.asked.size)
        assertEquals(0.95, det.vote(ts).confidence)
        assertTrue(det.vote(ts + 14.0).confidence > 0.9)
        assertEquals(0.0, det.vote(ts + 16.0).confidence, "held for 15 s, then quiet")
        // No new words since the last question: nothing to ask about, whatever the doubt.
        det.hint(ts + 20.0, 0.4, muted = true); assertEquals(2, judge.asked.size)
        for (w in words(ts + 12.0, 20, "d")) det.observeWord(w)
        det.hint(ts + 20.0, 0.4, muted = true); assertEquals(3, judge.asked.size)
        // "Not an ad" drops the belief.
        det.userSaysProgramme(ts + 21.0)
        assertEquals(0.0, det.vote(ts + 21.0).confidence)
    }

    @Test fun `always mode asks on the interval and a confident commercial becomes a script`() {
        val store = FileScriptStore()
        val judge = FakeJudge("COMMERCIAL 0.9: limited time offer")
        val det = JudgeDetector("judge_test", JudgeConfig(tieBreaker = false), judge, runAsync = { it.run() }, store = store); det.warmup()
        for (w in words(0.0, 30)) det.observeWord(w)
        det.hint(12.0, 0.0, muted = false)
        assertEquals(1, judge.asked.size)
        assertEquals(1, store.count(), "learned as a script"); assertTrue(det.scriptsDirty)
        for (w in words(12.0, 30)) det.observeWord(w)       // the same words again minutes later: not a duplicate script
        det.hint(24.0, 0.0, muted = false)
        assertEquals(2, judge.asked.size); assertEquals(1, store.count())
        assertEquals(1, det.learned)
    }

    @Test fun `a judge that throws is reported, not fatal`() {
        val det = JudgeDetector("judge_test", JudgeConfig(tieBreaker = false), { _, _ -> throw IllegalStateException("no network") }, runAsync = { it.run() }); det.warmup()
        for (w in words(0.0, 30)) det.observeWord(w)
        det.hint(12.0, 0.0, muted = false)
        assertEquals(0.0, det.vote(12.0).confidence)
        assertTrue(det.describe().startsWith("error: IllegalStateException"))
    }

    // ---- the ticker band ----
    private val W = 320; private val H = 240
    private val screen = Box(40, 30, 280, 210)

    /** A news picture: moving content, a solid lower-third band with crisp top/bottom lines and scrolling text inside. */
    private fun frame(band: Boolean, seed: Int): Gray {
        val r = Random(seed)
        val px = FloatArray(W * H) { 12f + r.nextInt(6) }
        val cx = 100 + (seed * 7) % 120; val cy = 80 + (seed * 11) % 90
        for (y in screen.y0 until screen.y1) for (x in screen.x0 until screen.x1) {
            val d = ((x - cx) * (x - cx) + (y - cy) * (y - cy)).toDouble()
            px[y * W + x] = (140 + 60 * sin(d / 900.0 + seed)).toFloat().coerceIn(80f, 200f)
        }
        if (band) {
            val top = screen.y1 - 40; val bottom = screen.y1 - 12
            for (y in top until bottom) for (x in screen.x0 until screen.x1) px[y * W + x] = 60f
            for (x in screen.x0 until screen.x1) { px[top * W + x] = 250f; px[(bottom - 1) * W + x] = 250f }
            // scrolling "text": bright blobs moving left each frame
            for (k in 0 until 12) { val x = screen.x0 + ((k * 21 - seed * 3) % 240 + 240) % 240; for (y in top + 8 until bottom - 8) if (x in screen.x0 until screen.x1) px[y * W + x] = 230f }
        }
        return Gray(W, H, px)
    }

    @Test fun `the band finder picks the ticker and its detector sees it go`() {
        val finder = LogoFinder()
        for (i in 0 until 40) assertTrue(finder.feed(frame(true, i)))
        val t = assertNotNull(finder.bandResult(), "no band found")
        assertTrue(t.roi.w > 0.99 && t.roi.y > 0.6, "full width, lower third: ${t.roi}")
        assertTrue(t.roi.h in 0.1..0.35, "a band, not the whole screen: ${t.roi}")
        val det = LogoAbsenceDetector(HANDHELD_LOGO_CONFIG.copy(searchPx = 4), t, name = "ticker_absence", noun = "ticker"); det.warmup()
        var ts = 0.0
        for (i in 100 until 110) { det.observeFrame(frame(true, i), ts); ts += 0.5 }
        assertTrue(det.programPresent, "score ${det.lastScore}")
        assertEquals("ticker seen", det.describe())
        for (i in 200 until 210) { det.observeFrame(frame(false, i), ts); ts += 0.5 }
        assertEquals(1.0, det.vote(ts).confidence, det.vote(ts).reason)
        assertEquals("ticker gone", det.describe())
        assertNull(LogoFinder().also { for (i in 0 until 12) it.feed(frame(false, i)) }.bandResult(), "no band, no template")
    }
}
