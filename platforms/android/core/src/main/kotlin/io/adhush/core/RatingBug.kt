package io.adhush.core

import kotlin.math.max

/** Ported from config.RatingBugConfig; the ROI is relative to the found screen. */
data class RatingBugConfig(
    val roi: Roi = Roi(0.0, 0.02, 0.22, 0.16),
    val minFill: Double = 0.45,
    val minAspect: Double = 1.2,
    val maxAspect: Double = 4.0,
    val minShare: Double = 0.04,
    val maxShare: Double = 0.6,
    val appearAfterS: Double = 5.0,
    val minPresentS: Double = 1.0,
    val holdS: Double = 20.0,
)

/**
 * The parental-rating box as programme evidence (ADR 0023, after MythTV).
 * US networks flash "TV-14" in the upper left for ten seconds after every
 * break; commercials never carry one. Through the camera the box is a
 * bright, filled rectangle appearing in the corner of the found screen
 * where nothing bright was a moment before. Votes 0 with weight while it
 * is up and for [holdS] after; inert otherwise; never votes for an ad.
 * The screen box comes from the logo detector, which finds it every frame.
 */
class RatingBugDetector(
    private val screen: () -> Box?,
    private val cfg: RatingBugConfig = RatingBugConfig(),
) : Detector {
    override val name = "rating_bug"

    private var quietSince: Double? = null
    private var boxSince: Double? = null
    private var presentUntil = Double.NEGATIVE_INFINITY
    private var lastTs = 0.0
    var sightings = 0
        private set
    private var last = Triple(0.0, 0.0, 0.0)

    override fun warmup() { quietSince = null; boxSince = null; presentUntil = Double.NEGATIVE_INFINITY; sightings = 0 }

    override fun observeAudio(block: AudioBlock) {}

    /** (fill, aspect, share) of the bright bounding box inside the ROI. */
    fun boxScore(frame: Gray, roi: Box): Triple<Double, Double, Double> {
        var peak = 0f
        for (y in roi.y0 until roi.y1) for (x in roi.x0 until roi.x1) peak = max(peak, frame[x, y])
        val bright = max(140f, 0.75f * peak)
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1; var count = 0
        for (y in roi.y0 until roi.y1) for (x in roi.x0 until roi.x1) if (frame[x, y] >= bright) {
            count++; if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        if (count < 8) return Triple(0.0, 0.0, 0.0)
        val w = (maxX - minX + 1).toDouble(); val h = (maxY - minY + 1).toDouble()
        return Triple(count / (w * h), w / h, (w * h) / (roi.w.toDouble() * roi.h))
    }

    override fun observeFrame(frame: Gray, ts: Double) {
        lastTs = ts
        val s = screen() ?: run { boxSince = null; return }
        val inScreen = cfg.roi.on(s.w, s.h)
        val roi = Box(s.x0 + inScreen.x0, s.y0 + inScreen.y0, s.x0 + inScreen.x1, s.y0 + inScreen.y1)
        if (roi.w < 4 || roi.h < 4 || roi.x1 > frame.w || roi.y1 > frame.h) return
        val (fill, aspect, share) = boxScore(frame, roi)
        last = Triple(fill, aspect, share)
        val boxed = fill >= cfg.minFill && aspect in cfg.minAspect..cfg.maxAspect && share in cfg.minShare..cfg.maxShare
        if (!boxed) { boxSince = null; if (quietSince == null) quietSince = ts; return }
        if (boxSince == null) {
            val q = quietSince
            if (q == null || ts - q < cfg.appearAfterS) return   // a permanent bright corner is scenery
            boxSince = ts; quietSince = null
        }
        if (ts - boxSince!! >= cfg.minPresentS) {
            if (presentUntil < ts) sightings++
            presentUntil = ts + cfg.holdS
        }
    }

    override val programPresent: Boolean get() = lastTs < presentUntil
    override val voting: Boolean get() = programPresent

    override fun vote(ts: Double): DetectorVote {
        val (fill, aspect, share) = last
        if (ts < presentUntil) return vote(ts, 0.0, "rating_box fill=${"%.2f".format(fill)} aspect=${"%.1f".format(aspect)} share=${"%.2f".format(share)} left_s=${"%.1f".format(presentUntil - ts)}")
        return vote(ts, 0.0, "no_rating_box fill=${"%.2f".format(fill)}")
    }

    fun describe(): String = "$sightings rating box(es) seen" + if (programPresent) " — programme" else ""
}
