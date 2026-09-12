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
    fun detectScreenBox(luma: Gray): Box? {
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
        return box
    }

    /** The lit screen in a full-resolution frame, or null; downscales like CameraSource does. */
    fun findScreen(frame: Gray): Box? {
        val small = downscale(frame, DETECT_DOWNSCALE)
        val b = detectScreenBox(small) ?: return null
        val s = b.scaled(DETECT_DOWNSCALE)
        return Box(s.x0, s.y0, min(frame.w, s.x1), min(frame.h, s.y1))
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
class LogoTemplate(val roi: Roi, val w: Int, val h: Int, val edges: FloatArray, val stability: Double) {
    fun save(file: File) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { o ->
            o.write(String.format(Locale.US, "# adhush logo template v1\nroi\t%.5f\t%.5f\t%.5f\t%.5f\nsize\t%d\t%d\nstability\t%.3f\n", roi.x, roi.y, roi.w, roi.h, w, h, stability))
            o.write("edges\t" + edges.joinToString("\t") { String.format(Locale.US, "%.3f", it) } + "\n")
        }
    }
    companion object {
        fun load(file: File): LogoTemplate? {
            if (!file.isFile) return null
            var roi: Roi? = null; var w = 0; var h = 0; var stability = 0.0; var edges: FloatArray? = null
            file.forEachLine { line ->
                val p = line.split('\t')
                when (p[0]) {
                    "roi" -> roi = Roi(p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble())
                    "size" -> { w = p[1].toInt(); h = p[2].toInt() }
                    "stability" -> stability = p[1].toDouble()
                    "edges" -> edges = FloatArray(p.size - 1) { p[it + 1].toFloat() }
                }
            }
            val r = roi ?: return null; val e = edges ?: return null
            if (e.size != w * h) return null
            return LogoTemplate(r, w, h, e, stability)
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
    var frames = 0
        private set
    var screenMisses = 0
        private set

    /** Feed a full camera frame; returns false when no lit screen was found in it. */
    fun feed(frame: Gray): Boolean {
        val box = Vision.findScreen(frame) ?: run { screenMisses++; return false }
        val screen = frame.crop(box).resample(width, height)
        val e = Vision.edgeMap(screen)
        for (i in e.indices) { sumEdge[i] += e[i]; if (e[i] >= strongEdge) strong[i]++ }
        frames++
        return true
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
        val edges = FloatArray(box.w * box.h)
        var stab = 0.0; var n = 0
        for (y in 0 until box.h) for (x in 0 until box.w) {
            val i = (box.y0 + y) * width + box.x0 + x
            edges[y * box.w + x] = sumEdge[i] / frames
            if (stable[i]) { stab += strong[i].toDouble() / frames; n++ }
        }
        val roi = Roi(box.x0.toDouble() / width, box.y0.toDouble() / height, box.w.toDouble() / width, box.h.toDouble() / height)
        return LogoTemplate(roi, box.w, box.h, edges, if (n == 0) 0.0 else stab / n)
    }
}

data class LogoAbsenceConfig(
    /** Seconds without the logo for the vote to reach 1.0 (the Pi's 45 frames at 30 fps). */
    val absenceS: Double = 1.5,
    val presentThreshold: Double = 0.4,
    val scoreAlpha: Double = 0.3,
    val redetectS: Double = 5.0,
)

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
    private var lastFrameTs: Double? = null
    var active = false
        private set
    var lastScore: Double = 1.0
        private set

    override fun warmup() { screen = null; nextDetectTs = -1.0; score = 1.0; absentSince = null; active = false; lastFrameTs = null }
    override fun observeAudio(block: AudioBlock) {}

    /** Positive programme proof: the logo is visibly there right now. */
    val programPresent: Boolean get() = active && absentSince == null && score >= cfg.presentThreshold

    override fun observeFrame(frame: Gray, ts: Double) {
        if (ts >= nextDetectTs || screen == null) {
            screen = Vision.findScreen(frame) ?: screen.takeIf { ts < nextDetectTs }
            nextDetectTs = ts + cfg.redetectS
        }
        val box = screen ?: run { active = false; return }
        active = true; lastFrameTs = ts
        val norm = frame.crop(box).resample(320, 180)
        val roi = norm.crop(template.roi.on(norm.w, norm.h)).resample(template.w, template.h)
        val raw = Vision.correlation(Vision.edgeMap(roi), template.edges)
        score += cfg.scoreAlpha * (raw - score)
        lastScore = score
        if (score < cfg.presentThreshold) { if (absentSince == null) absentSince = ts } else absentSince = null
    }

    override fun vote(ts: Double): DetectorVote {
        if (!active) return vote(ts, 0.0, "no_screen")
        val since = absentSince ?: return vote(ts, 0.0, "logo_present score=${"%.2f".format(Locale.US, score)}")
        val confidence = min(1.0, (ts - since) / cfg.absenceS)
        return vote(ts, confidence, "logo_absent s=${"%.1f".format(Locale.US, ts - since)} score=${"%.2f".format(Locale.US, score)}")
    }

}
