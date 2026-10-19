package io.adhush.android

import android.content.Context
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.adhush.core.Gray
import java.util.concurrent.Executors

/** A colour frame for the setup preview: row-major ARGB, upright like the [Gray] it came with. */
class ColorFrame(val w: Int, val h: Int, val argb: IntArray) {
    fun rotate(degrees: Int): ColorFrame = when (((degrees % 360) + 360) % 360) {
        0 -> this
        90 -> ColorFrame(h, w, IntArray(w * h).also { o -> for (y in 0 until h) for (x in 0 until w) o[x * h + (h - 1 - y)] = argb[y * w + x] })
        180 -> ColorFrame(w, h, IntArray(w * h).also { o -> for (i in argb.indices) o[argb.size - 1 - i] = argb[i] })
        270 -> ColorFrame(h, w, IntArray(w * h).also { o -> for (y in 0 until h) for (x in 0 until w) o[(w - 1 - x) * h + y] = argb[y * w + x] })
        else -> throw IllegalArgumentException("rotation must be a multiple of 90: $degrees")
    }
}

/**
 * The back camera as a trickle of luma frames — two a second at 1280 × 720 is
 * plenty to see whether a bug is on a screen eight feet away, and a small
 * fraction of what recording video costs. Frames come out upright (rotated
 * by the sensor's rotationDegrees) so the setup screen and the service see
 * the same picture. Frames are stamped by the caller with media time so the
 * logo detector and the audio detectors share one clock. The setup screen
 * also asks for colour, which is only ever shown to a person: every detector
 * works on the luma.
 */
class CameraSource(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val intervalMs: Long = INTERVAL_MS,
    /** Deliver a colour copy alongside the luma (the setup preview); costs a few tens of milliseconds a frame. */
    private val wantColor: Boolean = false,
    private val onFrame: (Gray, ColorFrame?) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    @Volatile private var camera: Camera? = null
    @Volatile private var wantedZoom = 1f
    @Volatile private var lastAt = 0L
    @Volatile var frames = 0L
        private set

    fun start(onError: (String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get(); provider = p
                @Suppress("DEPRECATION")
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))   // an 8 ft living room needs the pixels
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    try {
                        val now = System.currentTimeMillis()
                        if (now - lastAt >= intervalMs) {
                            lastAt = now; frames++
                            val deg = image.imageInfo.rotationDegrees
                            val color = if (wantColor) toColor(image).rotate(deg) else null
                            onFrame(toGray(image).rotate(deg), color)
                        }
                    } finally { image.close() }
                }
                p.unbindAll()
                val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                camera = cam
                applyZoom(wantedZoom)
            } catch (e: Exception) { onError("camera: ${e.message}") }
        }, ContextCompat.getMainExecutor(context))
    }

    /** The lens's largest zoom, 1.0 until the camera is open. */
    val maxZoom: Float get() = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f

    /** Zoom in on the set (optical where the phone has it, digital otherwise); the whole TV must still fit. */
    fun setZoom(ratio: Float) { wantedZoom = ratio; applyZoom(ratio) }

    private fun applyZoom(ratio: Float) {
        val cam = camera ?: return
        val max = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        runCatching { cam.cameraControl.setZoomRatio(ratio.coerceIn(1f, max)) }
    }

    fun stop() { runCatching { provider?.unbindAll() }; provider = null; camera = null; executor.shutdown() }

    /** The Y plane, honouring the row stride; chroma is irrelevant to edges. */
    private fun toGray(image: ImageProxy): Gray {
        val plane = image.planes[0]; val buf = plane.buffer; val stride = plane.rowStride; val ps = plane.pixelStride
        val w = image.width; val h = image.height
        val px = FloatArray(w * h)
        val row = ByteArray(stride)
        for (y in 0 until h) {
            buf.position(y * stride); buf.get(row, 0, minOf(stride, buf.remaining()))
            for (x in 0 until w) px[y * w + x] = (row[x * ps].toInt() and 0xFF).toFloat()
        }
        return Gray(w, h, px)
    }

    /** YUV_420_888 → ARGB, honouring each plane's strides; only for the preview a person looks at. */
    private fun toColor(image: ImageProxy): ColorFrame {
        val yP = image.planes[0]; val uP = image.planes[1]; val vP = image.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        val w = image.width; val h = image.height
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val yRow = y * yP.rowStride; val cRow = (y / 2)
            for (x in 0 until w) {
                val yy = yb.get(yRow + x * yP.pixelStride).toInt() and 0xFF
                val ci = (x / 2)
                val u = (ub.get(cRow * uP.rowStride + ci * uP.pixelStride).toInt() and 0xFF) - 128
                val v = (vb.get(cRow * vP.rowStride + ci * vP.pixelStride).toInt() and 0xFF) - 128
                val r = (yy + 1.370705f * v).toInt().coerceIn(0, 255)
                val g = (yy - 0.337633f * u - 0.698001f * v).toInt().coerceIn(0, 255)
                val b = (yy + 1.732446f * u).toInt().coerceIn(0, 255)
                out[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return ColorFrame(w, h, out)
    }

    companion object { const val INTERVAL_MS = 500L }
}
