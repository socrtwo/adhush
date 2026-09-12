package io.adhush.core

import kotlin.math.max
import kotlin.math.min

/** Ported verbatim from config.FusionConfig. */
data class FusionConfig(
    val muteConfidence: Double = 0.72,
    val unmuteConfidence: Double = 0.45,
    val muteDwellMs: Int = 900,
    val unmuteDwellMs: Int = 400,
    val maxMuteS: Double = 240.0,
    val fpUnmuteDwellMs: Int = 3000,
)

/**
 * Weighted evidence over a constant normaliser, with Schmitt hysteresis.
 * Line-for-line port of detect/fusion.py: no single default-weight detector
 * can mute alone, and a fading vote can only add evidence, never dilute one.
 */
class Fusion(private val cfg: FusionConfig, weights: Map<String, Double>, enabled: Collection<String>) {
    private val weights = weights.toMap()
    private val norm: Double
    private var mutedSide = false

    init {
        val enabledMass = enabled.sumOf { weightFor(it) }
        norm = max(enabledMass / 2, MIN_MASS)
    }

    fun weightFor(detector: String): Double = weights[detector] ?: DEFAULT_WEIGHT

    /**
     * Votes from every detector that is *active* this tick. A detector that
     * cannot see (the camera with no screen in view) stays out of the list and
     * so out of the normaliser: it neither adds evidence nor dilutes it.
     */
    fun combine(votes: List<DetectorVote>, ts: Double): MuteDecision {
        val mass = votes.sumOf { weightFor(it.detector) * it.confidence }
        val present = votes.sumOf { weightFor(it.detector) }
        val n = if (present > 0.0) max(present / 2, MIN_MASS) else norm
        val confidence = min(1.0, mass / n)
        if (mutedSide) {
            if (confidence <= cfg.unmuteConfidence) mutedSide = false
        } else if (confidence >= cfg.muteConfidence) {
            mutedSide = true
        }
        val reasons = votes.sortedByDescending { it.confidence }
            .filter { it.confidence > REASON_EPS }
            .map { "${it.detector}:${it.reason}" }
        return MuteDecision(ts, mutedSide, confidence, reasons)
    }

    fun reset() { mutedSide = false }

    companion object {
        const val DEFAULT_WEIGHT = 0.15
        const val MIN_MASS = 2 * DEFAULT_WEIGHT
        const val REASON_EPS = 0.05
    }
}
