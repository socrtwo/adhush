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
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
 * See what the camera sees, and choose the logo by eye. The live picture shows
 * the found screen (green) and the logo box (yellow); a magnified crop of the
 * box sits below it with a live match score. "Watch 45 s" runs the automatic
 * finder; dragging a box on the picture overrides its choice; "Save & start"
 * writes the template and starts the service. AdHush is stopped while this
 * screen is open, because two things cannot hold the camera at once.
 */
class CameraSetupActivity : AppCompatActivity() {
    private lateinit var picture: LogoOverlayView
    private lateinit var status: TextView
    private lateinit var crop: ImageView
    private lateinit var saveBtn: Button
    private var camera: CameraSource? = null
    private var finder = LogoFinder()
    @Volatile private var watching = false
    private var watchStartedAt = 0L
    @Volatile private var roi: Roi? = null
    @Volatile private var template: LogoTemplate? = null
    @Volatile private var detector: LogoAbsenceDetector? = null
    private var ts = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.startForegroundService(this, Intent(this, AdHushService::class.java).setAction(AdHushService.ACTION_STOP))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { textSize = 15f; text = "starting the camera …" }
        picture = LogoOverlayView(this)
        crop = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
        val watch = Button(this).apply { text = "Watch 45 s (find the logo)" }
        saveBtn = Button(this).apply { text = "Save & start"; isEnabled = false }
        val cancel = Button(this).apply { text = "Cancel" }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (b in listOf(watch, saveBtn, cancel)) row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(status)
        root.addView(picture, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(TextView(this).apply { text = "the logo box, magnified — it should look like the channel's bug"; textSize = 12f; gravity = Gravity.CENTER })
        root.addView(crop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 260))
        root.addView(row)
        setContentView(root)

        watch.setOnClickListener {
            finder = LogoFinder(); watching = true; watchStartedAt = System.currentTimeMillis()
            status.text = "watching — keep a show on, whole TV in view, hold still"
        }
        saveBtn.setOnClickListener {
            val t = template ?: return@setOnClickListener
            runCatching { t.save(File(filesDir, AdHushService.LOGO_FILE)) }
            Settings(this).camera = true
            ContextCompat.startForegroundService(this, Intent(this, AdHushService::class.java))
            finish()
        }
        cancel.setOnClickListener { finish() }
        picture.onBox = { boxInFrame -> chooseBox(boxInFrame) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 7)
        else startCamera()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startCamera() else status.text = "camera permission is needed"
    }

    private fun startCamera() {
        val cam = CameraSource(this, this, intervalMs = 250) { gray -> onFrame(gray) }
        camera = cam
        cam.start { msg -> runOnUiThread { status.text = msg } }
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
        val d = LogoAbsenceDetector(HANDHELD_LOGO_CONFIG, t); d.warmup(); detector = d
        runOnUiThread { status.text = "$how: ${t.roi.corner}, stability ${"%.2f".format(Locale.US, t.stability)} — watch the score, then Save"; saveBtn.isEnabled = true }
    }

    private fun onFrame(gray: Gray) {
        if (watching) {
            finder.feed(gray)
            val elapsed = (System.currentTimeMillis() - watchStartedAt) / 1000
            if (elapsed >= 45) {
                watching = false
                val chosen = roi
                adoptTemplate(if (chosen != null) finder.templateFor(chosen) else finder.result()?.also { roi = it.roi }, if (chosen != null) "your box" else "found")
            } else runOnUiThread { status.text = "watching $elapsed / 45 s · screen seen ${finder.frames} / ${finder.frames + finder.screenMisses}" }
        }
        val det = detector
        var line = ""
        if (det != null) {
            det.observeFrame(gray, ts); ts += 0.25
            line = if (det.active) "logo match ${"%.2f".format(Locale.US, det.lastScore)} ${if (det.programPresent) "PRESENT" else "absent"}" else "camera: ${det.inertReason}"
        }
        val screen = det?.lastScreen ?: finder.lastScreen ?: Vision.findScreen(gray)
        val roiBox = det?.lastRoiInFrame ?: roi?.let { r -> screen?.let { b -> Box(b.x0 + (r.x * b.w).toInt(), b.y0 + (r.y * b.h).toInt(), b.x0 + ((r.x + r.w) * b.w).toInt(), b.y0 + ((r.y + r.h) * b.h).toInt()) } }
        val cropBmp = roiBox?.let { toBitmap(gray.crop(Box(max(0, it.x0), max(0, it.y0), min(gray.w, it.x1), min(gray.h, it.y1)))) }
        runOnUiThread {
            picture.update(toBitmap(gray), screen, roiBox)
            if (line.isNotEmpty() && !watching) status.text = line + (template?.let { "  ·  box ${it.roi.corner}" } ?: "")
            if (cropBmp != null) crop.setImageBitmap(cropBmp)
        }
    }

    override fun onDestroy() { camera?.stop(); camera = null; super.onDestroy() }

    private fun toBitmap(g: Gray): Bitmap {
        val px = IntArray(g.w * g.h) { val v = g.px[it].toInt().coerceIn(0, 255); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        return Bitmap.createBitmap(px, g.w, g.h, Bitmap.Config.ARGB_8888)
    }
}

/** The camera picture with the screen (green) and logo (yellow) boxes; drag to draw your own box. */
class LogoOverlayView(context: android.content.Context) : View(context) {
    private var bitmap: Bitmap? = null
    private var screen: Box? = null
    private var roi: Box? = null
    private var drag: RectF? = null
    private var scale = 1f; private var offX = 0f; private var offY = 0f
    var onBox: ((Box) -> Unit)? = null
    private val green = Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 4f }
    private val yellow = Paint().apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 4f }
    private val white = Paint().apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f }

    fun update(b: Bitmap, s: Box?, r: Box?) { bitmap = b; screen = s; roi = r; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        scale = min(width.toFloat() / b.width, height.toFloat() / b.height)
        offX = (width - b.width * scale) / 2; offY = (height - b.height * scale) / 2
        canvas.drawBitmap(b, null, RectF(offX, offY, offX + b.width * scale, offY + b.height * scale), null)
        screen?.let { canvas.drawRect(rect(it), green) }
        roi?.let { canvas.drawRect(rect(it), yellow) }
        drag?.let { canvas.drawRect(it, white) }
    }

    private fun rect(bx: Box) = RectF(offX + bx.x0 * scale, offY + bx.y0 * scale, offX + bx.x1 * scale, offY + bx.y1 * scale)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
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
