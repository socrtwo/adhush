package io.adhush.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the corners of the phone's own screen during stream learning (ADR
 * 0020): a small virtual display mirrors the screen into an ImageReader, and
 * once a second the four corner strips are stacked into one small bitmap and
 * handed to the caption text recogniser. Whatever it reads goes to the
 * engine's badge detector, which decides whether it says "AD".
 */
@RequiresApi(Build.VERSION_CODES.Q)
class BadgeReader(
    private val projection: MediaProjection,
    context: Context,
    private val onText: (Double, String) -> Unit,
    private val clock: () -> Double,
) : AutoCloseable {
    private val dpi = context.resources.displayMetrics.densityDpi
    private val thread = HandlerThread("adhush-badge").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader: ImageReader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)
    private var display: VirtualDisplay? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val busy = AtomicBoolean(false)
    @Volatile var frames = 0L
        private set
    @Volatile var lastText = ""
        private set

    private val tick = object : Runnable {
        override fun run() { grab(); handler.postDelayed(this, INTERVAL_MS) }
    }

    fun start() {
        display = projection.createVirtualDisplay("adhush-badge", W, H, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)
        handler.postDelayed(tick, INTERVAL_MS)
    }

    private fun grab() {
        val image = try { reader.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            if (!busy.compareAndSet(false, true)) return
            val plane = image.planes[0]
            val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
            val padded = Bitmap.createBitmap(rowStride / pixelStride, H, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val full = if (padded.width != W) Bitmap.createBitmap(padded, 0, 0, W, H) else padded
            frames++
            // The four corners, stacked: badges live there; the middle of the picture never has one.
            val cw = W * 3 / 10; val ch = H * 15 / 100
            val stack = Bitmap.createBitmap(cw, ch * 4, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(stack)
            val corners = listOf(Pair(0, 0), Pair(W - cw, 0), Pair(0, H - ch), Pair(W - cw, H - ch))
            for ((i, c) in corners.withIndex()) canvas.drawBitmap(Bitmap.createBitmap(full, c.first, c.second, cw, ch), 0f, (i * ch).toFloat(), null)
            val big = Bitmap.createScaledBitmap(stack, cw * 2, ch * 8, true)
            recognizer.process(InputImage.fromBitmap(big, 0))
                .addOnSuccessListener { text ->
                    val line = text.textBlocks.flatMap { b -> b.lines.map { it.text } }.joinToString(" ").trim()
                    lastText = line
                    onText(clock(), line)
                }
                .addOnCompleteListener { busy.set(false) }
        } catch (e: Exception) {
            AppLog.w("badge", "frame skipped: ${e.message}"); busy.set(false)
        } finally { image.close() }
    }

    override fun close() {
        handler.removeCallbacks(tick)
        runCatching { display?.release() }; display = null
        runCatching { reader.close() }
        runCatching { recognizer.close() }
        thread.quitSafely()
    }

    companion object {
        const val W = 640
        const val H = 360
        const val INTERVAL_MS = 1000L
    }
}
