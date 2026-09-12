package io.adhush.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DspTest {
    private fun naiveDft(x: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = x.size
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (k in 0 until n) {
            var sr = 0.0; var si = 0.0
            for (j in 0 until n) { val a = -2 * PI * k * j / n; sr += x[j] * cos(a); si += x[j] * sin(a) }
            re[k] = sr; im[k] = si
        }
        return Pair(re, im)
    }

    @Test fun `bluestein matches a naive dft for the 4800-sample block`() {
        val rnd = Random(7)
        val x = DoubleArray(4800) { rnd.nextDouble(-1.0, 1.0) }
        val (re, im) = Dsp.dft(x)
        val (nre, nim) = naiveDft(x)
        var worst = 0.0
        for (k in 0 until x.size) { worst = maxOf(worst, abs(re[k] - nre[k]), abs(im[k] - nim[k])) }
        assertTrue(worst < 1e-6, "worst abs error $worst")
    }

    @Test fun `power-of-two and odd lengths both work`() {
        for (n in listOf(1, 2, 8, 15, 100, 4800)) {
            val x = DoubleArray(n) { sin(0.3 * it) }
            val p = Dsp.rfftPower(x)
            assertEquals(n / 2 + 1, p.size)
        }
    }

    @Test fun `dbfs of a known sine and flatness extremes`() {
        val sine = FloatArray(4800) { (0.5 * sin(2 * PI * 440 * it / 48000.0)).toFloat() }
        // RMS of 0.5 amplitude sine = 0.3536 -> -9.03 dBFS
        assertEquals(-9.03, Dsp.blockDbfs(sine), 0.02)
        assertEquals(-120.0, Dsp.blockDbfs(FloatArray(100)))
        assertTrue(Dsp.spectralFlatness(sine) < 0.05, "a tone is not flat")
        val rnd = Random(1)
        val noise = FloatArray(4800) { rnd.nextFloat() * 2 - 1 }
        assertTrue(Dsp.spectralFlatness(noise) > 0.5, "white noise is flat")
    }
}
