package io.adhush.android

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import java.util.WeakHashMap

/**
 * A button changes only when something real happened (0.28.4) — never a
 * flex for a tap that went nowhere. Three states: **busy** (blue, disabled,
 * its label the job's progress) for as long as a job the button started is
 * running, so a second press cannot start it twice; **ok** (green, a tick)
 * for a second and a half once what it asked for went through; **fail**
 * (red, a cross) when it did not. Then it comes back as it was, or with the
 * label the outcome calls for.
 */
object Feedback {
    private class Saved(var label: CharSequence, val bg: ColorStateList?, val text: ColorStateList, val stroke: ColorStateList?, val chipBg: ColorStateList?, val enabled: Boolean)
    private val saved = WeakHashMap<Button, Saved>()
    private val main = Handler(Looper.getMainLooper())
    private const val HOLD_MS = 1500L
    private val GLYPH = Regex("^[✓✗▶■●] ")

    /** The job has started and is still running: the button is taken until [ok] or [fail]. */
    fun busy(b: Button?, label: CharSequence) {
        if (b == null) return
        remember(b)
        main.removeCallbacksAndMessages(b)
        paint(b, R.color.adhush_primary)
        b.isEnabled = false
        b.text = label
    }

    /** A busy button's label follows the job (a download's percentage). */
    fun progress(b: Button?, label: CharSequence) { if (b != null && !b.isEnabled) b.text = label }

    fun ok(b: Button?, thenLabel: CharSequence? = null) { if (b != null) flash(b, R.color.adhush_program, "✓", thenLabel) }
    fun fail(b: Button?, thenLabel: CharSequence? = null) { if (b != null) flash(b, R.color.adhush_ducked, "✗", thenLabel) }

    private fun remember(b: Button): Saved = saved.getOrPut(b) { Saved(b.text, b.backgroundTintList, b.textColors, (b as? MaterialButton)?.strokeColor, (b as? Chip)?.chipBackgroundColor, b.isEnabled) }

    private fun paint(b: Button, colour: Int) {
        val c = ColorStateList.valueOf(ContextCompat.getColor(b.context, colour))
        when (b) {
            is Chip -> b.chipBackgroundColor = c
            is MaterialButton -> { b.backgroundTintList = c; b.strokeColor = c }
            else -> b.backgroundTintList = c
        }
        b.setTextColor(Color.WHITE)
    }

    private fun flash(b: Button, colour: Int, mark: String, thenLabel: CharSequence?) {
        val orig = remember(b)
        if (thenLabel != null) orig.label = thenLabel
        main.removeCallbacksAndMessages(b)
        paint(b, colour)
        b.isEnabled = false
        b.text = "$mark " + orig.label.toString().replace(GLYPH, "")
        main.postAtTime({ restore(b) }, b, SystemClock.uptimeMillis() + HOLD_MS)
    }

    private fun restore(b: Button) {
        val orig = saved.remove(b) ?: return
        b.text = orig.label
        b.setTextColor(orig.text)
        b.isEnabled = orig.enabled
        when (b) {
            is Chip -> b.chipBackgroundColor = orig.chipBg
            is MaterialButton -> { b.backgroundTintList = orig.bg; b.strokeColor = orig.stroke }
            else -> b.backgroundTintList = orig.bg
        }
    }
}

/** A click; the block gets the button so it can report the outcome. */
fun Button.onTap(block: (Button) -> Unit) = setOnClickListener { block(this) }
