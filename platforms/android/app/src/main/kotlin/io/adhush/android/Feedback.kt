package io.adhush.android

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import java.util.WeakHashMap

/**
 * Every button answers the finger (0.28.3): it dips the moment it is pressed,
 * turns green with a tick for a second and a half when what it asked for
 * went through, red with a cross when it did not. Start and Stop keep their
 * own running colours; everything else flashes and comes back as it was.
 */
object Feedback {
    private class Saved(val label: CharSequence, val bg: ColorStateList?, val text: ColorStateList, val stroke: ColorStateList?, val chipBg: ColorStateList?)
    private val saved = WeakHashMap<Button, Saved>()
    private val main = Handler(Looper.getMainLooper())
    private const val HOLD_MS = 1400L
    private val GLYPH = Regex("^[✓✗▶■●] ")

    /** The dip: a small squeeze and back, so a tap that is still on its way to the service is seen at once. */
    fun pressed(v: View) {
        v.animate().cancel()
        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(140).start() }.start()
    }

    fun ok(b: Button?) { if (b != null) flash(b, R.color.adhush_program, "✓") }
    fun fail(b: Button?) { if (b != null) flash(b, R.color.adhush_ducked, "✗") }

    private fun flash(b: Button, colour: Int, mark: String) {
        val orig = saved.getOrPut(b) { Saved(b.text, b.backgroundTintList, b.textColors, (b as? MaterialButton)?.strokeColor, (b as? Chip)?.chipBackgroundColor) }
        main.removeCallbacksAndMessages(b)
        val c = ColorStateList.valueOf(ContextCompat.getColor(b.context, colour))
        when (b) {
            is Chip -> b.chipBackgroundColor = c
            is MaterialButton -> { b.backgroundTintList = c; b.strokeColor = c }
            else -> b.backgroundTintList = c
        }
        b.setTextColor(Color.WHITE)
        b.text = "$mark " + orig.label.toString().replace(GLYPH, "")
        main.postAtTime({ restore(b) }, b, SystemClock.uptimeMillis() + HOLD_MS)
    }

    private fun restore(b: Button) {
        val orig = saved.remove(b) ?: return
        b.text = orig.label
        b.setTextColor(orig.text)
        when (b) {
            is Chip -> b.chipBackgroundColor = orig.chipBg
            is MaterialButton -> { b.backgroundTintList = orig.bg; b.strokeColor = orig.stroke }
            else -> b.backgroundTintList = orig.bg
        }
    }
}

/** A click that dips the button first; the block gets the button so it can report the outcome. */
fun Button.onTap(block: (Button) -> Unit) = setOnClickListener { Feedback.pressed(this); block(this) }
