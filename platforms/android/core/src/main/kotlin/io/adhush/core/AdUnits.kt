package io.adhush.core

import kotlin.math.abs

/** Ported from config.AdUnitsConfig. */
data class AdUnitsConfig(
    val toleranceS: Double = 1.2,
    val holdS: Double = 35.0,
    val mergeS: Double = 2.0,
    val silence: MicSilenceConfig = MicSilenceConfig(minRunMs = 250),
)

/**
 * Ad-unit length quantisation (ADR 0023, after comskip and MythTV): spots
 * are sold in 15/30/45/60/90/120-second units, so inside a break the quiet
 * gaps between them fall on a fifteen-second grid. The phone hears the gaps
 * with the same rolling-floor silence test as method 1; this detector keeps
 * the gap times and votes while the recent gaps fit the grid — a half vote
 * for one fitted gap, a full one for two in a row, held for [holdS] after
 * the last gap. Default weight: it never ducks alone but it holds a duck
 * through the sag in the middle of a pod. Inert without a fit.
 */
class AdUnitsDetector(private val cfg: AdUnitsConfig = AdUnitsConfig()) : Detector {
    override val name = "ad_units"

    private val silence = MicSilenceDetector(cfg.silence)
    private val separators = ArrayDeque<Double>()
    private var inRun = false
    private var runStart = 0.0
    private var fits: List<Double> = emptyList()
    private var lastSep: Double? = null

    override fun warmup() { silence.warmup(); separators.clear(); inRun = false; fits = emptyList(); lastSep = null }

    override fun audioDucked(ts: Double, ducked: Boolean) { silence.audioDucked(ts, ducked); inRun = false }

    override fun userSaysProgramme(ts: Double) { fits = emptyList(); separators.clear() }

    override fun observeAudio(block: AudioBlock) {
        silence.observeAudio(block)
        val running = silence.voting && silence.vote(block.ts).reason.startsWith("silence_run")
        // The separator is stamped when the run reaches its minimum length, as the Python original does:
        // the vote must not wait for the gap to end.
        if (running && !inRun) { inRun = true; runStart = block.ts; separator(runStart) }
        else if (!running && inRun) inRun = false
    }

    private fun separator(ts: Double) {
        if (separators.isNotEmpty() && ts - separators.last() < cfg.mergeS) return
        separators.addLast(ts)
        while (separators.size > 16) separators.removeFirst()
        lastSep = ts
        refit()
    }

    private fun refit() {
        val seps = separators.toList()
        val out = ArrayList<Double>()
        for (i in seps.size - 1 downTo 1) {
            val unit = unitFit(seps[i] - seps[i - 1], cfg.toleranceS) ?: break
            out.add(0, unit)
        }
        fits = out
    }

    /** The consecutive ad units that led up to the last separator. */
    val units: List<Double> get() = fits

    private fun active(ts: Double): Boolean { val last = lastSep ?: return false; return fits.isNotEmpty() && ts - last <= cfg.holdS }

    override val voting: Boolean get() = lastSep != null && fits.isNotEmpty()

    override fun vote(ts: Double): DetectorVote {
        if (!active(ts)) {
            if (fits.isNotEmpty() && lastSep != null) fits = emptyList()   // the hold ran out: the rhythm broke
            return vote(ts, 0.0, "no_units separators=${separators.size}")
        }
        val n = fits.size
        val units = fits.takeLast(4).joinToString(",") { "%.0f".format(it) }
        return vote(ts, if (n >= 2) 1.0 else 0.5, "unit_fit units=$units n=$n age_s=${"%.1f".format(ts - lastSep!!)}")
    }

    fun describe(): String = "${fits.size} fitted unit(s), ${separators.size} separators kept"

    companion object {
        val UNITS_S = doubleArrayOf(15.0, 30.0, 45.0, 60.0, 90.0, 120.0)
        fun unitFit(gapS: Double, toleranceS: Double): Double? = UNITS_S.firstOrNull { abs(gapS - it) <= toleranceS }
    }
}
