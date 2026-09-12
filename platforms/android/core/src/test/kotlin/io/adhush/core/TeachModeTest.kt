package io.adhush.core

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Teach mode end to end: "Is an ad" holds the duck through a whole break,
 * "Show's back" restores and learns it as material, and the spots are then
 * recognised in a different order, alone, and cut down.
 */
class TeachModeTest {
    private class FakeController : MuteController {
        var muted = false; var mutes = 0; var unmutes = 0
        override fun mute() { muted = true; mutes++ }
        override fun unmute() { muted = false; unmutes++ }
        override fun state() = muted
        override fun close() {}
    }

    private val rate = 48_000
    private val n = 4_800   // 100 ms blocks

    /** A "commercial": a fixed, seeded sequence of three-note chords changing every half second. */
    private fun material(seed: Int, seconds: Int): List<FloatArray> {
        val rnd = Random(seed)
        val chords = List(seconds * 2) { List(3) { rnd.nextInt(12) } }
        var sample = 0L
        return List(seconds * 10) { b ->
            val chord = chords[b / 5]
            FloatArray(n) { k ->
                val t = (sample + k).toDouble() / rate
                var v = 0.0
                for (pc in chord) v += sin(2 * PI * 261.63 * 2.0.pow(pc / 12.0) * t)
                (0.12 * v).toFloat()
            }.also { sample += n }
        }
    }

    private class Run(val engine: Engine, val ctl: FakeController) {
        var i = 0
        val muteTimes = ArrayList<Double>(); val unmuteTimes = ArrayList<Double>()
        fun feed(blocks: List<FloatArray>) {
            for (b in blocks) {
                val was = ctl.muted
                engine.onAudio(AudioBlock(i * 0.1, b, 48_000))
                if (ctl.muted && !was) muteTimes.add(i * 0.1)
                if (!ctl.muted && was) unmuteTimes.add(i * 0.1)
                i++
            }
        }
        val now get() = i * 0.1
    }

    private fun run(store: FingerprintStore = FileFingerprintStore()): Run {
        val ctl = FakeController()
        val engine = Assembly.engine(ctl, store, fusionCfg = FusionConfig(), weights = mapOf("silence" to 0.15, "loudness" to 0.30, "fingerprint" to 0.30))
        return Run(engine, ctl)
    }

    private val show = material(1, 40)
    private val adA = material(101, 20); private val adB = material(202, 20); private val adC = material(303, 20)

    @Test fun `a taught break is held, learned as material, and recognised in any order`() {
        val store = FileFingerprintStore()
        val r = run(store)
        r.feed(show.take(200))                                   // 20 s of show
        assertFalse(r.ctl.muted)
        assertTrue(r.engine.confirmAd(r.now)); assertTrue(r.ctl.muted)
        assertTrue(r.engine.status().teaching)
        r.feed(adA); r.feed(adB); r.feed(adC)                    // a 60 s break, still ducked throughout
        assertTrue(r.ctl.muted, "the hold must outlast the detectors' silence: ${r.unmuteTimes}")
        assertEquals(1, r.ctl.mutes)
        assertTrue(r.engine.showIsBack(r.now)); assertFalse(r.ctl.muted)
        assertFalse(r.engine.status().teaching)
        assertEquals(1, store.count(), "the break is one material record")
        assertEquals(AdKind.MATERIAL, store.ads().single().kind)
        assertEquals(60.0, store.ads().single().durationS, 0.2)

        r.feed(show.take(200))                                   // the show, not recognised
        assertEquals(1, r.ctl.unmutes); assertEquals(1, r.ctl.mutes)

        // The same spots come back in a different order, then the show returns.
        val breakStart = r.now
        r.feed(adC); r.feed(adA)
        val breakEnd = r.now
        r.feed(show.drop(200))                                   // 20 s of new show material
        assertEquals(2, r.ctl.mutes, "the reordered break was recognised: ${r.muteTimes}")
        val mutedAt = r.muteTimes.last()
        assertTrue(mutedAt - breakStart < 8.0, "ducked within 8 s of the break starting (took ${mutedAt - breakStart})")
        assertEquals(2, r.ctl.unmutes, "and released afterwards")
        val releasedAt = r.unmuteTimes.last()
        assertTrue(releasedAt >= breakEnd - 1.0, "not released before the break ended (${releasedAt} vs $breakEnd)")
        assertTrue(releasedAt - breakEnd < 7.0, "released within grace + dwell of the show returning (took ${releasedAt - breakEnd})")
    }

    @Test fun `a 15-second cut-down of a taught spot is recognised on its own`() {
        val store = FileFingerprintStore()
        val r = run(store)
        r.feed(show.take(100))
        r.engine.confirmAd(r.now); r.feed(adB); r.feed(adA); r.engine.showIsBack(r.now)
        r.feed(show.take(150))
        val start = r.now
        r.feed(adA.take(150))                                    // the first 15 s of A, alone
        r.feed(show.drop(200))
        assertEquals(2, r.ctl.mutes, "the teach duck, then the cut-down recognised: ${r.muteTimes}")
        assertTrue(r.muteTimes.last() - start < 8.0, "took ${r.muteTimes.last() - start}")
        assertEquals(2, r.ctl.unmutes)
    }

    @Test fun `Not an ad during teaching cancels without learning and a short bracket is ignored`() {
        val store = FileFingerprintStore()
        val r = run(store)
        r.feed(show.take(50))
        r.engine.confirmAd(r.now); r.feed(adA.take(50))
        assertTrue(r.engine.rejectAd(r.now)); assertFalse(r.ctl.muted)
        assertEquals(0, store.count())
        assertFalse(r.engine.status().teaching)
        assertNull(r.engine.status().let { if (it.teaching) it else null })
        // A break too short to be a break is ignored too.
        r.feed(show.take(50))
        r.engine.confirmAd(r.now); r.feed(adA.take(20)); r.engine.showIsBack(r.now)
        assertEquals(0, store.count(), "2 s is a slip of the finger")
        assertFalse(r.engine.showIsBack(r.now), "nothing to end")
    }

    @Test fun `the ceiling ends a forgotten teach session and still learns it`() {
        val store = FileFingerprintStore()
        val r = run(store)
        r.feed(show.take(50))
        r.engine.confirmAd(r.now)
        val longBreak = material(404, 250)                       // longer than the 240 s ceiling
        r.feed(longBreak)
        assertFalse(r.ctl.muted, "the ceiling restored the volume")
        assertEquals(1, store.count())
        assertNotNull(store.ads().single().let { it.durationS }.takeIf { it in 239.0..241.0 }, "learned up to the ceiling: ${store.ads()}")
    }

    @Test fun `material records survive the file round-trip`(): Unit = kotlin.io.path.createTempDirectory().toFile().let { dir ->
        val f = java.io.File(dir, "ads.tsv")
        val a = FileFingerprintStore(f)
        val id = a.addAd(58.5, listOf(Pair(0.0, 5), Pair(0.5, 9)), 1.0, AdKind.MATERIAL)
        val b = FileFingerprintStore(f)
        assertEquals(AdRecord(id, 58.5, 1, AdKind.MATERIAL), b.get(id))
    }
}
