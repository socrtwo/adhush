package io.adhush.core

import kotlin.math.log2


/** 12-bit chroma signatures and their agreement. Verbatim port of fingerprint/audio_chroma.py. */
object Chroma {
    const val BITS = 12
    private const val A4_HZ = 440.0
    private const val FMIN = 60.0
    private const val FMAX = 3800.0

    fun chromaBits(samples: FloatArray, rate: Int): Int {
        if (samples.isEmpty()) return 0
        val spectrum = Dsp.rfftPower(Dsp.toDouble(samples))
        val freqs = Dsp.rfftFreqs(samples.size, rate)
        val energy = DoubleArray(BITS)
        var total = 0.0
        var any = false
        for (k in spectrum.indices) {
            val f = freqs[k]
            if (f < FMIN || f > FMAX) continue
            any = true
            total += spectrum[k]
            // numpy.round is half-to-even; so is rint. Python's % is non-negative.
            val semitone = Math.floorMod(Math.rint(12.0 * log2(f / A4_HZ)).toLong(), 12L).toInt()
            energy[semitone] += spectrum[k]
        }
        if (!any || total <= 0.0) return 0
        val sorted = energy.copyOf(); sorted.sort()
        val median = (sorted[5] + sorted[6]) / 2.0
        var bits = 0
        for (value in energy) bits = (bits shl 1) or (if (value > median) 1 else 0)
        return bits
    }

    /** Fraction of agreeing bits across two aligned block sequences. */
    fun agreement(a: List<Int>, b: List<Int>): Double {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0.0
        var matching = 0
        for (i in 0 until n) matching += BITS - Integer.bitCount(a[i] xor b[i])
        return matching.toDouble() / (n * BITS)
    }
}
