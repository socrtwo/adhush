package io.adhush.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import io.adhush.core.Box
import io.adhush.core.Gray
import io.adhush.core.HANDHELD_LOGO_CONFIG
import io.adhush.core.LogoAbsenceDetector
import io.adhush.core.LogoFinder
import io.adhush.core.LogoTemplate
import io.adhush.core.Roi
import io.adhush.core.Vision
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * See what the camera sees, in colour, and choose the bug by eye. The live
 * picture shows the found screen (green, red when it runs off the edge) and
 * the logo box (yellow); a magnified colour crop of the box sits below it
 * with a live match score. Pinch the picture, or move the slider, to zoom the
 * camera in until the bug is big but the whole TV still fits. "Watch 45 s"
 * runs the automatic finder; dragging a box on the picture overrides its
 * choice; "Save & start" writes the template and the zoom and starts the
 * service. AdHush is stopped while this screen is open, because two things
 * cannot hold the camera at once.
 */
class CameraSetupActivity : AppCompatActivity() {
    private lateinit var picture: LogoOverlayView
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var crop: ImageView
    private lateinit var saveBtn: MaterialButton
    private lateinit var zoom: Slider
    private var camera: CameraSource? = null
    private var finder = LogoFinder()
    @Volatile private var watching = false
    private var watchStartedAt = 0L
    @Volatile private var roi: Roi? = null
    @Volatile private var template: LogoTemplate? = null
    @Volatile private var detector: LogoAbsenceDetector? = null
    private var ts = 0.0
    private var lastPartial = false
    /** Watch the lower-third band (a news ticker) instead of the corner bug. */
    private var ticker = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.startForegroundService(this, Intent(this, AdHushService::class.java).setAction(AdHushService.ACTION_STOP))
        setContentView(R.layout.activity_camera_setup)
        status = findViewById(R.id.setupStatus)
        hint = findViewById(R.id.setupHint)
        picture = findViewById(R.id.setupPicture)
        crop = findViewById(R.id.setupCrop)
        zoom = findViewById(R.id.setupZoom)
        saveBtn = findViewById(R.id.setupSave)
        val watch = findViewById<MaterialButton>(R.id.setupWatch)
        val cancel = findViewById<MaterialButton>(R.id.setupCancel)
        findViewById<View>(R.id.setupHelp).setOnClickListener { Help.show(this, if (ticker) Help.TICKER else Help.BUG) }
        val settings = Settings(this)
        ticker = settings.cameraTarget == "ticker"
        if (ticker) findViewById<TextView>(R.id.setupTitle).text = "Camera setup — find the news ticker"
        zoom.value = settings.cameraZoom.coerceIn(1f, 4f)
        zoom.addOnChangeListener { _, v, _ -> camera?.setZoom(v); zoomLabel(v) }
        zoomLabel(zoom.value)
        status.text = "starting the camera …"

        watch.setOnClickListener {
            finder = LogoFinder(); watching = true; watchStartedAt = System.currentTimeMillis()
            status.text = "watching — keep a show on, whole TV in view, hold still"
        }
        saveBtn.setOnClickListener {
            val t = template ?: return@setOnClickListener
            runCatching { t.save(File(filesDir, if (ticker) AdHushService.TICKER_FILE else AdHushService.LOGO_FILE)) }
            settings.camera = true
            settings.cameraZoom = zoom.value
            AppLog.i("camera", "logo template saved: ${t.roi.corner} stability ${"%.2f".format(Locale.US, t.stability)} zoom ${"%.1f".format(Locale.US, zoom.value)}")
            ContextCompat.startForegroundService(this, Intent(this, AdHushService::class.java))
            finish()
        }
        cancel.setOnClickListener { finish() }
        picture.onBox = { boxInFrame -> chooseBox(boxInFrame) }
        picture.onPinch = { factor ->
            val v = (zoom.value * factor).coerceIn(zoom.valueFrom, zoom.valueTo)
            zoom.value = v
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 7)
        else startCamera()
    }

    private fun zoomLabel(v: Float) { findViewById<TextView>(R.id.setupZoomLabel).text = "Zoom ${"%.1f".format(Locale.US, v)}× — make the bug big, keep the whole TV in the picture" }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startCamera() else status.text = "camera permission is needed"
    }

    private fun startCamera() {
        val cam = CameraSource(this, this, intervalMs = 250, wantColor = true) { gray, color -> onFrame(gray, color) }
        camera = cam
        cam.setZoom(zoom.value)
        cam.start { msg -> runOnUiThread { status.text = msg } }
        // The lens's real range once it is open.
        picture.postDelayed({ camera?.let { c -> val m = c.maxZoom; if (m > 1f) { zoom.valueTo = min(8f, max(2f, m)); if (zoom.value > zoom.valueTo) zoom.value = zoom.valueTo } } }, 1500)
    }

    /** The user dragged a box on the picture: turn it into a normalised screen region and re-verify live. */
    private fun chooseBox(boxInFrame: Box) {
        val screen = detector?.lastScreen ?: finder.lastScreen ?: return
        val x = ((boxInFrame.x0 - screen.x0).toDouble() / screen.w).coerceIn(0.0, 0.99)
        val y = ((boxInFrame.y0 - screen.y0).toDouble() / screen.h).coerceIn(0.0, 0.99)
        val w = (boxInFrame.w.toDouble() / screen.w).coerceIn(0.02, 1.0 - x)
        val h = (boxInFrame.h.toDouble() / screen.h).coerceIn(0.02, 1.0 - y)
        roi = Roi(x, y, w, h)
        adoptTemplate(finder.templateFor(Roi(x, y, w, h)), "your box")
    }

    private fun adoptTemplate(t: LogoTemplate?, how: String) {
        template = t
        if (t == null) { runOnUiThread { status.text = "press Watch first, then draw or accept a box"; saveBtn.isEnabled = false }; return }
        val d = if (ticker) LogoAbsenceDetector(HANDHELD_LOGO_CONFIG.copy(searchPx = 4), t, name = "ticker_absence", noun = "ticker") else LogoAbsenceDetector(HANDHELD_LOGO_CONFIG, t); d.warmup(); detector = d
        runOnUiThread { status.text = "$how: ${t.roi.corner}, stability ${"%.2f".format(Locale.US, t.stability)} — watch the score, then Save"; saveBtn.isEnabled = true }
    }

    private fun onFrame(gray: Gray, color: ColorFrame?) {
        if (watching) {
            finder.feed(gray)
            val elapsed = (System.currentTimeMillis() - watchStartedAt) / 1000
            if (elapsed >= 45) {
                watching = false
                val chosen = roi
                val auto = if (ticker) finder.bandResult() else finder.result()
                adoptTemplate(if (chosen != null) finder.templateFor(chosen) else auto?.also { roi = it.roi }, if (chosen != null) "your box" else "found")
            } else runOnUiThread { status.text = "watching $elapsed / 45 s · whole TV seen ${finder.frames} / ${finder.frames + finder.screenMisses + finder.partialFrames}" }
        }
        val det = detector
        var line = ""
        if (det != null) {
            det.observeFrame(gray, ts); ts += 0.25
            val noun = if (ticker) "ticker" else "bug"
            line = if (det.active) "$noun match ${"%.2f".format(Locale.US, det.lastScore)} — ${if (det.programPresent) "SEEN (a show is on)" else if (det.sighted) "GONE (a commercial?)" else "not seen yet"}" else "camera: ${det.describe()}"
        }
        val screen = det?.lastScreen ?: finder.lastScreen ?: Vision.findScreen(gray)
        val partial = screen != null && !Vision.screenComplete(screen, gray.w, gray.h)
        val roiBox = det?.lastRoiInFrame ?: roi?.let { r -> screen?.let { b -> Box(b.x0 + (r.x * b.w).toInt(), b.y0 + (r.y * b.h).toInt(), b.x0 + ((r.x + r.w) * b.w).toInt(), b.y0 + ((r.y + r.h) * b.h).toInt()) } }
        val cropBmp = roiBox?.let { rb ->
            val safe = Box(max(0, rb.x0), max(0, rb.y0), min(gray.w, rb.x1), min(gray.h, rb.y1))
            if (safe.w < 2 || safe.h < 2) null else if (color != null) colorCrop(color, safe) else toBitmap(gray.crop(safe))
        }
        val shown = if (color != null) Bitmap.createBitmap(color.argb, color.w, color.h, Bitmap.Config.ARGB_8888) else toBitmap(gray)
        runOnUiThread {
            picture.update(shown, gray.w, gray.h, screen, roiBox, partial)
            if (line.isNotEmpty() && !watching) status.text = line + (template?.let { "  ·  box ${it.roi.corner}" } ?: "")
            if (partial != lastPartial) { lastPartial = partial; hint.text = if (partial) "⚠ The TV runs off the edge of the picture — step back or zoom out. Nothing is muted while it looks like this." else getString(R.string.setup_hint) }
            if (screen == null && !watching) hint.text = "No lit screen found — point the camera at the TV with a show on."
            if (cropBmp != null) crop.setImageBitmap(cropBmp)
        }
    }

    override fun onDestroy() { camera?.stop(); camera = null; super.onDestroy() }

    private fun colorCrop(c: ColorFrame, b: Box): Bitmap {
        val px = IntArray(b.w * b.h)
        for (y in 0 until b.h) System.arraycopy(c.argb, (b.y0 + y) * c.w + b.x0, px, y * b.w, b.w)
        return Bitmap.createBitmap(px, b.w, b.h, Bitmap.Config.ARGB_8888)
    }

    private fun toBitmap(g: Gray): Bitmap {
        val px = IntArray(g.w * g.h) { val v = g.px[it].toInt().coerceIn(0, 255); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        return Bitmap.createBitmap(px, g.w, g.h, Bitmap.Config.ARGB_8888)
    }
}

/**
 * The camera picture with the screen (green; red when it runs off the edge)
 * and logo (yellow) boxes; drag to draw your own box, pinch to zoom the camera.
 */
class LogoOverlayView(context: android.content.Context, attrs: android.util.AttributeSet? = null) : View(context, attrs) {
    private var bitmap: Bitmap? = null
    private var frameW = 1; private var frameH = 1
    private var screen: Box? = null
    private var roi: Box? = null
    private var partial = false
    private var drag: RectF? = null
    private var scale = 1f; private var offX = 0f; private var offY = 0f
    var onBox: ((Box) -> Unit)? = null
    var onPinch: ((Float) -> Unit)? = null
    private val green = Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 4f }
    private val red = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 6f }
    private val yellow = Paint().apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 4f }
    private val white = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f }
    private var pinching = false
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean { pinching = true; drag = null; return true }
        override fun onScale(d: ScaleGestureDetector): Boolean { onPinch?.invoke(d.scaleFactor); return true }
        override fun onScaleEnd(d: ScaleGestureDetector) { pinching = false }
    })

    fun update(b: Bitmap, fw: Int, fh: Int, s: Box?, r: Box?, partialScreen: Boolean) { bitmap = b; frameW = fw; frameH = fh; screen = s; roi = r; partial = partialScreen; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        scale = min(width.toFloat() / frameW, height.toFloat() / frameH)
        offX = (width - frameW * scale) / 2; offY = (height - frameH * scale) / 2
        canvas.drawBitmap(b, null, RectF(offX, offY, offX + frameW * scale, offY + frameH * scale), null)
        screen?.let { canvas.drawRect(rect(it), if (partial) red else green) }
        roi?.let { canvas.drawRect(rect(it), yellow) }
        drag?.let { canvas.drawRect(it, white) }
    }

    private fun rect(bx: Box) = RectF(offX + bx.x0 * scale, offY + bx.y0 * scale, offX + bx.x1 * scale, offY + bx.y1 * scale)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        pinch.onTouchEvent(e)
        if (pinching || e.pointerCount > 1) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { drag = RectF(e.x, e.y, e.x, e.y); return true }
            MotionEvent.ACTION_MOVE -> { drag?.let { it.right = e.x; it.bottom = e.y; invalidate() }; return true }
            MotionEvent.ACTION_UP -> {
                val d = drag ?: return true
                drag = null
                val x0 = ((min(d.left, d.right) - offX) / scale).toInt(); val x1 = ((max(d.left, d.right) - offX) / scale).toInt()
                val y0 = ((min(d.top, d.bottom) - offY) / scale).toInt(); val y1 = ((max(d.top, d.bottom) - offY) / scale).toInt()
                if (x1 - x0 >= 6 && y1 - y0 >= 6) onBox?.invoke(Box(x0, y0, x1, y1))
                invalidate(); return true
            }
        }
        return super.onTouchEvent(e)
    }
}
