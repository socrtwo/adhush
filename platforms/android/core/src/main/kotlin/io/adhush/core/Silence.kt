package io.adhush.core

/**
 * The microphone edition of the silence detector. The Python original tests an
 * absolute dBFS floor that only a line tap ever reaches; a room never does.
 * Here "quiet" is relative to a rolling room floor (a low percentile of recent
 * block levels), while the level-invariant spectral-flatness test and the
 * run/decay logic are ported unchanged from detect/silence.py.
 *
 * quietMarginDb is a starting guess (docs/android-app-design.md): calibrate it
 * on recordings from the actual room before trusting this vote.
 */
data class MicSilenceConfig(
    val quietMarginDb: Double = 4.0,
    val floorWindowS: Double = 60.0,
    val floorPercentile: Double = 0.10,
    val warmupS: Double = 10.0,
    val minRunMs: Int = 400,
)

class MicSilenceDetector(private val cfg: MicSilenceConfig = MicSilenceConfig()) : Detector {
    override val name = "silence"

    private val history = ArrayDeque<Triple<Double, Double, Double>>()  // (ts, duration, dBFS)
    private var historyDur = 0.0
    private var runMs = 0.0
    private var lastDbfs = 0.0
    private var runEndedTs: Double? = null
    private var endedRunMs = 0.0
    var floorDbfs = -120.0
        private set

    override fun warmup() {
        history.clear(); historyDur = 0.0; runMs = 0.0; lastDbfs = 0.0; runEndedTs = null; endedRunMs = 0.0
        floorDbfs = -120.0
    }

    private fun percentile(values: DoubleArray, p: Double): Double {
        val sorted = values.copyOf(); sorted.sort()
        val idx = (p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    override fun observeAudio(block: AudioBlock) {
        lastDbfs = Dsp.blockDbfs(block.samples)
        history.addLast(Triple(block.ts, block.duration, lastDbfs))
        historyDur += block.duration
        while (historyDur > cfg.floorWindowS && history.size > 1) historyDur -= history.removeFirst().second
        floorDbfs = percentile(DoubleArray(history.size) { history.elementAt(it).third }, cfg.floorPercentile)
        val warm = historyDur >= cfg.warmupS
        val quiet = lastDbfs <= floorDbfs + cfg.quietMarginDb
        val flat = lastDbfs <= -80.0 || Dsp.spectralFlatness(block.samples) >= FLATNESS_MIN
        if (warm && quiet && flat) {
            runMs += block.duration * 1000.0
            runEndedTs = null
        } else {
            if (runMs >= cfg.minRunMs) { runEndedTs = block.ts; endedRunMs = runMs }
            runMs = 0.0
        }
    }

    override fun vote(ts: Double): DetectorVote {
        if (runMs >= cfg.minRunMs) {
            return vote(ts, 1.0, "silence_run ms=${runMs.toInt()} dbfs=${"%.1f".format(lastDbfs)} floor=${"%.1f".format(floorDbfs)}")
        }
        val ended = runEndedTs
        if (ended != null) {
            val age = ts - ended
            if (age >= 0.0 && age < DECAY_S) {
                return vote(ts, 1.0 - age / DECAY_S, "silence_ended ms=${endedRunMs.toInt()} age_s=${"%.2f".format(age)}")
            }
            runEndedTs = null
        }
        return vote(ts, 0.0, "no_silence dbfs=${"%.1f".format(lastDbfs)} floor=${"%.1f".format(floorDbfs)}")
    }

    companion object {
        const val DECAY_S = 2.5
        const val FLATNESS_MIN = 0.2
    }
}
