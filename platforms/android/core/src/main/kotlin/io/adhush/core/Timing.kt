package io.adhush.core

import kotlin.math.abs

/** How long a boolean has held continuously. Mirrors util/timing.DwellTimer. */
class DwellTimer(val dwellS: Double) {
    init { require(dwellS >= 0) { "dwellS must be >= 0" } }
    var activeSince: Double? = null
        private set

    fun update(condition: Boolean, now: Double): Boolean {
        if (!condition) { activeSince = null; return false }
        val since = activeSince ?: now.also { activeSince = it }
        return now - since >= dwellS
    }

    fun reset() { activeSince = null }
}

/** Nearest standard slot: a prior, not a guarantee. Mirrors util/timing.snap_to_slot. */
fun snapToSlot(durationS: Double, slots: List<Double>): Double =
    if (slots.isEmpty()) durationS else slots.minByOrNull { abs(it - durationS) }!!
