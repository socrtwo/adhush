package io.adhush.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChromaAndMatcherTest {
    @Test fun `agreement is 1 for identical, 0 for complementary sequences`() {
        assertEquals(1.0, Chroma.agreement(listOf(0xABC, 0x123), listOf(0xABC, 0x123)))
        assertEquals(0.0, Chroma.agreement(listOf(0xFFF), listOf(0x000)))
        assertEquals(0.0, Chroma.agreement(emptyList(), listOf(1)))
    }

    /** A deterministic "ad": a chord that changes every half second. */
    private fun adBlocks(seed: Int, count: Int = 12, startTs: Double = 0.0): List<Pair<Double, Int>> =
        (0 until count).map { i ->
            val notes = listOf(110.0 * (1 + (seed + i) % 5), 330.0 + 37 * ((seed * 3 + i) % 7), 900.0 + 111 * ((seed + 2 * i) % 6))
            val samples = FloatArray(24_000) { n -> notes.sumOf { f -> 0.1 * sin(2 * PI * f * n / 48_000.0) }.toFloat() }
            Pair(startTs + i * 0.5, Chroma.chromaBits(samples, 48_000))
        }

    @Test fun `a learned ad is recognised on its next airing and not confused with another`() {
        val store = FileFingerprintStore()
        val cfg = FingerprintConfig()
        val matcher = AudioMatcher(store, cfg)
        val learner = AudioLearner(store, matcher, cfg)
        val first = adBlocks(seed = 1, startTs = 100.0)
        val id = learner.learnSegment(100.0, 29.0, first)
        assertNotNull(id)
        assertEquals(1, store.count())

        // second airing, at a different media time: same chroma, confirm over three samples
        val again = adBlocks(seed = 1, startTs = 500.0)
        var match: Match? = null
        for (k in 6..again.size) match = matcher.feed(500.0 + k * 0.5, again.take(k))
        assertNotNull(match, "second airing not matched")
        assertEquals(id, match.adId)
        assertEquals(500.0, match.estStartTs, 0.3)
        assertEquals(30.0, match.durationS, "single sighting: duration is slot-snapped")

        matcher.reset()
        val other = adBlocks(seed = 4, startTs = 900.0)
        var wrong: Match? = null
        for (k in 6..other.size) wrong = matcher.feed(900.0 + k * 0.5, other.take(k))
        assertNull(wrong, "a different ad must not match")
    }

    @Test fun `durations fold in and the snap prior yields to the learned value`() {
        val store = FileFingerprintStore()
        val cfg = FingerprintConfig()
        val matcher = AudioMatcher(store, cfg)
        val learner = AudioLearner(store, matcher, cfg)
        val id = learner.learnSegment(0.0, 27.0, adBlocks(2))!!
        learner.observeDuration(id, 27.0); learner.observeDuration(id, 27.0)
        assertEquals(3, store.get(id)!!.sampleCount)
        assertEquals(27.0, matcher.effectiveDuration(id), 1e-9)
        assertNull(learner.learnSegment(0.0, 3.0, adBlocks(3)), "too short to learn")
    }

    @Test fun `the file store round-trips`(): Unit = kotlin.io.path.createTempDirectory().toFile().let { dir ->
        val f = java.io.File(dir, "ads.tsv")
        val a = FileFingerprintStore(f)
        val id = a.addAd(30.0, listOf(Pair(0.0, 5), Pair(0.5, 9)), nowTs = 12.0)
        a.updateDuration(id, 29.5, 2)
        val b = FileFingerprintStore(f)
        assertEquals(1, b.count())
        assertEquals(AdRecord(id, 29.5, 2), b.get(id))
        assertEquals(listOf(Pair(0.0, 5), Pair(0.5, 9)), b.audioBlocks(id))
    }
}
