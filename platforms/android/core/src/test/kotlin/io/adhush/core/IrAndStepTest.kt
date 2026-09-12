package io.adhush.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IrAndStepTest {
    @Test fun `a Sharp press is two 15-bit frames, the second with command, expansion and check inverted`() {
        val p = SharpIr.press(address = 1, command = 0x15)   // volume down
        assertEquals(31 + 1 + 31, p.size)
        assertEquals(SharpIr.MARK_US, p[0])
        assertEquals(SharpIr.ONE_SPACE_US, p[1], "address bit 0 of 1 is a 1")
        assertEquals(SharpIr.ZERO_SPACE_US, p[3], "address bit 1 is a 0")
        assertEquals(SharpIr.GAP_US, p[31])
        // command 0x15 = 0b00010101, LSB first: 1,0,1,0,1,0,0,0 → bits 5..12 of the frame
        val cmdSpaces = (5 until 13).map { p[2 * it + 1] }
        assertEquals(listOf(1680, 680, 1680, 680, 1680, 680, 680, 680), cmdSpaces)
        val cmdSpaces2 = (5 until 13).map { p[32 + 2 * it + 1] }
        assertEquals(listOf(680, 1680, 680, 1680, 680, 1680, 1680, 1680), cmdSpaces2, "inverted in the second frame")
        assertEquals(SharpIr.ONE_SPACE_US, p[2 * 13 + 1], "expansion 1"); assertEquals(SharpIr.ZERO_SPACE_US, p[32 + 2 * 13 + 1])
        assertEquals(SharpIr.ZERO_SPACE_US, p[2 * 14 + 1], "check 0"); assertEquals(SharpIr.ONE_SPACE_US, p[32 + 2 * 14 + 1])
        assertEquals(SharpIr.MARK_US, p.last())
        assertTrue(p.all { it > 0 })
    }

    @Test fun `the LIRC-style word packs address low`() {
        assertEquals(0x1 or (0x16 shl 5) or (1 shl 13), SharpIr.word(1, 0x16))
    }

    private class Presses : KeySender {
        val log = ArrayList<Pair<TvKey, Int>>()
        override fun press(key: TvKey, times: Int) { log.add(Pair(key, times)) }
    }

    @Test fun `step ducking presses down then up the same count, persisting the count first`() {
        val s = Presses(); val persist = MemoryDuckPersistence()
        val ctl = StepVolumeController(s, persist, duckLevel = 4, normalVolume = 19)
        ctl.duck()
        assertEquals(listOf(Pair(TvKey.VOLUME_DOWN, 15)), s.log); assertEquals(15, persist.load()); assertTrue(ctl.ducked)
        ctl.duck(); assertEquals(1, s.log.size, "ducking twice presses nothing more")
        ctl.restore()
        assertEquals(Pair(TvKey.VOLUME_UP, 15), s.log.last()); assertEquals(null, persist.load()); assertFalse(ctl.ducked)
    }

    @Test fun `a crash while ducked is repaired on the next start by replaying the count`() {
        val s = Presses(); val persist = MemoryDuckPersistence(); persist.save(9)
        val ctl = StepVolumeController(s, persist, duckLevel = 4, normalVolume = 30)
        assertTrue(ctl.recoverOnStart())
        assertEquals(listOf(Pair(TvKey.VOLUME_UP, 9)), s.log); assertEquals(null, persist.load())
        assertFalse(ctl.recoverOnStart())
    }
}
