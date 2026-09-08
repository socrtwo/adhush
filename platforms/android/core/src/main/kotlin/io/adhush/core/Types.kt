package io.adhush.core

/** One block of mono audio, float32 samples in [-1, 1]. Mirrors events.AudioEvent. */
class AudioBlock(val ts: Double, val samples: FloatArray, val sampleRate: Int) {
    val duration: Double get() = samples.size.toDouble() / sampleRate
}

/** A detector's opinion at a moment: 0 = programme, 1 = ad. Mirrors events.DetectorVote. */
data class DetectorVote(val detector: String, val ts: Double, val confidence: Double, val reason: String) {
    init { require(confidence in 0.0..1.0) { "confidence $confidence outside [0, 1]" } }
}

/** Fusion output. Mirrors events.MuteDecision. */
data class MuteDecision(val ts: Double, val mute: Boolean, val confidence: Double, val reasons: List<String>)

/** Detectors vote; they never touch a controller (ADR 0003). Mirrors detect/base.py. */
interface Detector {
    val name: String
    fun warmup()
    fun observeAudio(block: AudioBlock)
    fun vote(ts: Double): DetectorVote
    fun vote(ts: Double, confidence: Double, reason: String): DetectorVote =
        DetectorVote(name, ts, confidence.coerceIn(0.0, 1.0), reason)
}

enum class AdState(val wire: String) { PROGRAM("program"), SUSPECT_AD("suspect_ad"), AD("ad"), RECOVERY("recovery") }
enum class Action(val wire: String) { MUTE("mute"), UNMUTE("unmute") }

/** Controllers act; they never inspect audio (ADR 0003). Mirrors control/base.py. */
interface MuteController {
    fun mute()
    fun unmute()
    /** Commanded state, or null when the transport cannot say. */
    fun state(): Boolean?
    fun close()
}

class ControlError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
