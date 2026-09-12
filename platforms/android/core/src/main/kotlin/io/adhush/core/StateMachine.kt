package io.adhush.core

/**
 * PROGRAM → SUSPECT_AD → AD → RECOVERY with asymmetric dwell and a hard
 * ceiling. Line-for-line port of state.py; see its docstring for why entering
 * AD requires full confidence at dwell completion and why RECOVERY ignores
 * promotion.
 */
class AdStateMachine(private val cfg: FusionConfig) {
    var state = AdState.PROGRAM
        private set
    private val muteDwell = DwellTimer(cfg.muteDwellMs / 1000.0)
    private val unmuteDwell = DwellTimer(cfg.unmuteDwellMs / 1000.0)
    private val fpUnmuteDwell = DwellTimer(cfg.fpUnmuteDwellMs / 1000.0)
    var adEnteredTs: Double? = null
        private set
    private var recoveryUntil: Double? = null

    val muted: Boolean get() = state == AdState.AD
    val muteDwellS: Double get() = cfg.muteDwellMs / 1000.0

    fun update(
        decision: MuteDecision,
        promote: Boolean = false,
        fpHold: Boolean = false,
        programEvidence: Boolean = false,
    ): Action? {
        val now = decision.ts
        if ((state == AdState.PROGRAM || state == AdState.SUSPECT_AD) && promote) {
            state = AdState.AD
            adEnteredTs = now
            muteDwell.reset(); unmuteDwell.reset(); fpUnmuteDwell.reset()
            return Action.MUTE
        }
        when (state) {
            AdState.PROGRAM -> {
                if (decision.mute) {
                    state = AdState.SUSPECT_AD
                    muteDwell.reset()
                    muteDwell.update(true, now)
                }
                return null
            }
            AdState.SUSPECT_AD -> {
                if (!decision.mute) {
                    state = AdState.PROGRAM
                    muteDwell.reset()
                    return null
                }
                if (muteDwell.update(true, now) && decision.confidence >= cfg.muteConfidence) {
                    state = AdState.AD
                    adEnteredTs = now
                    unmuteDwell.reset()
                    return Action.MUTE
                }
                return null
            }
            AdState.AD -> {
                val entered = adEnteredTs!!
                if (now - entered >= cfg.maxMuteS) return leaveAd(now)
                if (fpHold) {
                    unmuteDwell.reset()
                    if (fpUnmuteDwell.update(programEvidence, now)) return leaveAd(now)
                    return null
                }
                fpUnmuteDwell.reset()
                if (unmuteDwell.update(!decision.mute, now)) return leaveAd(now)
                return null
            }
            AdState.RECOVERY -> {
                if (now >= recoveryUntil!!) state = AdState.PROGRAM
                return null
            }
        }
    }

    /** User/API rejection: leave AD (or clear suspicion) immediately. */
    fun cancelAd(now: Double): Action? {
        if (state == AdState.AD) return leaveAd(now)
        if (state == AdState.SUSPECT_AD) { state = AdState.PROGRAM; muteDwell.reset() }
        return null
    }

    private fun leaveAd(now: Double): Action {
        state = AdState.RECOVERY
        recoveryUntil = now + RECOVERY_S
        adEnteredTs = null
        muteDwell.reset(); unmuteDwell.reset(); fpUnmuteDwell.reset()
        return Action.UNMUTE
    }

    companion object { const val RECOVERY_S = 2.0 }
}
