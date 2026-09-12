package io.adhush.core

/**
 * Rolling chroma blocks over the live audio, fed to the audio-primary matcher.
 * The Android counterpart of detect/fingerprint.py, minus video: a confirmed
 * match is the active match the engine promotes on, and the rolling buffer is
 * the learner's source material when a fusion-driven ad ends.
 */
class AudioFingerprintDetector(private val cfg: FingerprintConfig, private val matcher: AudioMatcher) : Detector {
    override val name = "fingerprint"
    private val buffer = ArrayDeque<Pair<Double, Int>>()  // (ts, chroma bits) per sampleInterval
    private val pending = ArrayList<FloatArray>()
    private var pendingN = 0
    private var blockStart: Double? = null
    private var active: Match? = null
    private var quietUntil = 0.0
    private val liveWindow: Int get() = (cfg.windowS / cfg.sampleIntervalS).toInt()

    override fun warmup() { buffer.clear(); pending.clear(); pendingN = 0; blockStart = null; active = null; matcher.reset() }

    override fun observeAudio(block: AudioBlock) {
        var start = blockStart ?: block.ts
        pending.add(block.samples); pendingN += block.samples.size
        val need = Math.round(cfg.sampleIntervalS * block.sampleRate).toInt()
        while (pendingN >= need) {
            val all = FloatArray(pendingN); var o = 0
            for (p in pending) { System.arraycopy(p, 0, all, o, p.size); o += p.size }
            val head = all.copyOfRange(0, need); val rest = all.copyOfRange(need, all.size)
            buffer.addLast(Pair(start, Chroma.chromaBits(head, block.sampleRate)))
            while (buffer.size > (BUFFER_S / cfg.sampleIntervalS).toInt()) buffer.removeFirst()
            start += need.toDouble() / block.sampleRate
            pending.clear(); if (rest.isNotEmpty()) pending.add(rest); pendingN = rest.size
            onSample(start)
        }
        blockStart = start
    }

    /**
     * After learning material that ends now, the live window still holds its
     * tail: without this, the freshly learned break would be "recognised" at
     * once and duck the set again.
     */
    fun holdOffAfterLearning(ts: Double) { quietUntil = ts + cfg.windowS + cfg.sampleIntervalS; active = null; matcher.reset() }

    private fun onSample(ts: Double) {
        if (ts < quietUntil) return
        val live = buffer.takeLast(liveWindow)
        val a = active
        if (a != null) {
            if (ts >= a.expectedEndTs) { active = null; matcher.reset() }
            else if (a.kind == AdKind.MATERIAL) {
                // Rolling hold: keep the duck while the live audio agrees with the
                // record at this alignment; when it stops (the next spot in a
                // different order, or the show is back), try to re-anchor on any
                // known material; otherwise let the grace run out.
                val (agrees, agreement) = matcher.stillAgrees(a.adId, a.estStartTs, live)
                if (agrees) { active = a.copy(expectedEndTs = ts + cfg.materialGraceS, agreement = agreement); return }
                matcher.identify(live)?.let { (adId, est) ->
                    // Only if the *recent* audio agrees too: the window's tail alone must not re-find the spot that just ended.
                    val (recentAgrees, agreement) = matcher.stillAgrees(adId, est, live)
                    if (recentAgrees) active = matcher.match(adId, est, ts, agreement)
                }
                return
            } else return
        }
        active = matcher.feed(ts, live)
    }

    fun activeMatch(ts: Double): Match? {
        active?.let { if (ts >= it.expectedEndTs) { active = null; matcher.reset() } }
        return active
    }

    fun abortMatch() { active = null; matcher.reset() }

    fun audioBetween(t0: Double, t1: Double): List<Pair<Double, Int>> = buffer.filter { it.first in t0..t1 }

    override fun vote(ts: Double): DetectorVote {
        val m = activeMatch(ts) ?: return vote(ts, 0.0, "no_fp")
        return vote(ts, 1.0, "fp_hit ad=${m.adId} dur=${m.durationS.toInt()} end=${"%.1f".format(m.expectedEndTs)} agree=${"%.2f".format(m.agreement)}")
    }

    /** Long enough to hold a whole taught break (the state machine's 240 s ceiling) plus slack. */
    companion object { const val BUFFER_S = 300.0 }
}

enum class Override(val wire: String) { AUTO("auto"), MUTE("mute"), UNMUTE("unmute") }

data class Transition(val ts: Double, val action: Action, val confidence: Double, val reasons: List<String>)

data class Status(
    val state: AdState,
    val muted: Boolean,
    val override: Override,
    val confidence: Double,
    val detectors: List<String>,
    val reasons: List<String>,
    val adsLearned: Int,
    /** Teach mode: the user pressed "Is an ad" and has not yet pressed "Show's back". */
    val teaching: Boolean = false,
)

/**
 * Wires blocks → detectors → fusion → state machine → controller, the way
 * engine.Pipeline does, for an audio-only, mic-fed core. Learning and
 * promotion follow the Python engine: a fusion-driven ad is learned when it
 * ends; a fingerprint-driven one folds its observed airing into the duration.
 */
class Engine(
    private val detectors: List<Detector>,
    private val fusion: Fusion,
    private val machine: AdStateMachine,
    private val controller: MuteController,
    private val fingerprint: AudioFingerprintDetector? = null,
    private val learner: AudioLearner? = null,
    private val store: FingerprintStore? = null,
) {
    private enum class Source { FUSION, FINGERPRINT, USER }

    @Volatile var override: Override = Override.AUTO
        private set
    private var lastDecision: MuteDecision? = null
    private var adStartTs: Double? = null
    private var adSource: Source? = null
    private var activeAdId: Int? = null
    private var controllerMuted = false
    /** Teach mode: hold the duck until "Show's back" (or the ceiling), then learn the whole break. */
    private var userHold = false
    val transitions = ArrayList<Transition>()
    private val listeners = ArrayList<(Status) -> Unit>()

    fun addListener(l: (Status) -> Unit) { listeners.add(l) }

    /** A camera frame (luma). Detectors that watch the screen update; the vote happens on the next audio tick. */
    @Synchronized fun onFrame(frame: Gray, ts: Double) { for (d in detectors) d.observeFrame(frame, ts) }

    private val logo: LogoAbsenceDetector? get() = detectors.firstOrNull { it is LogoAbsenceDetector } as LogoAbsenceDetector?

    @Synchronized fun onAudio(block: AudioBlock) {
        for (d in detectors) d.observeAudio(block)
        fingerprint?.observeAudio(block)
        val ts = block.ts
        // An inert logo detector (no screen in view) casts no vote and leaves the normaliser alone.
        val voting = detectors.filter { (it as? LogoAbsenceDetector)?.active != false }
        val votes = voting.map { it.vote(ts) } + (fingerprint?.vote(ts)?.let { listOf(it) } ?: emptyList())
        val decision = fusion.combine(votes, ts)
        lastDecision = decision
        if (override != Override.AUTO) return  // the user has taken the wheel

        val match = fingerprint?.activeMatch(ts)
        val fpHold = match != null  // activeMatch() already dropped expired ones
        val promote = fpHold && (machine.state == AdState.PROGRAM || machine.state == AdState.SUSPECT_AD)
        // A user hold behaves like a fingerprint hold with no programme evidence: only the ceiling ends it.
        // A fingerprint hold ends early when the logo is visibly back — presence is proof of programme.
        val programEvidence = !userHold && (logo?.programPresent == true)
        val action = machine.update(decision, promote = promote, fpHold = fpHold || userHold, programEvidence = programEvidence) ?: return
        val reasons = if (promote) listOf("fingerprint:promote ad=${match!!.adId} dur=${match.durationS.toInt()}") + decision.reasons else decision.reasons
        apply(action, ts, decision.confidence, reasons, if (promote) Source.FINGERPRINT else Source.FUSION, match)
    }

    private fun apply(action: Action, ts: Double, confidence: Double, reasons: List<String>, source: Source, match: Match?) {
        when (action) {
            Action.MUTE -> {
                controller.mute(); controllerMuted = true
                adStartTs = ts; adSource = source; activeAdId = match?.adId
            }
            Action.UNMUTE -> {
                controller.unmute(); controllerMuted = false
                val start = adStartTs; val src = adSource; val adId = activeAdId
                adStartTs = null; adSource = null; activeAdId = null; userHold = false
                if (start != null && learner != null && fingerprint != null) {
                    val duration = ts - start
                    when (src) {
                        Source.FINGERPRINT -> adId?.let { if (matchKind(it) == AdKind.AD) learner.observeDuration(it, duration) }
                        Source.FUSION -> learner.learnSegment(start, duration, fingerprint.audioBetween(start, start + 60.0))
                        Source.USER -> learner.learnMaterial(start, ts, fingerprint.audioBetween(start, ts))?.let { fingerprint.holdOffAfterLearning(ts) }
                        null -> {}
                    }
                }
                fingerprint?.abortMatch()
            }
        }
        transitions.add(Transition(ts, action, confidence, reasons))
        emit()
    }

    private fun matchKind(adId: Int): AdKind = store?.get(adId)?.kind ?: AdKind.AD

    /**
     * "✓ Is an ad" — teach mode: duck now and stay ducked, whatever the detectors
     * say, until "Show's back" or the ceiling; then learn everything heard in
     * between as material.
     */
    @Synchronized fun confirmAd(now: Double): Boolean {
        if (machine.state == AdState.AD) { adSource = Source.USER; userHold = true; emit(); return true }
        // Promote through the machine so dwell/ceiling bookkeeping stays consistent.
        val decision = lastDecision ?: MuteDecision(now, true, 1.0, listOf("user:confirm"))
        val action = machine.update(decision.copy(ts = now), promote = true) ?: return false
        userHold = true
        apply(action, now, 1.0, listOf("user:confirm"), Source.USER, null)
        return true
    }

    /**
     * "▶ Show's back": in teach mode, restore and learn the bracketed break. On an
     * automatic duck that overran, just restore — nothing is learned or forgotten.
     */
    @Synchronized fun showIsBack(now: Double): Boolean {
        if (userHold) {
            val action = machine.cancelAd(now) ?: return false
            apply(action, now, 0.0, listOf("user:show_back"), Source.USER, null)
            return true
        }
        return standDown(now)
    }

    /** "✗ Not an ad": unmute now; a fingerprint that caused this is forgotten. */
    @Synchronized fun rejectAd(now: Double): Boolean {
        val adId = activeAdId; val src = adSource
        val action = machine.cancelAd(now)
        if (action == null) return false
        controller.unmute(); controllerMuted = false
        adStartTs = null; adSource = null; activeAdId = null; userHold = false
        if (src == Source.FINGERPRINT && adId != null) learner?.forget(adId)
        fingerprint?.abortMatch()
        fusion.reset()
        transitions.add(Transition(now, action, 0.0, listOf("user:reject")))
        emit()
        return true
    }

    /** The user touched the remote: leave AD without learning or forgetting anything. */
    @Synchronized fun standDown(now: Double): Boolean {
        val action = machine.cancelAd(now) ?: return false
        controllerMuted = false   // the controller already stood down on its own
        adStartTs = null; adSource = null; activeAdId = null; userHold = false
        fingerprint?.abortMatch()
        fusion.reset()
        transitions.add(Transition(now, action, 0.0, listOf("user:remote")))
        emit()
        return true
    }

    @Synchronized fun setOverride(mode: Override, now: Double) {
        override = mode
        when (mode) {
            Override.MUTE -> if (!controllerMuted) { controller.mute(); controllerMuted = true }
            Override.UNMUTE -> if (controllerMuted) { controller.unmute(); controllerMuted = false }
            Override.AUTO -> {
                // Resync the transport with the machine's view.
                val want = machine.muted
                if (want != controllerMuted) { if (want) controller.mute() else controller.unmute(); controllerMuted = want }
            }
        }
        emit()
    }

    @Synchronized fun status(): Status = Status(
        state = machine.state,
        muted = controllerMuted,
        override = override,
        confidence = lastDecision?.confidence ?: 0.0,
        teaching = userHold,
        detectors = detectors.map { it.name } + (fingerprint?.let { listOf(it.name) } ?: emptyList()),
        reasons = lastDecision?.reasons ?: emptyList(),
        adsLearned = store?.count() ?: 0,
    )

    fun close() { runCatching { controller.close() } }

    private fun emit() { val s = status(); for (l in listeners) runCatching { l(s) } }
}

/** The standard audio-only assembly; the app and the tests both build it this way. */
object Assembly {
    fun engine(
        controller: MuteController,
        store: FingerprintStore = FileFingerprintStore(),
        fusionCfg: FusionConfig = FusionConfig(),
        fpCfg: FingerprintConfig = FingerprintConfig(),
        weights: Map<String, Double> = emptyMap(),
        logo: LogoAbsenceDetector? = null,
    ): Engine {
        val detectors = listOf<Detector>(MicSilenceDetector(), LoudnessDetector()) + (logo?.let { listOf<Detector>(it) } ?: emptyList())
        val matcher = AudioMatcher(store, fpCfg)
        val fp = AudioFingerprintDetector(fpCfg, matcher)
        // The logo carries three default weights: absence alone mutes, and presence vetoes an audio-only duck.
        val w = if (logo != null && "logo_absence" !in weights) weights + ("logo_absence" to LOGO_WEIGHT) else weights
        val fusion = Fusion(fusionCfg, w, detectors.map { it.name } + fp.name)
        return Engine(detectors, fusion, AdStateMachine(fusionCfg), controller, fp, AudioLearner(store, matcher, fpCfg), store)
    }

    const val LOGO_WEIGHT = 3 * Fusion.DEFAULT_WEIGHT
}
