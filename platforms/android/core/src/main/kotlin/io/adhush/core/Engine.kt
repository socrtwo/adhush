package io.adhush.core

import kotlin.math.max

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
    /** What the camera can see right now ("bug seen", "whole TV not in view", …); null without a camera. */
    val camera: String? = null,
    /** What each AI judge last said, by detector name. */
    val judges: Map<String, String> = emptyMap(),
    /** Seconds left of the quiet period after "Not an ad", 0 when none. */
    val quietS: Double = 0.0,
    /** Seconds left of a timed manual duck, 0 when none. */
    val timedS: Double = 0.0,
    /** What the break clock thinks of this minute; null without the clock. */
    val clock: String? = null,
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
    /** After "Not an ad", no automatic duck for this long: the user's word outranks the detectors for a while. */
    private val notAdQuietS: Double = NOT_AD_QUIET_S,
    /** Wall-clock seconds, for the break clock; media time is not enough to know the minute of the hour. */
    private val wallClock: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    private enum class Source { FUSION, FINGERPRINT, USER, TIMED }
    /** A timed manual duck ends at this media time (ADR 0017). */
    private var timedUntil: Double? = null
    private var adStartWall = 0.0
    private var quietUntil = -1.0
    private var lastTs = 0.0

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

    private val transcript: TranscriptDetector? get() = detectors.firstOrNull { it is TranscriptDetector && it.name == "transcript" } as TranscriptDetector?

    private val captions: TranscriptDetector? get() = detectors.firstOrNull { it is TranscriptDetector && it.name == "captions" } as TranscriptDetector?

    private val judges: List<JudgeDetector> get() = detectors.filterIsInstance<JudgeDetector>()

    /** Recognised words from the phone's speech engine; the transcript detector and the judges see them. */
    @Synchronized fun onWords(words: List<Word>) { for (w in words) { transcript?.observeWord(w); for (j in judges) j.observeWord(w) } }

    /** Words read off the screen's caption band; the captions detector and the judges see them. */
    @Synchronized fun onCaptions(words: List<Word>) { for (w in words) { captions?.observeWord(w); for (j in judges) j.observeWord(w) } }

    /** Repetition learning over the recent transcript and captions; how many new scripts were found. */
    @Synchronized fun learnScriptsFromTranscript(): Int = (transcript?.learnFromHistory() ?: 0) + (captions?.learnFromHistory() ?: 0)

    private val logo: LogoAbsenceDetector? get() = detectors.firstOrNull { it is LogoAbsenceDetector } as LogoAbsenceDetector?

    private val clock: ClockDetector? get() = detectors.firstOrNull { it is ClockDetector } as ClockDetector?

    @Synchronized fun onAudio(block: AudioBlock) {
        for (d in detectors) d.observeAudio(block)
        fingerprint?.observeAudio(block)
        val ts = block.ts
        lastTs = ts
        clock?.tick(wallClock())
        // An inert detector (the camera with no whole screen in view) casts no vote and leaves the normaliser alone.
        val voting = detectors.filter { it.voting }
        val votes = voting.map { it.vote(ts) } + (fingerprint?.vote(ts)?.let { listOf(it) } ?: emptyList())
        val quiet = ts < quietUntil
        // The quiet period after "Not an ad": the evidence is still shown, but nothing acts on it.
        val decision = if (quiet) { fusion.reset(); MuteDecision(ts, false, 0.0, listOf("user:not_ad_quiet")) } else fusion.combine(votes, ts)
        lastDecision = decision
        // The AI judges ask their question off this thread when the others are unsure; a learned script reloads the matchers.
        for (j in judges) {
            j.hint(ts, decision.confidence, controllerMuted)
            if (j.scriptsDirty) { j.scriptsDirty = false; transcript?.refreshScripts(); captions?.refreshScripts() }
        }
        timedUntil?.let { if (ts >= it) { endTimed(ts); return } }
        if (override != Override.AUTO) return  // the user has taken the wheel

        val match = if (quiet) null else fingerprint?.activeMatch(ts)
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
                drive(true, ts)
                adStartTs = ts; adSource = source; activeAdId = match?.adId; adStartWall = wallClock()
            }
            Action.UNMUTE -> {
                drive(false, ts)
                val start = adStartTs; val src = adSource; val adId = activeAdId
                adStartTs = null; adSource = null; activeAdId = null; userHold = false
                // The break clock learns every real break; a timed manual duck teaches nothing.
                if (start != null && src != null && src != Source.TIMED) clock?.learn(adStartWall, wallClock())
                if (start != null && learner != null && fingerprint != null) {
                    val duration = ts - start
                    when (src) {
                        Source.FINGERPRINT -> adId?.let { if (matchKind(it) == AdKind.AD) learner.observeDuration(it, duration) }
                        Source.FUSION -> learner.learnSegment(start, duration, fingerprint.audioBetween(start, start + 60.0))
                        Source.USER -> {
                            learner.learnMaterial(start, ts, fingerprint.audioBetween(start, ts))?.let { fingerprint.holdOffAfterLearning(ts) }
                            transcript?.learnWindow(start, ts)   // the words of the break are a script too
                        }
                        Source.TIMED, null -> {}
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
     * Duck or restore the set, then tell the detectors what the mic will hear
     * next (ADR 0016). A command that throws leaves them untouched: the set
     * has not changed.
     */
    private fun drive(mute: Boolean, ts: Double) {
        if (mute) controller.mute() else controller.unmute()
        controllerMuted = mute
        for (d in detectors) d.audioDucked(ts, mute)
    }

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
     * A timed manual duck (ADR 0017): turn the set down now and back up after
     * [seconds], whatever the detectors say in between; nothing is learned.
     * Pressed while already ducked, it keeps the duck for that long instead.
     */
    @Synchronized fun duckFor(now: Double, seconds: Double): Boolean {
        if (override != Override.AUTO) return false
        if (machine.state == AdState.AD) { timedUntil = now + seconds; adSource = Source.TIMED; userHold = true; emit(); return true }
        val action = machine.userMute(now) ?: return false
        timedUntil = now + seconds
        userHold = true
        apply(action, now, 1.0, listOf("user:timed ${seconds.toInt()}"), Source.TIMED, null)
        return true
    }

    private fun endTimed(now: Double): Boolean {
        timedUntil = null
        val action = machine.cancelAd(now) ?: return false
        apply(action, now, 0.0, listOf("user:timed_end"), Source.TIMED, null)
        fusion.reset()
        return true
    }

    /**
     * "▶ Show's back": in teach mode, restore and learn the bracketed break. On an
     * automatic duck that overran, just restore — nothing is learned or forgotten.
     */
    @Synchronized fun showIsBack(now: Double): Boolean {
        if (timedUntil != null) return endTimed(now)
        if (userHold) {
            val action = machine.cancelAd(now) ?: return false
            apply(action, now, 0.0, listOf("user:show_back"), Source.USER, null)
            for (d in detectors) d.userSaysProgramme(now)
            return true
        }
        return standDown(now)
    }

    /** "✗ Not an ad": unmute now; a fingerprint that caused this is forgotten. */
    @Synchronized fun rejectAd(now: Double): Boolean {
        val adId = activeAdId; val src = adSource
        val action = machine.cancelAd(now)
        if (action == null) return false
        timedUntil = null
        drive(false, now)
        adStartTs = null; adSource = null; activeAdId = null; userHold = false
        if (src == Source.FINGERPRINT && adId != null) learner?.forget(adId)
        fingerprint?.abortMatch()
        fusion.reset()
        for (d in detectors) d.userSaysProgramme(now)
        quietUntil = now + notAdQuietS
        transitions.add(Transition(now, action, 0.0, listOf("user:reject")))
        emit()
        return true
    }

    /** The user touched the remote: leave AD without learning or forgetting anything. */
    @Synchronized fun standDown(now: Double): Boolean {
        val action = machine.cancelAd(now) ?: return false
        timedUntil = null
        controllerMuted = false   // the controller already stood down on its own
        for (d in detectors) d.audioDucked(now, false)
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
            Override.MUTE -> if (!controllerMuted) drive(true, now)
            Override.UNMUTE -> if (controllerMuted) drive(false, now)
            Override.AUTO -> {
                // Resync the transport with the machine's view.
                val want = machine.muted
                if (want != controllerMuted) drive(want, now)
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
        camera = logo?.describe(),
        judges = judges.associate { it.name to it.describe() },
        quietS = max(0.0, quietUntil - lastTs),
        timedS = timedUntil?.let { max(0.0, it - lastTs) } ?: 0.0,
        clock = clock?.describe(),
    )

    fun close() { runCatching { controller.close() } }

    private fun emit() { val s = status(); for (l in listeners) runCatching { l(s) } }

    companion object { const val NOT_AD_QUIET_S = 60.0 }
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
        transcript: TranscriptDetector? = null,
        captions: TranscriptDetector? = null,
        /** The AI judges (cloud and/or on the phone), each a detector in its own right. */
        judges: List<JudgeDetector> = emptyList(),
        /** The audio methods can be switched off one by one; at least one method must remain. */
        silence: Boolean = true,
        loudness: Boolean = true,
        fingerprints: Boolean = true,
        notAdQuietS: Double = Engine.NOT_AD_QUIET_S,
        /** The break clock (ADR 0017); a default-weight vote that tips the balance, never ducks alone. */
        clock: ClockDetector? = null,
        wallClock: () -> Double = { System.currentTimeMillis() / 1000.0 },
    ): Engine {
        val detectors = listOfNotNull<Detector>(if (silence) MicSilenceDetector() else null, if (loudness) LoudnessDetector() else null, logo, transcript, captions, clock) + judges
        require(detectors.isNotEmpty() || fingerprints) { "at least one method must be on" }
        val matcher = AudioMatcher(store, fpCfg)
        val fp = if (fingerprints) AudioFingerprintDetector(fpCfg, matcher) else null
        // The logo carries three default weights: absence alone mutes, and presence vetoes an audio-only duck.
        var w = if (logo != null && "logo_absence" !in weights) weights + ("logo_absence" to LOGO_WEIGHT) else weights
        if (transcript != null && "transcript" !in w) w = w + ("transcript" to LOGO_WEIGHT)   // a known script mutes alone, like a missing logo
        if (captions != null && "captions" !in w) w = w + ("captions" to LOGO_WEIGHT)         // and so does a known script read off the screen
        for (j in judges) if (j.name !in w) w = w + (j.name to LOGO_WEIGHT)                   // and an AI that says "commercial"
        if (logo != null && logo.name !in w) w = w + (logo.name to LOGO_WEIGHT)               // the ticker detector under its own name
        val fusion = Fusion(fusionCfg, w, detectors.map { it.name } + listOfNotNull(fp?.name))
        return Engine(detectors, fusion, AdStateMachine(fusionCfg), controller, fp, if (fp != null) AudioLearner(store, matcher, fpCfg) else null, store, notAdQuietS, wallClock)
    }

    const val LOGO_WEIGHT = 3 * Fusion.DEFAULT_WEIGHT
}
