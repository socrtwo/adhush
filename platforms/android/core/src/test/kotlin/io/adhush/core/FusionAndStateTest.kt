package io.adhush.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FusionAndStateTest {
    private fun v(name: String, c: Double, ts: Double = 0.0) = DetectorVote(name, ts, c, "x")

    @Test fun `a lone default-weight detector can hold but never start a mute`() {
        val fusion = Fusion(FusionConfig(), emptyMap(), listOf("silence", "loudness", "fingerprint"))
        val alone = fusion.combine(listOf(v("loudness", 1.0)), 0.0)
        assertEquals(0.5, alone.confidence, 1e-9)   // normaliser floor 0.30, mass 0.15... wait enabled mass 0.45/2=0.225 -> floor 0.30
        assertFalse(alone.mute)
        val two = fusion.combine(listOf(v("loudness", 1.0), v("silence", 1.0)), 0.1)
        assertTrue(two.confidence >= 0.99 && two.mute)
        val holding = fusion.combine(listOf(v("loudness", 1.0)), 0.2)
        assertTrue(holding.mute, "0.5 exceeds unmute_confidence 0.45, so a lone vote holds")
    }

    @Test fun `entering AD needs the dwell and full confidence at completion`() {
        val m = AdStateMachine(FusionConfig())
        val hot = MuteDecision(0.0, true, 0.9, emptyList())
        assertNull(m.update(hot)); assertEquals(AdState.SUSPECT_AD, m.state)
        assertNull(m.update(hot.copy(ts = 0.5)))
        // dwell served, but confidence fell below the threshold: no mute
        assertNull(m.update(MuteDecision(1.0, true, 0.6, emptyList())))
        assertEquals(AdState.SUSPECT_AD, m.state)
        assertEquals(Action.MUTE, m.update(hot.copy(ts = 1.1)))
        assertTrue(m.muted)
    }

    @Test fun `unmute is fast, ceiling is hard, promotion is instant, recovery ignores promotion`() {
        val m = AdStateMachine(FusionConfig(maxMuteS = 5.0))
        assertEquals(Action.MUTE, m.update(MuteDecision(0.0, true, 1.0, emptyList()), promote = true))
        assertNull(m.update(MuteDecision(0.1, false, 0.0, emptyList())))
        assertEquals(Action.UNMUTE, m.update(MuteDecision(0.5, false, 0.0, emptyList())))
        assertEquals(AdState.RECOVERY, m.state)
        assertNull(m.update(MuteDecision(0.6, true, 1.0, emptyList()), promote = true), "recovery is deaf")
        assertNull(m.update(MuteDecision(2.6, false, 0.0, emptyList())))
        assertEquals(AdState.PROGRAM, m.state)
        assertEquals(Action.MUTE, m.update(MuteDecision(3.0, true, 1.0, emptyList()), promote = true))
        // fp_hold with no programme evidence holds through anything, until the ceiling
        for (t in 31..79) assertNull(m.update(MuteDecision(t / 10.0, false, 0.0, emptyList()), fpHold = true))
        assertEquals(Action.UNMUTE, m.update(MuteDecision(8.0, false, 0.0, emptyList()), fpHold = true))
    }
}
