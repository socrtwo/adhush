package io.adhush.core

import kotlin.math.log10
import kotlin.math.min

/** Ported verbatim from config.LoudnessConfig; the values encode validated tuning. */
data class LoudnessConfig(
    val windowS: Double = 1.5,
    val deltaLufs: Double = 2.5,
    val baselineS: Double = 120.0,
)

/**
 * EBU R128-style short-term loudness delta against a slow, freezing baseline.
 * A line-for-line port of detect/loudness.py, including its frequency-domain
 * K-weighting approximation and the Parseval bookkeeping.
 */
class LoudnessDetector(private val cfg: LoudnessConfig = LoudnessConfig()) : Detector {
    override val name = "loudness"

    private var weights: DoubleArray? = null
    private var weightsKey: Pair<Int, Int>? = null
    private val window = ArrayDeque<Pair<Double, Double>>()  // (duration, weighted mean-square)
    private var windowDur = 0.0
    private var baselineLufs: Double? = null
    private var observedS = 0.0
    var lastShortTerm = -70.0
        private set

    override fun warmup() {
        window.clear(); windowDur = 0.0; baselineLufs = null; observedS = 0.0; lastShortTerm = -70.0
    }

    private val warm: Boolean get() = baselineLufs != null && observedS >= 4 * cfg.windowS

    private fun kWeights(n: Int, rate: Int): DoubleArray {
        val freqs = Dsp.rfftFreqs(n, rate)
        return DoubleArray(freqs.size) { k ->
            val f2 = freqs[k] * freqs[k]
            val highpass = (f2 * f2) / (f2 * f2 + HP_HZ * HP_HZ * HP_HZ * HP_HZ)
            val shelf = 1.0 + SHELF_GAIN * f2 / (f2 + SHELF_HZ * SHELF_HZ)
            highpass * shelf
        }
    }

    private fun weightedMs(samples: FloatArray, rate: Int): Double {
        val key = Pair(samples.size, rate)
        if (weightsKey != key) { weights = kWeights(samples.size, rate); weightsKey = key }
        val w = weights!!
        val spectrum = Dsp.rfftPower(Dsp.toDouble(samples))
        val n = samples.size
        var total = 0.0
        for (k in spectrum.indices) {
            val doubled = if (k == 0 || (n % 2 == 0 && k == spectrum.size - 1)) 1.0 else 2.0
            total += spectrum[k] * doubled * w[k]
        }
        return total / (n.toDouble() * n)
    }

    override fun observeAudio(block: AudioBlock) {
        val ms = weightedMs(block.samples, block.sampleRate)
        window.addLast(Pair(block.duration, ms))
        windowDur += block.duration
        while (windowDur > cfg.windowS && window.size > 1) {
            windowDur -= window.removeFirst().first
        }
        observedS += block.duration

        var total = 0.0
        for ((d, m) in window) total += d * m
        val meanMs = if (windowDur > 0) total / windowDur else 0.0
        if (meanMs <= 0.0) { lastShortTerm = -70.0; return }
        lastShortTerm = -0.691 + 10.0 * log10(meanMs)

        if (lastShortTerm <= SILENCE_GATE_LUFS) return
        val baseline = baselineLufs
        if (baseline == null) { baselineLufs = lastShortTerm; return }
        if (lastShortTerm - baseline > cfg.deltaLufs / 2) return  // freeze while elevated
        val alpha = min(1.0, block.duration / cfg.baselineS)
        baselineLufs = baseline + alpha * (lastShortTerm - baseline)
    }

    override fun vote(ts: Double): DetectorVote {
        if (!warm) return vote(ts, 0.0, "warming observed_s=${"%.1f".format(observedS)}")
        val baseline = baselineLufs!!
        if (lastShortTerm <= SILENCE_GATE_LUFS) return vote(ts, 0.0, "gated st_lufs=${"%.1f".format(lastShortTerm)}")
        val delta = lastShortTerm - baseline
        val confidence = (delta / (cfg.deltaLufs * FULL_CONF_FACTOR)).coerceIn(0.0, 1.0)
        return vote(ts, confidence, "loudness delta_lufs=${"%.2f".format(delta)} st_lufs=${"%.1f".format(lastShortTerm)} baseline_lufs=${"%.1f".format(baseline)}")
    }

    companion object {
        const val HP_HZ = 38.0
        const val SHELF_HZ = 1500.0
        const val SHELF_GAIN = 1.505
        const val SILENCE_GATE_LUFS = -55.0
        const val FULL_CONF_FACTOR = 1.4
    }
}
