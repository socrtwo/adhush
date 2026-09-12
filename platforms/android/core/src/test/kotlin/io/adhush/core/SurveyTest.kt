package io.adhush.core

import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SurveyTest {
    private fun block(i: Int, amp: Double, rate: Int = 48_000, n: Int = 4_800): AudioBlock {
        val s = FloatArray(n) { k -> (amp * sin(2 * PI * 440.0 * (i * n + k) / rate)).toFloat() }
        return AudioBlock(i * n.toDouble() / rate, s, rate)
    }

    @Test fun `records every block, finishes on time, and writes a digest`() {
        val survey = RoomSurvey(durationS = 15.0)
        var finishedAt = -1
        for (i in 0 until 200) {   // 20 s: quiet first 5 s, then a tone
            val amp = if (i < 50) 0.0005 else 0.2
            if (survey.feed(block(i, amp), ducked = i in 120..129) && finishedAt < 0) finishedAt = i
        }
        assertEquals(149, finishedAt, "completes at the block that crosses 15 s")
        assertEquals(150, survey.rows.size, "nothing recorded after completion")
        assertTrue(survey.done)
        assertFalse(survey.rows.first().ducked); assertTrue(survey.rows[125].ducked)
        val text = survey.summary()
        assertTrue("level dBFS" in text && "verdict" in text && "10 while ducked" in text, text)
        val tmp = File.createTempFile("survey", ".tsv"); tmp.deleteOnExit()
        survey.writeTsv(tmp)
        val lines = tmp.readLines()
        assertEquals("ts_s\tdbfs\tflatness\tst_lufs\tbaseline_lufs\tfloor_dbfs\tsilence_conf\tloudness_conf\tducked", lines[0])
        assertEquals(151, lines.size)
        assertTrue(lines[1].startsWith("0.00\t"), lines[1])
        assertTrue(lines[130].endsWith("\t0") || lines[130].endsWith("\t1"))
    }

    @Test fun `an empty survey has a digest too`() {
        assertTrue("no audio" in RoomSurvey().summary())
    }
}
