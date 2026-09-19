package io.adhush.core

import java.util.Locale

/** What a judge (a language model, cloud or local) said about a stretch of transcript. */
data class Verdict(val commercial: Boolean, val confidence: Double, val reason: String) {
    companion object {
        /**
         * Parse the one line every judge is asked to answer with:
         * `COMMERCIAL 0.9: reason` or `SHOW 0.8: reason`. Tolerant of case,
         * extra prose, JSON-ish braces and a missing number (then 0.7).
         */
        fun parse(text: String): Verdict? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val upper = t.uppercase(Locale.US)
            val idxC = upper.indexOf("COMMERCIAL"); val idxS = upper.indexOf("SHOW"); val idxP = upper.indexOf("PROGRAM")
            val firstShow = listOf(idxS, idxP).filter { it >= 0 }.minOrNull()
            val commercial = when {
                idxC < 0 && firstShow == null -> return null
                idxC < 0 -> false
                firstShow == null -> true
                else -> idxC < firstShow
            }
            val keywordEnd = if (commercial) idxC + "COMMERCIAL".length else (firstShow ?: 0) + (if (firstShow == idxS) "SHOW".length else "PROGRAM".length)
            val number = Regex("""(0(?:\.\d+)?|1(?:\.0+)?)""").find(t.substring(keywordEnd))?.value?.toDoubleOrNull()
            val confidence = (number ?: 0.7).coerceIn(0.0, 1.0)
            val reason = t.substringAfter(":", "").trim().trim('"', '}', '{').take(120).ifEmpty { if (commercial) "sounds like a commercial" else "sounds like the show" }
            return Verdict(commercial, confidence, reason)
        }
    }
}

/** A judge reads a transcript and answers; blocking, may throw. The app supplies one per AI (cloud or on the phone). */
fun interface TranscriptJudge {
    fun judge(transcript: String, channel: String): Verdict?
}

data class JudgeConfig(
    /** How much recent transcript one question covers. */
    val windowS: Double = 40.0,
    /** In "always" mode, ask this often; in tie-breaker mode, this often while the decision is in doubt or the set is ducked. */
    val intervalS: Double = 10.0,
    /** In tie-breaker mode, ask at least this often even when nothing is in doubt (a slow sanity check). */
    val maxIntervalS: Double = 60.0,
    /** Ask only when the other methods are unsure (fusion confidence in the grey zone) or the set is ducked. */
    val tieBreaker: Boolean = true,
    val greyLow: Double = 0.2,
    val greyHigh: Double = 0.72,
    /** Do not ask about fewer words than this (an ad has not said anything yet). */
    val minWords: Int = 12,
    /** A "commercial" answer keeps the vote up this long; a new answer replaces it. */
    val holdS: Double = 15.0,
    /** A confident "commercial" answer over at least learnMinWords words is saved as a script, so the next airing is recognised offline for free. */
    val learnConfidence: Double = 0.85,
    val learnMinWords: Int = 15,
)

/**
 * The seventh and eighth methods. Words in (from speech or captions), a
 * judge's verdict out. Unlike the script methods it needs no memory of the
 * commercial: it asks a language model whether *these* words sound like
 * someone selling something. Asking costs money (cloud) or seconds of CPU
 * (local), so it asks on a cadence, by default only as a tie-breaker when
 * the other methods are unsure or the set is already ducked. The answer is
 * a vote with the logo weight, held for a few seconds, and a confident
 * "commercial" is saved as a script so the same spot is free next time.
 */
class JudgeDetector(
    override val name: String,
    private val cfg: JudgeConfig,
    private val judge: TranscriptJudge,
    /** Runs the (slow) question off the audio thread; the app hands in a thread. */
    private val runAsync: (Runnable) -> Unit,
    private val store: ScriptStore? = null,
    private val channel: String = "",
) : Detector {
    private val history = ArrayDeque<Word>()
    private var lastAskTs = 0.0
    private var wordsSinceAsk = 0
    private var inFlight = false
    private var holdUntil = -1.0
    private var lastTs = 0.0
    private var lastHint = 0.0
    private var lastMuted = false
    var lastVerdict: Verdict? = null
        private set
    var lastError: String? = null
        private set
    var asked = 0
        private set
    var learned = 0
        private set
    /** Set by the engine after a learned script so the script detectors reload. */
    var scriptsDirty = false

    override fun warmup() { history.clear(); holdUntil = -1.0; inFlight = false; wordsSinceAsk = 0 }
    override fun observeAudio(block: AudioBlock) { lastTs = block.ts }

    @Synchronized fun observeWord(raw: Word) {
        val text = normalizeWord(raw.text)
        if (text.isEmpty()) return
        history.addLast(Word(raw.ts, text)); wordsSinceAsk++
        while (history.isNotEmpty() && raw.ts - history.first().ts > 3 * cfg.windowS) history.removeFirst()
    }

    /** Every audio tick: what the other methods think, so a tie-breaker knows when it is needed. */
    @Synchronized fun hint(ts: Double, fusionConfidence: Double, muted: Boolean) {
        lastTs = ts; lastHint = fusionConfidence; lastMuted = muted
        if (inFlight || wordsSinceAsk < cfg.minWords) return
        val doubt = fusionConfidence in cfg.greyLow..cfg.greyHigh
        val interval = if (!cfg.tieBreaker || doubt || muted) cfg.intervalS else cfg.maxIntervalS
        if (ts - lastAskTs < interval) return
        val words = history.filter { it.ts >= ts - cfg.windowS }
        if (words.size < cfg.minWords) return
        lastAskTs = ts; wordsSinceAsk = 0; inFlight = true; asked++
        val text = words.joinToString(" ") { it.text }
        val askedAt = ts
        runAsync(Runnable {
            val verdict = try { judge.judge(text, channel) } catch (t: Throwable) { synchronized(this) { lastError = "${t.javaClass.simpleName}: ${t.message}" }; null }
            answer(verdict, words, askedAt)
        })
    }

    @Synchronized private fun answer(v: Verdict?, words: List<Word>, askedAt: Double) {
        inFlight = false
        if (v == null) return
        lastError = null
        lastVerdict = v
        if (v.commercial && v.confidence >= 0.5) {
            holdUntil = lastTs + cfg.holdS
            if (v.confidence >= cfg.learnConfidence && words.size >= cfg.learnMinWords && store != null) {
                val text = words.map { it.text }
                if (TranscriptMatcher(store).identify(text) == null) { store.add(text, words.last().ts - words.first().ts); learned++; scriptsDirty = true }
            }
        } else holdUntil = -1.0
    }

    /** "Not an ad": drop the held answer and do not ask again for a while. */
    @Synchronized override fun userSaysProgramme(ts: Double) { holdUntil = -1.0; lastAskTs = ts; lastVerdict = null }

    @Synchronized override fun vote(ts: Double): DetectorVote {
        val v = lastVerdict
        if (ts < holdUntil && v != null && v.commercial) return vote(ts, v.confidence, "judge commercial ${"%.2f".format(Locale.US, v.confidence)} ${v.reason.take(40)}")
        return vote(ts, 0.0, if (v == null) "judge quiet" else "judge show ${"%.2f".format(Locale.US, v.confidence)}")
    }

    /** One line for the status: what the AI last said. */
    @Synchronized fun describe(): String {
        lastError?.let { return "error: $it" }
        val v = lastVerdict ?: return if (asked == 0) "not asked yet" else "thinking…"
        return (if (v.commercial) "commercial" else "show") + " ${"%.0f".format(Locale.US, v.confidence * 100)}% — ${v.reason.take(48)}"
    }

    companion object {
        /** The question, shared by every judge so the cloud and the phone answer the same way. */
        fun prompt(channel: String): String = buildString {
            append("You are watching live television")
            if (channel.isNotBlank()) append(" on ").append(channel)
            append(". You will be given the last 40 seconds of what was said, as recognised by an imperfect speech-to-text engine (expect misheard words).\n")
            append("Decide whether a COMMERCIAL is playing right now (an advertisement for a product, service, drug, insurance, car, store, lawyer, charity appeal, or the network's own promo with a sales pitch), ")
            append("or the SHOW itself (news, interviews, discussion, weather, a reporter, sports, a movie or series). Anchors talking about a company or product in the news are the SHOW.\n")
            append("Answer with exactly one line and nothing else, in this form:\n")
            append("COMMERCIAL 0.9: two or three words why\n")
            append("or\n")
            append("SHOW 0.8: two or three words why\n")
            append("The number is your confidence from 0 to 1.")
        }
    }
}
