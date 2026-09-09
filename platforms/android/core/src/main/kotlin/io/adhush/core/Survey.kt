package io.adhush.core

import java.io.File
import java.util.Locale
import kotlin.math.max

/**
 * A room survey: for a fixed span of listening, record per block exactly what
 * the audio detectors measure — level, spectral flatness, short-term loudness,
 * the silence detector's running floor and both detectors' confidence — so the
 * thresholds can be tuned from this room rather than guessed. Numbers only:
 * no audio is kept. Uses its own detector instances so the running engine is
 * not disturbed.
 */
class RoomSurvey(
    val durationS: Double = 600.0,
    private val loudnessCfg: LoudnessConfig = LoudnessConfig(),
    private val silenceCfg: MicSilenceConfig = MicSilenceConfig(),
) {
    data class Row(
        val ts: Double, val dbfs: Double, val flatness: Double, val stLufs: Double,
        val floorDbfs: Double, val silenceConf: Double, val loudnessConf: Double, val ducked: Boolean,
    )

    private val loudness = LoudnessDetector(loudnessCfg).also { it.warmup() }
    private val silence = MicSilenceDetector(silenceCfg).also { it.warmup() }
    private val _rows = ArrayList<Row>()
    val rows: List<Row> get() = _rows
    private var startTs: Double? = null
    var elapsedS = 0.0
        private set
    val done: Boolean get() = elapsedS >= durationS

    /** Feed one block; returns true when the survey has just completed. */
    fun feed(block: AudioBlock, ducked: Boolean = false): Boolean {
        if (done) return false
        if (startTs == null) startTs = block.ts
        loudness.observeAudio(block); silence.observeAudio(block)
        val t = block.ts + block.duration
        _rows.add(Row(
            ts = block.ts - startTs!!,
            dbfs = Dsp.blockDbfs(block.samples),
            flatness = Dsp.spectralFlatness(block.samples),
            stLufs = loudness.lastShortTerm,
            floorDbfs = silence.floorDbfs,
            silenceConf = silence.vote(t).confidence,
            loudnessConf = loudness.vote(t).confidence,
            ducked = ducked,
        ))
        elapsedS = t - startTs!!
        return done
    }

    fun writeTsv(file: File) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { w ->
            w.write("ts_s\tdbfs\tflatness\tst_lufs\tfloor_dbfs\tsilence_conf\tloudness_conf\tducked\n")
            for (r in _rows) w.write(
                String.format(Locale.US, "%.2f\t%.1f\t%.3f\t%.1f\t%.1f\t%.2f\t%.2f\t%d\n",
                    r.ts, r.dbfs, r.flatness, r.stLufs, r.floorDbfs, r.silenceConf, r.loudnessConf, if (r.ducked) 1 else 0)
            )
        }
    }

    /** Human-readable digest: levels, how far the programme sits above the floor, what the detectors would have said. */
    fun summary(): String {
        if (_rows.isEmpty()) return "survey: no audio received"
        val undocked = _rows.filter { !it.ducked }.ifEmpty { _rows }
        val dbfs = undocked.map { it.dbfs }.toDoubleArray()
        val lufs = undocked.map { it.stLufs }.filter { it > -69.0 }.toDoubleArray()
        val floor = _rows.last().floorDbfs
        val margin = silenceCfg.quietMarginDb
        val quietBlocks = undocked.count { it.dbfs <= it.floorDbfs + margin }
        val flatBlocks = undocked.count { it.dbfs <= -80.0 || it.flatness >= MicSilenceDetector.FLATNESS_MIN }
        var run = 0.0; var longestQuiet = 0.0; var prevTs = -1.0
        for (r in undocked) {
            val quiet = r.dbfs <= r.floorDbfs + margin && (r.dbfs <= -80.0 || r.flatness >= MicSilenceDetector.FLATNESS_MIN)
            run = if (quiet && prevTs >= 0) run + (r.ts - prevTs) else 0.0
            longestQuiet = max(longestQuiet, run); prevTs = r.ts
        }
        val silenceFires = undocked.count { it.silenceConf >= 1.0 }
        val loudFires = undocked.count { it.loudnessConf >= 0.5 }
        val f = { v: Double -> String.format(Locale.US, "%.1f", v) }
        val p50 = pct(dbfs, 0.5)
        val lines = ArrayList<String>()
        lines += "survey ${f(elapsedS)} s, ${_rows.size} blocks" + if (undocked.size != _rows.size) " (${_rows.size - undocked.size} while ducked, excluded)" else ""
        lines += "level dBFS p10/p50/p90/max ${f(pct(dbfs, 0.1))} / ${f(p50)} / ${f(pct(dbfs, 0.9))} / ${f(dbfs.max())}"
        if (lufs.isNotEmpty()) lines += "short-term LUFS p10/p50/p90 ${f(pct(lufs, 0.1))} / ${f(pct(lufs, 0.5))} / ${f(pct(lufs, 0.9))}"
        else lines += "short-term LUFS: everything under the ${f(LoudnessDetector.SILENCE_GATE_LUFS)} gate — mic too far or TV too quiet"
        lines += "silence floor ${f(floor)} dBFS; programme median sits ${f(p50 - floor)} dB above it (margin ${f(margin)} dB)"
        lines += "would count quiet: ${pctOf(quietBlocks, undocked.size)}% of blocks; flat: ${pctOf(flatBlocks, undocked.size)}%; longest quiet run ${f(longestQuiet)} s"
        lines += "silence detector at full confidence in ${pctOf(silenceFires, undocked.size)}% of blocks; loudness ≥ 0.5 in ${pctOf(loudFires, undocked.size)}%"
        lines += when {
            p50 - floor < 6.0 -> "verdict: too little contrast — move the phone closer to the TV or raise its volume before tuning"
            quietBlocks > undocked.size / 5 -> "verdict: a fifth of the programme reads as quiet — lower quietMarginDb (try ${f(max(1.0, margin - 2))})"
            silenceFires == 0 && loudFires == 0 -> "verdict: clean — no detector fired during the survey; thresholds can stay"
            else -> "verdict: usable — compare the fires above with when ads actually ran"
        }
        return lines.joinToString("\n")
    }

    private fun pct(values: DoubleArray, p: Double): Double {
        val sorted = values.copyOf(); sorted.sort()
        return sorted[(p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)]
    }
    private fun pctOf(n: Int, total: Int): Int = if (total == 0) 0 else (100.0 * n / total + 0.5).toInt()
}
