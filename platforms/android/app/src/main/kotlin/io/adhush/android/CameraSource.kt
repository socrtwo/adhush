package io.adhush.android

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.adhush.core.Gray
import java.util.concurrent.Executors

/**
 * The back camera as a trickle of luma frames — two a second at 1280 × 720 is
 * plenty to see whether a bug is on a screen eight feet away, and a small
 * fraction of what recording video costs. Frames come out upright (rotated
 * by the sensor's rotationDegrees) so the setup screen and the service see
 * the same picture. Frames are stamped by the caller with media time so the
 * logo detector and the audio detectors share one clock.
 */
class CameraSource(private val context: Context, private val owner: LifecycleOwner, private val intervalMs: Long = INTERVAL_MS, private val onFrame: (Gray) -> Unit) {
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
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
                        if (now - lastAt >= intervalMs) { lastAt = now; frames++; onFrame(toGray(image).rotate(image.imageInfo.rotationDegrees)) }
                    } finally { image.close() }
                }
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
            } catch (e: Exception) { onError("camera: ${e.message}") }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() { runCatching { provider?.unbindAll() }; provider = null; executor.shutdown() }

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

    companion object { const val INTERVAL_MS = 500L }
}
