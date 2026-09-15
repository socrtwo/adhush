package io.adhush.core

import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * The break clock (ADR 0017): a learned prior on the minute of the hour.
 * Cable news runs to a format clock — breaks land at roughly the same minutes
 * every hour — but the clock is neither published nor exact, so nothing is
 * hard-coded. Every break the other methods (or the user) confirm is scored
 * against the minutes it covered; every minute the app watches is counted as
 * seen. The vote is the fraction of watched hours in which this minute was a
 * break, scaled so that [ClockConfig.fullFraction] reads as certainty. It is a
 * default-weight vote: on its own it can never duck, it tips the balance.
 */
/** What the clock keeps: breaks per minute, hours seen per minute, and break lengths in 30-second bins (ADR 0020). */
class ClockCounts(val breaks: IntArray = IntArray(60), val seen: IntArray = IntArray(60), val lengths: IntArray = IntArray(ClockDetector.LENGTH_BINS))

interface ClockStore {
    fun load(): ClockCounts?
    fun save(counts: ClockCounts)
}

class FileClockStore(private val file: File? = null) : ClockStore {
    override fun load(): ClockCounts? {
        val f = file?.takeIf { it.isFile } ?: return null
        val c = ClockCounts()
        f.forEachLine { line ->
            val p = line.split('\t')
            when {
                p[0] == "m" && p.size >= 4 -> { val m = p[1].toIntOrNull() ?: return@forEachLine; if (m in 0..59) { c.breaks[m] = p[2].toInt(); c.seen[m] = p[3].toInt() } }
                p[0] == "d" && p.size >= 3 -> { val b = p[1].toIntOrNull() ?: return@forEachLine; if (b in c.lengths.indices) c.lengths[b] = p[2].toInt() }
            }
        }
        return c
    }

    override fun save(counts: ClockCounts) {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            w.write("# adhush clock v2\tminute\tbreaks\thours_seen | d\t30s-bin\tbreaks\n")
            for (m in 0 until 60) w.write("m\t$m\t${counts.breaks[m]}\t${counts.seen[m]}\n")
            for (b in counts.lengths.indices) w.write("d\t$b\t${counts.lengths[b]}\n")
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}

data class ClockConfig(
    /** A minute must have been watched in this many distinct hours before it votes. */
    val minHours: Int = 3,
    /** This fraction of watched hours being a break reads as full confidence. */
    val fullFraction: Double = 0.6,
    /** Only breaks of a plausible length teach the clock. */
    val minBreakS: Double = 15.0,
    val maxBreakS: Double = 300.0,
)

class ClockDetector(private val store: ClockStore, private val cfg: ClockConfig = ClockConfig()) : Detector {
    override val name = "clock"
    private val breaks = IntArray(60)
    private val seen = IntArray(60)
    /** Break lengths in 30-second bins (DTC: breaks are built from 30-second units, up to six minutes). */
    private val lengths = IntArray(LENGTH_BINS)
    private var minute = -1
    private var lastKey = Long.MIN_VALUE   // hour * 60 + minute of the last tick
    private var hourOfLastSave = Long.MIN_VALUE

    init { store.load()?.let { c -> c.breaks.copyInto(breaks); c.seen.copyInto(seen); c.lengths.copyInto(lengths) } }

    private fun persist() = store.save(ClockCounts(breaks, seen, lengths))

    override fun warmup() {}   // what it learned is kept across sessions
    override fun observeAudio(block: AudioBlock) {}

    /** Wall-clock seconds; called every tick. Each (hour, minute) counts as seen once. */
    fun tick(wall: Double) {
        val key = (wall / 60.0).toLong()
        minute = (key % 60).toInt()
        if (key == lastKey) return
        lastKey = key
        seen[minute] = min(seen[minute] + 1, MAX_COUNT)
        val hour = key / 60
        if (hour != hourOfLastSave) { hourOfLastSave = hour; persist() }
    }

    /** A confirmed break from [startWall] to [endWall] (wall-clock seconds). */
    fun learn(startWall: Double, endWall: Double) {
        val dur = endWall - startWall
        if (dur < cfg.minBreakS || dur > cfg.maxBreakS) return
        val first = (startWall / 60.0).toLong(); val last = ((endWall - 1.0) / 60.0).toLong()
        for (k in first..last) { val m = (k % 60).toInt(); breaks[m] = min(breaks[m] + 1, MAX_COUNT) }
        val bin = min(LENGTH_BINS - 1, (dur / LENGTH_BIN_S).toInt())
        lengths[bin] = min(lengths[bin] + 1, MAX_COUNT)
        persist()
    }

    /** How many break lengths have been learned. */
    val lengthSamples: Int get() = lengths.sum()

    /** The break length at a percentile of what this channel has shown, or null before [MIN_LENGTH_SAMPLES]. */
    fun lengthAt(percentile: Double): Double? {
        val n = lengthSamples
        if (n < MIN_LENGTH_SAMPLES) return null
        val target = percentile * n
        var acc = 0
        for (b in lengths.indices) { acc += lengths[b]; if (acc >= target) return (b + 1) * LENGTH_BIN_S }
        return LENGTH_BINS * LENGTH_BIN_S
    }

    /** A ceiling for this channel's breaks: a little over the longest usual one, never above [hardMaxS] nor below 90 s. */
    fun ceilingS(hardMaxS: Double): Double = lengthAt(0.9)?.let { (it + 30.0).coerceIn(90.0, hardMaxS) } ?: hardMaxS

    /** "About this long to go" from the typical length, or null while still learning. */
    fun remainingS(elapsedS: Double): Double? = lengthAt(0.75)?.let { max(0.0, it - elapsedS) }

    /** Inert until this minute has been watched in enough hours: no dilution while learning. */
    override val voting: Boolean get() = minute >= 0 && seen[minute] >= cfg.minHours

    private fun fraction(m: Int): Double = if (seen[m] <= 0) 0.0 else min(1.0, breaks[m].toDouble() / seen[m])

    override fun vote(ts: Double): DetectorVote {
        if (!voting) return vote(ts, 0.0, "clock_learning minute=$minute seen=${if (minute >= 0) seen[minute] else 0}")
        val frac = fraction(minute)
        return vote(ts, frac / cfg.fullFraction, "clock minute=$minute frac=${"%.2f".format(frac)} seen=${seen[minute]}")
    }

    /** For the status line: what the clock thinks of this minute. */
    fun describe(): String {
        if (minute < 0) return "no time yet"
        val s = seen[minute]
        if (s < cfg.minHours) return ":${"%02d".format(minute)} learning ($s of ${cfg.minHours} hours)"
        return ":${"%02d".format(minute)} a break in ${breaks[minute]} of $s hours"
    }

    /** The whole hour as text, for a log or a page: minutes that are usually a break. */
    fun breakMinutes(): List<Int> = (0 until 60).filter { seen[it] >= cfg.minHours && fraction(it) >= cfg.fullFraction }

    companion object {
        const val MAX_COUNT = 100_000
        const val LENGTH_BIN_S = 30.0
        const val LENGTH_BINS = 12
        const val MIN_LENGTH_SAMPLES = 5
    }
}
