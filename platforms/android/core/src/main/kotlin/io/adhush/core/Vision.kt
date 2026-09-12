package io.adhush.core

import java.io.File
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A grayscale frame: row-major luma 0..255. */
class Gray(val w: Int, val h: Int, val px: FloatArray) {
    init { require(px.size == w * h) { "pixel count ${px.size} != $w x $h" } }
    operator fun get(x: Int, y: Int): Float = px[y * w + x]

    fun crop(b: Box): Gray {
        val nw = b.x1 - b.x0; val nh = b.y1 - b.y0
        val out = FloatArray(nw * nh)
        for (y in 0 until nh) System.arraycopy(px, (b.y0 + y) * w + b.x0, out, y * nw, nw)
        return Gray(nw, nh, out)
    }

    /** Rotate clockwise by 0/90/180/270 degrees, the way a camera's rotationDegrees asks. */
    fun rotate(degrees: Int): Gray = when (((degrees % 360) + 360) % 360) {
        0 -> this
        90 -> Gray(h, w, FloatArray(w * h).also { o -> for (y in 0 until h) for (x in 0 until w) o[x * h + (h - 1 - y)] = px[y * w + x] })
        180 -> Gray(w, h, FloatArray(w * h).also { o -> for (i in px.indices) o[px.size - 1 - i] = px[i] })
        270 -> Gray(h, w, FloatArray(w * h).also { o -> for (y in 0 until h) for (x in 0 until w) o[(w - 1 - x) * h + y] = px[y * w + x] })
        else -> throw IllegalArgumentException("rotation must be a multiple of 90: $degrees")
    }

    /** Nearest-neighbour resample, centre-sampled like logo_absence._resize_nearest. */
    fun resample(nw: Int, nh: Int): Gray {
        val out = FloatArray(nw * nh)
        for (y in 0 until nh) {
            val sy = min(h - 1, ((y + 0.5) * h / nh).toInt())
            for (x in 0 until nw) out[y * nw + x] = px[sy * w + min(w - 1, ((x + 0.5) * w / nw).toInt())]
        }
        return Gray(nw, nh, out)
    }
}

data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val w get() = x1 - x0
    val h get() = y1 - y0
    fun scaled(f: Int) = Box(x0 * f, y0 * f, x1 * f, y1 * f)
}

/** Normalised region of the *screen* (0..1), like RoiConfig. */
data class Roi(val x: Double, val y: Double, val w: Double, val h: Double) {
    fun on(width: Int, height: Int): Box = Box((x * width).toInt(), (y * height).toInt(), min(width, ((x + w) * width).toInt()), min(height, ((y + h) * height).toInt()))
    val corner: String get() = (if (y + h / 2 < 0.5) "top" else "bottom") + "-" + (if (x + w / 2 < 0.5) "left" else "right")
}

/** Ports of capture/camera.detect_screen_bbox and util/imageops for the phone camera. */
object Vision {
    const val GLARE_LUMA = 250f
    const val MIN_AREA_FRACTION = 0.08
    const val DETECT_DOWNSCALE = 8

    fun downscale(g: Gray, f: Int): Gray {
        val nw = g.w / f; val nh = g.h / f
        val out = FloatArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            var s = 0f
            for (dy in 0 until f) for (dx in 0 until f) s += g.px[(y * f + dy) * g.w + x * f + dx]
            out[y * nw + x] = s / (f * f)
        }
        return Gray(nw, nh, out)
    }

    /** numpy's default (linear-interpolated) percentile. */
    fun percentile(values: FloatArray, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val s = values.copyOf(); s.sort()
        val pos = p / 100.0 * (s.size - 1)
        val lo = pos.toInt(); val hi = min(s.size - 1, lo + 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    /** Bright-rectangle detector on a downscaled luma frame; threshold halfway between surround and screen. */
    fun detectScreenBox(luma: Gray): Box? = detectScreenBoxWithThreshold(luma)?.first

    fun detectScreenBoxWithThreshold(luma: Gray): Pair<Box, Double>? {
        val values = FloatArray(luma.px.size) { if (luma.px[it] >= GLARE_LUMA) 0f else luma.px[it] }
        val low = percentile(values, 20.0); val high = percentile(values, 95.0)
        if (high - low < 20.0) return null
        val threshold = (low + high) / 2
        val rowLit = IntArray(luma.h); val colLit = IntArray(luma.w)
        for (y in 0 until luma.h) for (x in 0 until luma.w) if (values[y * luma.w + x] > threshold) { rowLit[y]++; colLit[x]++ }
        val rows = (0 until luma.h).filter { rowLit[it] > 0.35 * luma.w }
        val cols = (0 until luma.w).filter { colLit[it] > 0.35 * luma.h }
        if (rows.isEmpty() || cols.isEmpty()) return null
        val box = Box(cols.first(), rows.first(), cols.last() + 1, rows.last() + 1)
        if (box.w * box.h < MIN_AREA_FRACTION * luma.px.size) return null
        return Pair(box, threshold)
    }

    /**
     * The lit screen in a full-resolution frame, or null. Found coarsely on a
     * downscaled copy like CameraSource does, then each edge is refined at full
     * resolution: the coarse box is only good to DETECT_DOWNSCALE pixels, which
     * is a large fraction of a small logo when the phone is in a hand.
     */
    fun findScreen(frame: Gray): Box? {
        val small = downscale(frame, DETECT_DOWNSCALE)
        val (b, threshold) = detectScreenBoxWithThreshold(small) ?: return null
        val s = b.scaled(DETECT_DOWNSCALE)
        val coarse = Box(s.x0, s.y0, min(frame.w, s.x1), min(frame.h, s.y1))
        return refineScreenBox(frame, coarse, threshold)
    }

    private fun refineScreenBox(frame: Gray, coarse: Box, threshold: Double): Box {
        val r = DETECT_DOWNSCALE
        fun colLit(x: Int): Boolean {
            var n = 0; for (y in coarse.y0 until coarse.y1) if (frame[x, y] > threshold && frame[x, y] < GLARE_LUMA) n++
            return n > 0.35 * coarse.h
        }
        fun rowLit(y: Int): Boolean {
            var n = 0; for (x in coarse.x0 until coarse.x1) if (frame[x, y] > threshold && frame[x, y] < GLARE_LUMA) n++
            return n > 0.35 * coarse.w
        }
        var x0 = coarse.x0; for (x in max(0, coarse.x0 - r)..min(frame.w - 1, coarse.x0 + r)) if (colLit(x)) { x0 = x; break }
        var x1 = coarse.x1; for (x in min(frame.w - 1, coarse.x1 + r) downTo max(0, coarse.x1 - r - 1)) if (colLit(x)) { x1 = x + 1; break }
        var y0 = coarse.y0; for (y in max(0, coarse.y0 - r)..min(frame.h - 1, coarse.y0 + r)) if (rowLit(y)) { y0 = y; break }
        var y1 = coarse.y1; for (y in min(frame.h - 1, coarse.y1 + r) downTo max(0, coarse.y1 - r - 1)) if (rowLit(y)) { y1 = y + 1; break }
        return if (x1 > x0 && y1 > y0) Box(x0, y0, x1, y1) else coarse
    }

    /** np.gradient magnitude: central differences inside, one-sided at the border. */
    fun edgeMap(g: Gray): FloatArray {
        val out = FloatArray(g.w * g.h)
        for (y in 0 until g.h) for (x in 0 until g.w) {
            val gx = when { g.w == 1 -> 0f; x == 0 -> g[1, y] - g[0, y]; x == g.w - 1 -> g[x, y] - g[x - 1, y]; else -> (g[x + 1, y] - g[x - 1, y]) / 2 }
            val gy = when { g.h == 1 -> 0f; y == 0 -> g[x, 1] - g[x, 0]; y == g.h - 1 -> g[x, y] - g[x, y - 1]; else -> (g[x, y + 1] - g[x, y - 1]) / 2 }
            out[y * g.w + x] = hypot(gx, gy)
        }
        return out
    }

    /** Pearson correlation of two equal-length maps (centred cosine), 0 when either is flat. */
    fun correlation(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size)
        var ma = 0.0; var mb = 0.0
        for (i in a.indices) { ma += a[i]; mb += b[i] }
        ma /= a.size; mb /= b.size
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { val x = a[i] - ma; val y = b[i] - mb; dot += x * y; na += x * x; nb += y * y }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0.0) 0.0 else dot / denom
    }
}

/** The edge template of the network bug, in a region of the normalised screen. */
class LogoTemplate(
    val roi: Roi, val w: Int, val h: Int, val edges: FloatArray, val stability: Double,
    /** Mean edge magnitude over the whole normalised screen at calibration: the blur guard's reference. 0 = unknown. */
    val screenEdgeMean: Double = 0.0,
) {
    fun save(file: File) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { o ->
            o.write(String.format(Locale.US, "# adhush logo template v1\nroi\t%.5f\t%.5f\t%.5f\t%.5f\nsize\t%d\t%d\nstability\t%.3f\nscreen_edge_mean\t%.3f\n", roi.x, roi.y, roi.w, roi.h, w, h, stability, screenEdgeMean))
            o.write("edges\t" + edges.joinToString("\t") { String.format(Locale.US, "%.3f", it) } + "\n")
        }
    }
    companion object {
        fun load(file: File): LogoTemplate? {
            if (!file.isFile) return null
            var roi: Roi? = null; var w = 0; var h = 0; var stability = 0.0; var edges: FloatArray? = null; var screenEdge = 0.0
            file.forEachLine { line ->
                val p = line.split('\t')
                when (p[0]) {
                    "roi" -> roi = Roi(p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble())
                    "size" -> { w = p[1].toInt(); h = p[2].toInt() }
                    "stability" -> stability = p[1].toDouble()
                    "screen_edge_mean" -> screenEdge = p[1].toDouble()
                    "edges" -> edges = FloatArray(p.size - 1) { p[it + 1].toFloat() }
                }
            }
            val r = roi ?: return null; val e = edges ?: return null
            if (e.size != w * h) return null
            return LogoTemplate(r, w, h, e, stability, screenEdge)
        }
    }
}

/**
 * One-button calibration. Watches the screen during a programme and keeps,
 * per pixel of the normalised screen, how often a strong edge sits there:
 * picture content moves, the bug's edges do not. The corner with the most
 * persistently-edged pixels is the logo's; a tight box around them is the
 * ROI, and the mean edge map inside it is the template — exactly what
 * `adhush calibrate` builds, without anyone drawing a box.
 */
class LogoFinder(
    val width: Int = 320, val height: Int = 180,
    private val strongEdge: Float = 12f,
    private val minStability: Double = 0.7,
    private val minPixels: Int = 30,
) {
    private val sumEdge = FloatArray(width * height)
    private val strong = IntArray(width * height)
    private var screenEdgeSum = 0.0
    var frames = 0
        private set
    var screenMisses = 0
        private set
    /** Where the screen was in the last frame fed, in frame pixels; null if it was not found. */
    var lastScreen: Box? = null
        private set

    /** Feed a full camera frame; returns false when no lit screen was found in it. */
    fun feed(frame: Gray): Boolean {
        val box = Vision.findScreen(frame) ?: run { screenMisses++; lastScreen = null; return false }
        lastScreen = box
        val screen = frame.crop(box).resample(width, height)
        val e = Vision.edgeMap(screen)
        var total = 0.0
        for (i in e.indices) { sumEdge[i] += e[i]; total += e[i]; if (e[i] >= strongEdge) strong[i]++ }
        screenEdgeSum += total / e.size
        frames++
        return true
    }

    /** Per pixel of the normalised screen: the fraction of frames with a strong edge there (0..1). For the setup preview. */
    fun stabilityMap(): FloatArray = FloatArray(width * height) { if (frames == 0) 0f else strong[it].toFloat() / frames }

    /**
     * A template for a box the user (or [result]) chose, in normalised screen
     * coordinates: the mean edge map inside it, its stability, and the screen's
     * overall edge level for the blur guard. Null before ten frames.
     */
    fun templateFor(roi: Roi): LogoTemplate? {
        if (frames < 10) return null
        val box = roi.on(width, height)
        if (box.w < 4 || box.h < 4) return null
        val edges = FloatArray(box.w * box.h)
        var stab = 0.0; var n = 0
        for (y in 0 until box.h) for (x in 0 until box.w) {
            val i = (box.y0 + y) * width + box.x0 + x
            edges[y * box.w + x] = sumEdge[i] / frames
            val st = strong[i].toDouble() / frames
            if (st >= minStability) { stab += st; n++ }
        }
        return LogoTemplate(roi, box.w, box.h, edges, if (n == 0) 0.0 else stab / n, screenEdgeSum / frames)
    }

    /** The template, or null when nothing persistent enough was seen. */
    fun result(): LogoTemplate? {
        if (frames < 10) return null
        val stable = BooleanArray(width * height) { strong[it].toDouble() / frames >= minStability }
        // Candidate corners: 30% wide, 25% tall; the bug lives in one of them.
        val cw = (width * 0.30).toInt(); val ch = (height * 0.25).toInt()
        val corners = listOf(Box(0, 0, cw, ch), Box(width - cw, 0, width, ch), Box(0, height - ch, cw, height), Box(width - cw, height - ch, width, height))
        var best: Box? = null; var bestCount = 0
        for (c in corners) {
            var n = 0
            for (y in c.y0 until c.y1) for (x in c.x0 until c.x1) if (stable[y * width + x]) n++
            if (n > bestCount) { bestCount = n; best = c }
        }
        val corner = best ?: return null
        if (bestCount < minPixels) return null
        var x0 = width; var y0 = height; var x1 = 0; var y1 = 0
        for (y in corner.y0 until corner.y1) for (x in corner.x0 until corner.x1) if (stable[y * width + x]) { x0 = min(x0, x); y0 = min(y0, y); x1 = max(x1, x + 1); y1 = max(y1, y + 1) }
        val pad = 3
        val box = Box(max(0, x0 - pad), max(0, y0 - pad), min(width, x1 + pad), min(height, y1 + pad))
        return templateFor(Roi(box.x0.toDouble() / width, box.y0.toDouble() / height, box.w.toDouble() / width, box.h.toDouble() / height))
    }
}

data class LogoAbsenceConfig(
    /** Seconds without the logo for the vote to reach 1.0 (the Pi's 45 frames at 30 fps; longer for a hand-held phone). */
    val absenceS: Double = 1.5,
    val presentThreshold: Double = 0.4,
    val scoreAlpha: Double = 0.3,
    /** How often to re-find the screen. 0 = every frame, which a hand-held phone needs. */
    val redetectS: Double = 5.0,
    /**
     * Blur guard: when the whole screen's edge level drops below this fraction of
     * what calibration saw — a moving hand smearing the picture — the frame is
     * inert rather than "absent". 0 disables it.
     */
    val blurRatio: Double = 0.4,
)

/** Hand-held defaults: re-find the screen every frame, be slower to call the logo gone, guard against blur. */
val HANDHELD_LOGO_CONFIG = LogoAbsenceConfig(absenceS = 2.5, redetectS = 0.0, blurRatio = 0.4)

/**
 * Port of detect/logo_absence.py for camera frames. Per frame: find the
 * screen (re-checked every few seconds), cut the ROI of the normalised
 * screen, correlate its edge map with the template, smooth, and count time
 * below the threshold. Presence drops the vote to 0 at once; absence ramps it
 * over [LogoAbsenceConfig.absenceS]. With no screen in view it is inert:
 * `active` is false and it casts no vote at all.
 */
class LogoAbsenceDetector(private val cfg: LogoAbsenceConfig = LogoAbsenceConfig(), private val template: LogoTemplate) : Detector {
    override val name = "logo_absence"
    private var screen: Box? = null
    private var nextDetectTs = -1.0
    private var score = 1.0
    private var absentSince: Double? = null
    var active = false
        private set
    var lastScore: Double = 1.0
        private set
    /** Why the last frame was inert, if it was: "no_screen" or "blurry". For the setup preview. */
    var inertReason: String = "no_screen"
        private set
    /** The screen and the logo box in the last frame's pixel coordinates, for drawing. */
    var lastScreen: Box? = null
        private set
    var lastRoiInFrame: Box? = null
        private set

    override fun warmup() { screen = null; nextDetectTs = -1.0; score = 1.0; absentSince = null; active = false; lastScreen = null; lastRoiInFrame = null }
    override fun observeAudio(block: AudioBlock) {}

    /** Positive programme proof: the logo is visibly there right now. */
    val programPresent: Boolean get() = active && absentSince == null && score >= cfg.presentThreshold

    override fun observeFrame(frame: Gray, ts: Double) {
        if (cfg.redetectS <= 0.0 || ts >= nextDetectTs || screen == null) {
            screen = Vision.findScreen(frame) ?: screen.takeIf { cfg.redetectS > 0.0 && ts < nextDetectTs }
            nextDetectTs = ts + cfg.redetectS
        }
        val box = screen ?: run { active = false; inertReason = "no_screen"; lastScreen = null; lastRoiInFrame = null; return }
        lastScreen = box
        val norm = frame.crop(box).resample(320, 180)
        val roiBox = template.roi.on(norm.w, norm.h)
        lastRoiInFrame = Box(box.x0 + roiBox.x0 * box.w / norm.w, box.y0 + roiBox.y0 * box.h / norm.h, box.x0 + roiBox.x1 * box.w / norm.w, box.y0 + roiBox.y1 * box.h / norm.h)
        if (cfg.blurRatio > 0.0 && template.screenEdgeMean > 0.0) {
            val e = Vision.edgeMap(norm); var total = 0.0; for (v in e) total += v
            if (total / e.size < cfg.blurRatio * template.screenEdgeMean) { active = false; inertReason = "blurry"; return }
        }
        active = true
        val roi = norm.crop(roiBox).resample(template.w, template.h)
        val raw = Vision.correlation(Vision.edgeMap(roi), template.edges)
        score += cfg.scoreAlpha * (raw - score)
        lastScore = score
        if (score < cfg.presentThreshold) { if (absentSince == null) absentSince = ts } else absentSince = null
    }

    override fun vote(ts: Double): DetectorVote {
        if (!active) return vote(ts, 0.0, inertReason)
        val since = absentSince ?: return vote(ts, 0.0, "logo_present score=${"%.2f".format(Locale.US, score)}")
        val confidence = min(1.0, (ts - since) / cfg.absenceS)
        return vote(ts, confidence, "logo_absent s=${"%.1f".format(Locale.US, ts - since)} score=${"%.2f".format(Locale.US, score)}")
    }

}
