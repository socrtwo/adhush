package io.adhush.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR 0020: the player's own "AD" badge, read off the screen during stream learning. */
class BadgeTest {
    @Test fun `whole words only, with the count or the timer when there is one`() {
        assertEquals("Ad 1 of 3", BadgeDetector.badgeIn("Ad 1 of 3   LIVE"))
        assertEquals("AD 0:15", BadgeDetector.badgeIn("AD · 0:15"))
        assertEquals("Advertisement", BadgeDetector.badgeIn("Advertisement"))
        assertEquals("Your video will resume", BadgeDetector.badgeIn("Your video will resume after this break"))
        assertNull(BadgeDetector.badgeIn("already on the road ahead"))   // AutoAdMuter's mistake
        assertNull(BadgeDetector.badgeIn("BREAKING NEWS 4:31 PM"))
        assertNull(BadgeDetector.badgeIn("ADHD awareness week"))
    }

    @Test fun `a badge votes while seen, then its absence is programme evidence, and no scans make it inert`() {
        val d = BadgeDetector(); d.warmup()
        assertFalse(d.voting)
        d.observeText(1.0, "MS NOW LIVE")
        assertTrue(d.voting); assertEquals(0.0, d.vote(1.0).confidence)
        d.observeText(2.0, "Ad 1 of 2")
        assertEquals(1.0, d.vote(2.5).confidence); assertFalse(d.programPresent)
        d.observeText(3.0, "Ad 2 of 2"); d.observeText(4.0, "Ad 2 of 2")
        d.observeText(5.0, "LIVE"); d.observeText(6.0, "LIVE"); d.observeText(7.0, "LIVE")
        assertEquals(0.0, d.vote(7.0).confidence)
        assertTrue(d.programPresent, "no badge for two seconds of scans after one: the break is over")
        d.observeAudio(AudioBlock(30.0, FloatArray(10), 48_000))
        assertFalse(d.voting, "no scan for ten seconds: inert")
    }
}
