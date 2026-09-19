package io.adhush.android

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.adhush.core.Box
import io.adhush.core.Gray
import io.adhush.core.Vision
import io.adhush.core.Word
import io.adhush.core.normalizeWord
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The sixth method's eyes: reads the closed captions off the TV picture. The
 * caption band (the lower part of the found screen) is cut out of each camera
 * frame, enlarged, and handed to the on-device text recogniser (ML Kit's
 * Latin model, bundled in the APK; nothing leaves the phone). New words —
 * captions sit still for a second or two, so the same line comes back many
 * times — are stamped with media time and fed to a transcript detector,
 * exactly as the microphone's words are. A commercial's captions repeat
 * verbatim every airing, so repetition learning and script matching apply
 * unchanged; the difference is that captions are never misheard over the
 * room's fans.
 */
class CaptionSource(private val onWords: (List<Word>) -> Unit) : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val busy = AtomicBoolean(false)
    private var lastLine = ""
    private var lastAt = 0L
    @Volatile var lastText = ""
        private set
    @Volatile var linesRead = 0L
        private set
    @Volatile var screenSeen = false
        private set

    /** Feed an upright camera frame with its media time; at most one recognition runs at a time. */
    fun feed(frame: Gray, ts: Double) {
        val now = System.currentTimeMillis()
        if (now - lastAt < INTERVAL_MS || !busy.compareAndSet(false, true)) return
        lastAt = now
        val screen = Vision.findScreen(frame)?.takeIf { Vision.screenComplete(it, frame.w, frame.h) }
        screenSeen = screen != null
        if (screen == null) { busy.set(false); return }
        // The caption band: the bottom 35 % of the screen, enlarged so eight-foot text is tall enough to read.
        val band = Box(screen.x0, screen.y0 + screen.h * 65 / 100, screen.x1, screen.y1)
        val crop = frame.crop(band)
        val scale = (MIN_BAND_HEIGHT / crop.h.toFloat()).coerceAtLeast(1f)
        val bmp = toBitmap(crop)
        val big = if (scale > 1f) Bitmap.createScaledBitmap(bmp, (crop.w * scale).toInt(), (crop.h * scale).toInt(), true) else bmp
        recognizer.process(InputImage.fromBitmap(big, 0))
            .addOnSuccessListener { text ->
                val line = text.textBlocks.flatMap { b -> b.lines.map { it.text } }.joinToString(" ").trim()
                lastText = line
                if (line.isNotEmpty() && line != lastLine) {
                    val fresh = newWords(lastLine, line)
                    lastLine = line
                    if (fresh.isNotEmpty()) { linesRead++; onWords(fresh.map { Word(ts, it) }) }
                }
            }
            .addOnCompleteListener { busy.set(false) }
    }

    /**
     * Captions scroll: the new line usually shares its head with the old one
     * (the old top row is gone, the new bottom row arrived). Only words that
     * were not in the previous reading are new; the rest were already fed.
     */
    private fun newWords(previous: String, current: String): List<String> {
        val old = previous.split(Regex("\\s+")).map(::normalizeWord).filter { it.isNotEmpty() }
        val cur = current.split(Regex("\\s+")).map(::normalizeWord).filter { it.isNotEmpty() }
        if (old.isEmpty()) return cur
        // Longest suffix of old that is a prefix of cur, or any overlap of at least three words anywhere.
        for (k in minOf(old.size, cur.size) downTo 3) if (old.takeLast(k) == cur.take(k)) return cur.drop(k)
        val seen = old.toHashSet()
        return cur.filter { it !in seen }
    }

    private fun toBitmap(g: Gray): Bitmap {
        val px = IntArray(g.w * g.h) { val v = g.px[it].toInt().coerceIn(0, 255); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        return Bitmap.createBitmap(px, g.w, g.h, Bitmap.Config.ARGB_8888)
    }

    override fun close() { runCatching { recognizer.close() } }

    companion object {
        const val INTERVAL_MS = 1000L
        /** The recogniser wants text about 20 px tall; a caption row is a tenth of the band. */
        const val MIN_BAND_HEIGHT = 240f
    }
}
