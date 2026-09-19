package io.adhush.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ADR 0017: the break clock, timed manual ducks, and the remote keys. */
class ClockAndTimedDuckTest {
    private class MemStore : ClockStore {
        var saved: ClockCounts? = null; var saves = 0
        override fun load() = saved
        override fun save(counts: ClockCounts) { saved = ClockCounts(counts.breaks.copyOf(), counts.seen.copyOf(), counts.lengths.copyOf()); saves++ }
    }

    @Test fun `the clock learns the minutes breaks land on and votes only where it has watched enough`() {
        val store = MemStore()
        val c = ClockDetector(store, ClockConfig(minHours = 3, fullFraction = 0.6))
        // Five hours watched minute by minute; in four of them a break ran from :20 to :22.
        for (h in 0 until 5) {
            for (m in 0 until 60) c.tick(h * 3600.0 + m * 60.0 + 1.0)
            if (h < 4) c.learn(h * 3600.0 + 20 * 60.0, h * 3600.0 + 22 * 60.0)
        }
        c.tick(5 * 3600.0 + 20 * 60.0 + 5.0)   // :20 in a sixth hour
        assertTrue(c.voting)
        assertEquals(1.0, c.vote(0.0).confidence, c.vote(0.0).reason)   // 4 of 6 hours ≥ 0.6
        c.tick(5 * 3600.0 + 40 * 60.0)
        assertTrue(c.voting); assertEquals(0.0, c.vote(0.0).confidence)   // watched, never a break
        assertEquals(listOf(20, 21), c.breakMinutes())
        assertTrue(store.saves > 0)
        // A fresh detector at a minute it has never watched stays inert.
        val fresh = ClockDetector(MemStore()); fresh.tick(50 * 60.0)
        assertFalse(fresh.voting)
        // Implausible "breaks" teach nothing.
        val before = store.saved!!.breaks.copyOf()
        c.learn(0.0, 5.0); c.learn(0.0, 900.0)
        assertTrue(before.contentEquals(store.saved!!.breaks))
    }

    @Test fun `the clock file round-trips`() {
        val f = File.createTempFile("clock", ".tsv"); f.delete()
        val a = ClockDetector(FileClockStore(f))
        for (h in 0 until 3) { for (m in 0 until 60) a.tick(h * 3600.0 + m * 60.0); a.learn(h * 3600.0 + 600.0, h * 3600.0 + 720.0) }
        val b = ClockDetector(FileClockStore(f)); b.tick(3 * 3600.0 + 600.0)
        assertTrue(b.voting); assertEquals(1.0, b.vote(0.0).confidence, b.vote(0.0).reason)
        f.delete()
    }

    private class Ctl : MuteController {
        var muted = false; val log = ArrayList<String>()
        override fun mute() { muted = true; log.add("mute") }
        override fun unmute() { muted = false; log.add("unmute") }
        override fun state() = muted
        override fun close() {}
    }

    @Test fun `a timed duck turns the set down and back up on its own, learning nothing`() {
        val ctl = Ctl(); val store = FileFingerprintStore(); var wall = 1_000_000.0
        val clockStore = MemStore()
        val engine = Assembly.engine(ctl, store, clock = ClockDetector(clockStore), wallClock = { wall })
        var ts = 0.0
        fun tick(n: Int) { repeat(n) { engine.onAudio(AudioBlock(ts, FloatArray(4800), 48_000)); ts += 0.1; wall += 0.1 } }
        tick(10)
        assertTrue(engine.duckFor(ts, 30.0))
        assertTrue(ctl.muted); assertTrue(engine.status().timedS in 29.0..30.5, "timedS=${engine.status().timedS}")
        assertEquals(listOf("user:timed 30"), engine.transitions.last().reasons)
        tick(290)
        assertTrue(ctl.muted, "still ducked at 29 s")
        tick(20)
        assertFalse(ctl.muted, "restored after 30 s")
        assertEquals(listOf("user:timed_end"), engine.transitions.last().reasons)
        assertEquals(0, store.count(), "a timed duck is not a learned break")
        assertEquals(0, clockStore.saved?.breaks?.sum() ?: 0, "nor does it teach the clock")
        assertEquals(0.0, engine.status().timedS)
        // Pressed again at once (still in the recovery pause) it still ducks; Show's back ends it early.
        assertTrue(engine.duckFor(ts, 60.0), "duck during recovery"); assertTrue(ctl.muted); tick(5); assertTrue(engine.showIsBack(ts)); assertFalse(ctl.muted)
    }

    @Test fun `remote keys frame as RCKY with the two-digit code`() {
        assertEquals("RCKY12  \r", String(Aquos.frame("RCKY", "12"), Charsets.US_ASCII))
        val sent = ArrayList<String>()
        val client = SharpIpClient { payload -> sent.add(String(payload, Charsets.US_ASCII)); "OK\r".toByteArray() }
        assertTrue(client.press(RemoteKey.POWER))
        assertEquals("RCKY12  \r", sent.single())
        assertEquals(RemoteKey.VOL_UP, RemoteKey.of("VOL_UP"))
    }
}
