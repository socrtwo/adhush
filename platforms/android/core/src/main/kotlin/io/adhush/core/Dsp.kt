package io.adhush.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The few signal primitives every detector shares, written to match numpy's
 * results closely enough that the conformance fixture holds to ~1e-9:
 * an arbitrary-length FFT (Bluestein over radix-2), rfft power spectra, block
 * dBFS and spectral flatness.
 */
object Dsp {
    /** In-place radix-2 FFT; n must be a power of two. */
    private fun fftPow2(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {  // bit reversal
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val wRe = cos(ang); val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var cRe = 1.0; var cIm = 0.0
                for (k in 0 until len / 2) {
                    val aRe = re[i + k]; val aIm = im[i + k]
                    val bRe = re[i + k + len / 2] * cRe - im[i + k + len / 2] * cIm
                    val bIm = re[i + k + len / 2] * cIm + im[i + k + len / 2] * cRe
                    re[i + k] = aRe + bRe; im[i + k] = aIm + bIm
                    re[i + k + len / 2] = aRe - bRe; im[i + k + len / 2] = aIm - bIm
                    val nRe = cRe * wRe - cIm * wIm; cIm = cRe * wIm + cIm * wRe; cRe = nRe
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    private fun nextPow2(v: Int): Int { var p = 1; while (p < v) p = p shl 1; return p }

    /** Forward DFT of a real sequence of any length: returns (re, im) of all n bins. */
    fun dft(x: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = x.size
        if (n == 0) return Pair(DoubleArray(0), DoubleArray(0))
        if (n and (n - 1) == 0) {
            val re = x.copyOf(); val im = DoubleArray(n)
            fftPow2(re, im, false)
            return Pair(re, im)
        }
        // Bluestein: X_k = w_k * sum_j (x_j w_j) * conj(w_{k-j}), w_k = exp(-i*pi*k^2/n)
        val m = nextPow2(2 * n - 1)
        val chirpRe = DoubleArray(n); val chirpIm = DoubleArray(n)
        for (k in 0 until n) {
            val kk = (k.toLong() * k) % (2L * n)   // keep the angle exact for large k
            val ang = -PI * kk / n
            chirpRe[k] = cos(ang); chirpIm[k] = sin(ang)
        }
        val aRe = DoubleArray(m); val aIm = DoubleArray(m)
        for (k in 0 until n) { aRe[k] = x[k] * chirpRe[k]; aIm[k] = x[k] * chirpIm[k] }
        val bRe = DoubleArray(m); val bIm = DoubleArray(m)
        bRe[0] = chirpRe[0]; bIm[0] = -chirpIm[0]
        for (k in 1 until n) { bRe[k] = chirpRe[k]; bIm[k] = -chirpIm[k]; bRe[m - k] = bRe[k]; bIm[m - k] = bIm[k] }
        fftPow2(aRe, aIm, false); fftPow2(bRe, bIm, false)
        for (i in 0 until m) {
            val r = aRe[i] * bRe[i] - aIm[i] * bIm[i]
            val im = aRe[i] * bIm[i] + aIm[i] * bRe[i]
            aRe[i] = r; aIm[i] = im
        }
        fftPow2(aRe, aIm, true)
        val outRe = DoubleArray(n); val outIm = DoubleArray(n)
        for (k in 0 until n) {
            outRe[k] = aRe[k] * chirpRe[k] - aIm[k] * chirpIm[k]
            outIm[k] = aRe[k] * chirpIm[k] + aIm[k] * chirpRe[k]
        }
        return Pair(outRe, outIm)
    }

    /** |rfft(x)|^2 for bins 0..n/2, like numpy's abs(rfft(x))**2. */
    fun rfftPower(x: DoubleArray): DoubleArray {
        val n = x.size
        if (n == 0) return DoubleArray(0)
        val (re, im) = dft(x)
        val bins = n / 2 + 1
        return DoubleArray(bins) { k -> re[k] * re[k] + im[k] * im[k] }
    }

    fun rfftFreqs(n: Int, rate: Int): DoubleArray = DoubleArray(n / 2 + 1) { k -> k.toDouble() * rate / n }

    fun toDouble(samples: FloatArray): DoubleArray = DoubleArray(samples.size) { samples[it].toDouble() }

    /** RMS level of a float32 block in dBFS. Mirrors silence.block_dbfs. */
    fun blockDbfs(samples: FloatArray): Double {
        if (samples.isEmpty()) return -120.0
        var acc = 0.0
        for (s in samples) { val d = s.toDouble(); acc += d * d }
        val rms = sqrt(acc / samples.size)
        if (rms <= 0.0) return -120.0
        return max(-120.0, 20.0 * log10(rms))
    }

    /** Geometric/arithmetic mean of the power spectrum without DC. Mirrors silence.spectral_flatness. */
    fun spectralFlatness(samples: FloatArray): Double {
        val spectrum = rfftPower(toDouble(samples))
        if (spectrum.size <= 1) return 1.0
        var maxV = 0.0
        for (k in 1 until spectrum.size) maxV = max(maxV, spectrum[k])
        if (maxV == 0.0) return 1.0
        var logSum = 0.0; var sum = 0.0
        val count = spectrum.size - 1
        for (k in 1 until spectrum.size) { val v = spectrum[k] + 1e-12; logSum += ln(v); sum += v }
        val geometric = exp(logSum / count)
        val arithmetic = sum / count
        return geometric / arithmetic
    }
}
