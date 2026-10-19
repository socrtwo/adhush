package io.adhush.core

import kotlin.math.abs
import kotlin.math.max

/** Ported from config.StingerConfig; the values encode validated tuning. */
data class StingerConfig(
    val blockS: Double = 0.1,
    /** A burst is noisy, not tonal: 0 is a pure tone, 1 white noise. */
    val minFlatness: Double = 0.25,
    /** Burst level over the two-second pre-burst median. */
    val burstAboveDb: Double = 6.0,
    val maxBurstS: Double = 1.5,
    /** The post-burst median must move this far from the pre-burst bed ... */
    val levelStepDb: Double = 3.0,
    /** ... unless the burst itself was this far above it. */
    val loudBurstDb: Double = 10.0,
    val preS: Double = 2.0,
    val postS: Double = 1.0,
    /** The vote decays to nothing over this long. */
    val holdS: Double = 2.5,
    /** Level judgements go quiet this long after our own volume change (ADR 0016). */
    val duckGuardS: Double = 1.5,
)

/**
 * Segment stingers (ADR 0027): the whoosh, hit or swell a channel drops on
 * the cut into a segment or a break, followed by a change of level. A
 * line-for-line port of detect/stinger.py: a short burst (up to
 * [StingerConfig.maxBurstS]) that is noisy rather than tonal and
 * [StingerConfig.burstAboveDb] louder than the two seconds before it, after
 * which the level has moved by [StingerConfig.levelStepDb] (or the burst
 * itself was [StingerConfig.loudBurstDb] above the bed). Applause is too
 * long; a plain level step is loudness's business. The vote is 1.0 when the
 * stinger is confirmed and decays over [StingerConfig.holdS]: default
 * weight, it tips a balance, never ducks alone. Inert otherwise.
 */
class StingerDetector(private val cfg: StingerConfig = StingerConfig()) : Detector {
    override val name = "stinger"

    private val pending = ArrayList<FloatArray>()
    private var pendingN = 0
    private var blockStart: Double? = null
    private val levels = ArrayDeque<Double>()
    private var burstStart: Double? = null
    private var burstPre = 0.0
    private var burstPeak = -120.0
    private var burstBlocks = 0
    private val post = ArrayList<Double>()
    private var hitTs: Double? = null
    private var hitBurstDb = 0.0
    private var hitStepDb = 0.0
    private var guardUntil = -1.0
    private var lastTs = 0.0
    /** Stingers heard since warm-up. */
    var count = 0
        private set

    private val preBlocks: Int get() = max(2, (cfg.preS / cfg.blockS).toInt())
    private val postBlocks: Int get() = max(1, (cfg.postS / cfg.blockS).toInt())
    private val maxBurstBlocks: Int get() = max(1, (cfg.maxBurstS / cfg.blockS).toInt())

    override fun warmup() {
        pending.clear(); pendingN = 0; blockStart = null; levels.clear(); resetBurst()
        hitTs = null; guardUntil = -1.0; lastTs = 0.0; count = 0
    }

    override fun audioDucked(ts: Double, ducked: Boolean) { guardUntil = ts + cfg.duckGuardS; resetBurst(); levels.clear() }

    override fun userSaysProgramme(ts: Double) { hitTs = null }

    private fun resetBurst() { burstStart = null; burstBlocks = 0; post.clear() }

    override fun observeAudio(block: AudioBlock) {
        var start = blockStart ?: block.ts
        pending.add(block.samples); pendingN += block.samples.size
        val need = max(1, Math.round(cfg.blockS * block.sampleRate).toInt())
        while (pendingN >= need) {
            val all = FloatArray(pendingN); var o = 0
            for (p in pending) { System.arraycopy(p, 0, all, o, p.size); o += p.size }
            val head = all.copyOfRange(0, need); val rest = all.copyOfRange(need, all.size)
            pending.clear(); if (rest.isNotEmpty()) pending.add(rest); pendingN = rest.size
            onBlock(start, Dsp.blockDbfs(head), Dsp.spectralFlatness(head))
            start += need.toDouble() / block.sampleRate
        }
        blockStart = start
    }

    private fun median(xs: Collection<Double>): Double {
        val s = xs.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    private fun onBlock(ts: Double, dbfs: Double, flatness: Double) {
        lastTs = ts
        if (ts < guardUntil) return
        if (post.isNotEmpty() && burstStart != null) {
            // After the burst: hear the new bed, then judge.
            post.add(dbfs)
            if (post.size >= postBlocks) {
                val step = median(post) - burstPre
                val above = burstPeak - burstPre
                if (abs(step) >= cfg.levelStepDb || above >= cfg.loudBurstDb) { hitTs = ts; hitBurstDb = above; hitStepDb = step; count++ }
                for (level in post) pushLevel(level)
                resetBurst()
            }
            return
        }
        if (burstStart != null) {
            if (flatness >= cfg.minFlatness && dbfs >= burstPre + cfg.burstAboveDb) {
                burstBlocks++; burstPeak = max(burstPeak, dbfs)
                if (burstBlocks > maxBurstBlocks) { resetBurst(); pushLevel(dbfs) }   // too long for a stinger: loud, noisy programme
                return
            }
            post.add(dbfs)   // the burst just ended; the first post block
            return
        }
        if (levels.size >= preBlocks) {
            val pre = median(levels)
            if (flatness >= cfg.minFlatness && dbfs >= pre + cfg.burstAboveDb) { burstStart = ts; burstPre = pre; burstPeak = dbfs; burstBlocks = 1; return }
        }
        pushLevel(dbfs)
    }

    private fun pushLevel(dbfs: Double) { levels.addLast(dbfs); while (levels.size > preBlocks) levels.removeFirst() }

    override val voting: Boolean get() = hitTs?.let { lastTs - it < cfg.holdS } ?: false

    override fun vote(ts: Double): DetectorVote {
        val hit = hitTs
        if (hit != null && ts - hit < cfg.holdS) {
            val age = max(0.0, ts - hit)
            return vote(ts, max(0.0, 1.0 - age / cfg.holdS), "stinger burst_db=+${"%.1f".format(hitBurstDb)} step_db=${"%+.1f".format(hitStepDb)} age_s=${"%.1f".format(age)}")
        }
        return vote(ts, 0.0, "stinger_quiet heard=$count")
    }

    fun describe(): String = if (count > 0) "$count stinger${if (count == 1) "" else "s"} heard" else "listening for a stinger"
}
