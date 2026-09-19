package io.adhush.core

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sqrt

/** Ported verbatim from config.LoudnessConfig; the values encode validated tuning. */
data class LoudnessConfig(
    val windowS: Double = 1.5,
    val deltaLufs: Double = 2.5,
    val baselineS: Double = 120.0,
    /** Elevated longer than any ad pod: the baseline is wrong and may follow. */
    val maxElevatedS: Double = 180.0,
    /** Crest factor (ADR 0021): a peak-to-RMS drop of this many dB under the programme's is a full crest vote; 0 turns it off. */
    val crestDropDb: Double = 4.0,
)

/**
 * EBU R128-style short-term loudness delta against a slow, freezing baseline.
 * A line-for-line port of detect/loudness.py, including its frequency-domain
 * K-weighting approximation and the Parseval bookkeeping.
 *
 * Duck compensation (ADR 0016, after admuffs): the phone's mic hears the set
 * get quieter the moment the app ducks it and would call that "programme
 * resumed". Told of the duck, the detector freezes its vote for one window,
 * measures how far the room dropped, and adds that back to every later
 * reading. If the ducked set is buried under the room (near the silence gate,
 * or the mic hears flat noise — fans, not a broadcast) it goes inert instead.
 *
 * Crest factor (ADR 0021): commercials are compressed harder than the show,
 * so their peak-to-RMS ratio sits several dB lower even when they are no
 * louder. A drop against the same slow baseline adds up to half a vote —
 * never a whole one, so a music bed in the programme cannot duck by itself.
 */
class LoudnessDetector(private val cfg: LoudnessConfig = LoudnessConfig()) : Detector {
    override val name = "loudness"

    private var weights: DoubleArray? = null
    private var weightsKey: Pair<Int, Int>? = null
    private val window = ArrayDeque<Triple<Double, Double, Double>>()  // (duration, weighted mean-square, crest dB)
    private var windowDur = 0.0
    var baselineLufs: Double? = null
        private set
    var baselineCrestDb: Double? = null
        private set
    /** Peak-to-RMS ratio over the short-term window, in dB. */
    var crestDb = 0.0
        private set
    private var observedS = 0.0
    private var ungatedS = 0.0   // consecutive seconds with the short-term value above the gate
    private var elevatedS = 0.0  // consecutive seconds above baseline + delta/2
    var lastShortTerm = -70.0
        private set
    private var ducked = false
    /** Added to every reading while ducked, so the ducked ad is judged on the original scale. */
    var duckOffsetDb = 0.0
        private set
    private var preDuckLufs: Double? = null
    private val settleFlatness = ArrayList<Double>()
    private var settleUntil: Double? = null
    private var frozen: Pair<Double, String>? = null
    private var buried = false
    private var skipBaselineUntil = Double.NEGATIVE_INFINITY

    override fun warmup() {
        window.clear(); windowDur = 0.0; baselineLufs = null; baselineCrestDb = null; crestDb = 0.0; observedS = 0.0
        ungatedS = 0.0; elevatedS = 0.0; lastShortTerm = -70.0
        resetDuck()
    }

    private fun resetDuck() {
        ducked = false; duckOffsetDb = 0.0; preDuckLufs = null; settleUntil = null; frozen = null; buried = false; settleFlatness.clear()
        skipBaselineUntil = Double.NEGATIVE_INFINITY
    }

    /** Inert while the ducked set is buried under the room: no offset can recover it. */
    override val voting: Boolean get() = !buried

    override fun audioDucked(ts: Double, ducked: Boolean) {
        if (!ducked) {
            resetDuck()
            skipBaselineUntil = ts + cfg.windowS   // the window still holds ducked audio
            return
        }
        if (this.ducked) return
        this.ducked = true
        if (!warm || lastShortTerm <= SILENCE_GATE_LUFS) { buried = true; return }   // nothing to measure against
        preDuckLufs = lastShortTerm
        val v = vote(ts)
        frozen = Pair(v.confidence, v.reason)
        settleUntil = ts + cfg.windowS + DUCK_SETTLE_MARGIN_S
    }

    /** One window after the duck: measure the drop, or give up. */
    private fun settle(raw: Double) {
        settleUntil = null; frozen = null
        val pre = preDuckLufs ?: return
        val noise = settleFlatness.isNotEmpty() && settleFlatness.average() >= BURIED_FLATNESS
        settleFlatness.clear()
        if (raw <= SILENCE_GATE_LUFS + DUCK_HEADROOM_DB || noise) { buried = true; return }
        duckOffsetDb = (pre - raw).coerceIn(0.0, MAX_DUCK_OFFSET_DB)
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
        window.addLast(Triple(block.duration, ms, blockCrestDb(block.samples)))
        windowDur += block.duration
        while (windowDur > cfg.windowS && window.size > 1) {
            windowDur -= window.removeFirst().first
        }
        observedS += block.duration

        var total = 0.0
        var crest = 0.0
        for ((d, m, c) in window) { total += d * m; crest += d * c }
        val meanMs = if (windowDur > 0) total / windowDur else 0.0
        if (windowDur > 0) crestDb = crest / windowDur
        val raw = if (meanMs <= 0.0) -70.0 else -0.691 + 10.0 * log10(meanMs)
        val now = block.ts + block.duration
        settleUntil?.let { settleFlatness.add(Dsp.spectralFlatness(block.samples)); if (now >= it) settle(raw) }
        lastShortTerm = raw + duckOffsetDb
        if (meanMs <= 0.0) return

        if (lastShortTerm <= SILENCE_GATE_LUFS) { ungatedS = 0.0; return }
        ungatedS += block.duration
        if (settleUntil != null || now < skipBaselineUntil) return   // a window straddling a volume change must not move the baseline
        val baseline = baselineLufs
        if (baseline == null) {
            // Only once the whole window is un-gated programme: a window still
            // filling with the mic's start-up silence reads low and would freeze it there.
            if (ungatedS >= cfg.windowS) { baselineLufs = lastShortTerm; baselineCrestDb = crestDb }
            return
        }
        if (lastShortTerm - baseline > cfg.deltaLufs / 2) {
            // Freeze while elevated (suspected ad) — unless it has outlasted any ad
            // pod, which means the baseline itself is wrong and must follow.
            elevatedS += block.duration
            if (elevatedS < cfg.maxElevatedS) return
        } else elevatedS = 0.0
        val alpha = min(1.0, block.duration / cfg.baselineS)
        baselineLufs = baseline + alpha * (lastShortTerm - baseline)
        baselineCrestDb?.let { baselineCrestDb = it + alpha * (crestDb - it) }
    }

    override fun vote(ts: Double): DetectorVote {
        frozen?.let { (c, r) -> return vote(ts, c, "duck_settling $r") }
        if (buried) return vote(ts, 0.0, "ducked_buried st_lufs=${"%.1f".format(lastShortTerm)}")
        if (!warm) return vote(ts, 0.0, "warming observed_s=${"%.1f".format(observedS)}")
        val baseline = baselineLufs!!
        if (lastShortTerm <= SILENCE_GATE_LUFS) return vote(ts, 0.0, "gated st_lufs=${"%.1f".format(lastShortTerm)}")
        val delta = lastShortTerm - baseline
        var confidence = (delta / (cfg.deltaLufs * FULL_CONF_FACTOR)).coerceIn(0.0, 1.0)
        var crest = ""
        val baseCrest = baselineCrestDb
        if (cfg.crestDropDb > 0.0 && baseCrest != null) {
            val drop = baseCrest - crestDb
            val crestConf = (drop / (cfg.crestDropDb * FULL_CONF_FACTOR)).coerceIn(0.0, 1.0)
            confidence = min(1.0, confidence + CREST_SHARE * crestConf)
            crest = " crest_db=${"%.1f".format(crestDb)} crest_drop_db=${"%.1f".format(drop)}"
        }
        val duck = if (ducked) " duck_offset_db=${"%.1f".format(duckOffsetDb)}" else ""
        return vote(ts, confidence, "loudness delta_lufs=${"%.2f".format(delta)} st_lufs=${"%.1f".format(lastShortTerm)} baseline_lufs=${"%.1f".format(baseline)}$crest$duck")
    }

    companion object {
        const val HP_HZ = 38.0
        const val SHELF_HZ = 1500.0
        const val SHELF_GAIN = 1.505
        const val FULL_CONF_FACTOR = 1.4
        const val SILENCE_GATE_LUFS = -55.0
        /** Duck compensation: wait one window plus this before measuring the drop. */
        const val DUCK_SETTLE_MARGIN_S = 1.0
        const val MAX_DUCK_OFFSET_DB = 30.0
        /** Buried: the ducked level sits within this of the silence gate… */
        const val DUCK_HEADROOM_DB = 6.0
        /** …or the settle window is flat noise: the silence detector's own test for "room, not broadcast". */
        const val BURIED_FLATNESS = 0.2
        /** A full crest-factor drop is worth this much of a vote (ADR 0021). */
        const val CREST_SHARE = 0.5

        /** Peak-to-RMS ratio of one block in dB; 0 for silence. */
        fun blockCrestDb(samples: FloatArray): Double {
            if (samples.isEmpty()) return 0.0
            var sq = 0.0; var peak = 0.0
            for (v in samples) { val d = v.toDouble(); sq += d * d; if (abs(d) > peak) peak = abs(d) }
            val rms = sqrt(sq / samples.size)
            return if (rms <= 0.0) 0.0 else 20.0 * log10(peak / rms)
        }
    }
}
