package io.adhush.core

import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** One recognised word with the media time it was heard at. */
data class Word(val ts: Double, val text: String)

/** Lower-case letters and digits only; what an offline recogniser gives, made comparable. */
fun normalizeWord(w: String): String = w.lowercase(Locale.US).filter { it.isLetterOrDigit() }

/** A commercial as words: what repetition (or the user) said an ad sounds like. */
data class Script(val id: Int, val words: List<String>, val durationS: Double, val sampleCount: Int)

interface ScriptStore {
    fun add(words: List<String>, durationS: Double): Int
    fun all(): List<Script>
    fun bump(id: Int, durationS: Double)
    fun remove(id: Int)
    fun count(): Int
}

/** TSV behind an in-memory map, like the fingerprint store. */
class FileScriptStore(private val file: File? = null) : ScriptStore {
    private val scripts = LinkedHashMap<Int, Script>()
    private var nextId = 1
    init { file?.takeIf { it.isFile }?.forEachLine { line ->
        val p = line.split('\t')
        if (p[0] == "script" && p.size >= 5) { val id = p[1].toInt(); scripts[id] = Script(id, p[4].split(' ').filter { it.isNotEmpty() }, p[2].toDouble(), p[3].toInt()); if (id >= nextId) nextId = id + 1 }
    } }
    @Synchronized override fun add(words: List<String>, durationS: Double): Int { val id = nextId++; scripts[id] = Script(id, words, durationS, 1); persist(); return id }
    @Synchronized override fun all(): List<Script> = scripts.values.toList()
    @Synchronized override fun bump(id: Int, durationS: Double) { scripts[id]?.let { scripts[id] = it.copy(durationS = (it.durationS * it.sampleCount + durationS) / (it.sampleCount + 1), sampleCount = it.sampleCount + 1) }; persist() }
    @Synchronized override fun remove(id: Int) { scripts.remove(id); persist() }
    @Synchronized override fun count(): Int = scripts.size
    private fun persist() {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.bufferedWriter().use { w -> w.write("# adhush scripts v1\n"); for (s in scripts.values) w.write("script\t${s.id}\t${s.durationS}\t${s.sampleCount}\t${s.words.joinToString(" ")}\n") }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}

data class TranscriptConfig(
    /** Consecutive words that make one index key; a recogniser error breaks one key, not the match. */
    val n: Int = 4,
    /** Aligned key hits needed before a live match is confirmed (≈ n + confirmHits − 1 words in a row). */
    val confirmHits: Int = 3,
    /** Keep the duck this long after the last aligned hit (ads have music and pauses without words). */
    val graceS: Double = 8.0,
    /** A repeated run must be at least this many words to be a commercial, not a catchphrase. */
    val minRepeatWords: Int = 10,
    /** Two airings must be at least this far apart to count as a repeat. */
    val minRepeatGapS: Double = 120.0,
    /** Boilerplate phrases hold the duck this long on their own. */
    val boilerplateHoldS: Double = 15.0,
    /** How much transcript to keep for repetition learning. */
    val historyS: Double = 3 * 3600.0,
)

/**
 * Phrases that occur in commercials and essentially never in programming:
 * the legally required and direct-response language. Matched as a run of
 * normalised words anywhere in the recent transcript.
 */
val BOILERPLATE: List<List<String>> = listOf(
    "side effects may include", "ask your doctor", "tell your doctor", "do not take if", "talk to your doctor",
    "individual results may vary", "results may vary", "call now", "order now", "offer ends", "while supplies last",
    "terms and conditions apply", "not available in all states", "restrictions apply", "see store for details",
    "visit us online", "call today", "for a limited time", "money back guarantee",
).map { it.split(' ').map(::normalizeWord) }

/**
 * Finds commercials in a transcript by repetition: index every n-gram with
 * its time; an n-gram heard again minutes later starts a run; consecutive
 * aligned n-grams extend it; runs of at least minRepeatWords are scripts.
 * Overlaps with known scripts fold into them instead of duplicating.
 */
class RepeatLearner(private val store: ScriptStore, private val cfg: TranscriptConfig = TranscriptConfig()) {
    /** Returns the ids of scripts newly added from [words] (sorted by time). */
    fun learn(words: List<Word>): List<Int> {
        val n = cfg.n
        if (words.size < 2 * cfg.minRepeatWords) return emptyList()
        val keys = HashMap<String, MutableList<Int>>()
        for (i in 0..words.size - n) keys.getOrPut((i until i + n).joinToString(" ") { words[it].text }) { ArrayList() }.add(i)
        val used = BooleanArray(words.size)
        val added = ArrayList<Int>()
        val existing = TranscriptMatcher(store, cfg)
        var i = 0
        while (i <= words.size - n) {
            if (used[i]) { i++; continue }
            val key = (i until i + n).joinToString(" ") { words[it].text }
            val later = keys[key]?.firstOrNull { j -> j > i && words[j].ts - words[i].ts >= cfg.minRepeatGapS && !used[j] }
            if (later == null) { i++; continue }
            // Extend while both copies keep agreeing word for word (allow single-word slips).
            var len = n; var slips = 0
            while (i + len < later && later + len < words.size) {
                if (words[i + len].text == words[later + len].text) { len++; slips = 0 }
                else if (slips < 1 && i + len + 1 < later && later + len + 1 < words.size && words[i + len + 1].text == words[later + len + 1].text) { len += 2; slips++ }
                else break
            }
            if (len >= cfg.minRepeatWords) {
                val run = words.subList(i, i + len).map { it.text }
                val duration = words[i + len - 1].ts - words[i].ts
                // Every other airing of this run, anywhere later: each one is a sample.
                val copies = (keys[key] ?: emptyList<Int>()).filter { j -> j > i && !used[j] && words[j].ts - words[i].ts >= cfg.minRepeatGapS && agrees(words, i, j, len) }
                val known = existing.identify(run)
                val id = if (known != null) { store.bump(known, duration); known } else { store.add(run, duration).also { added.add(it); existing.refresh() } }
                repeat(copies.size - 1) { store.bump(id, duration) }
                for (k in i until i + len) used[k] = true
                for (j in copies) for (k in j until min(words.size, j + len)) used[k] = true
                i += len
            } else i++
        }
        return added
    }

    /** Do the runs at [a] and [b] agree for [len] words, allowing single-word slips? */
    private fun agrees(words: List<Word>, a: Int, b: Int, len: Int): Boolean {
        if (b + len > words.size) return false
        var bad = 0
        for (k in 0 until len) if (words[a + k].text != words[b + k].text && ++bad > len / 5) return false
        return true
    }
}

/**
 * Live matching of recognised words against the scripts, the way the audio
 * matcher aligns chroma blocks: an inverted index of n-grams, a tally of
 * (script, offset) for the last few words, a match once confirmHits keys
 * agree on one offset. The held match rolls forward with each aligned hit.
 */
class TranscriptMatcher(private val store: ScriptStore, private val cfg: TranscriptConfig = TranscriptConfig()) {
    private class Entry(val id: Int, val pos: Int)
    private var index = HashMap<String, MutableList<Entry>>()
    private var scripts = HashMap<Int, Script>()
    private val recent = ArrayDeque<Word>()
    private var candidate: Pair<Int, Int>? = null   // (id, alignment = wordIndexInLive - posInScript)
    private var streak = 0
    private var liveCount = 0

    init { refresh() }

    fun refresh() {
        val ni = HashMap<String, MutableList<Entry>>(); val ns = HashMap<Int, Script>()
        for (s in store.all()) { ns[s.id] = s; for (p in 0..s.words.size - cfg.n) ni.getOrPut(s.words.subList(p, p + cfg.n).joinToString(" ")) { ArrayList() }.add(Entry(s.id, p)) }
        index = ni; scripts = ns
    }

    fun reset() { candidate = null; streak = 0 }

    /** Which script does this run of words belong to (any offset), by most aligned n-gram hits? */
    fun identify(run: List<String>): Int? {
        val tally = HashMap<Pair<Int, Int>, Int>()
        for (i in 0..run.size - cfg.n) for (e in index[run.subList(i, i + cfg.n).joinToString(" ")] ?: continue) {
            val k = Pair(e.id, i - e.pos); tally[k] = (tally[k] ?: 0) + 1
        }
        val best = tally.maxByOrNull { it.value } ?: return null
        return if (best.value >= cfg.confirmHits) best.key.first else null
    }

    /** Feed one live word; returns a confirmed (script, estimated start ts) when the streak completes, else null. */
    fun feed(word: Word): Pair<Script, Double>? {
        recent.addLast(word); liveCount++
        while (recent.size > cfg.n) recent.removeFirst()
        if (recent.size < cfg.n) return null
        val key = recent.joinToString(" ") { it.text }
        val entries = index[key] ?: run { if (++missStreak > 2 * cfg.n) reset(); return null }
        missStreak = 0
        var hit: Pair<Int, Int>? = null
        for (e in entries) {
            val align = (liveCount - cfg.n) - e.pos
            val c = candidate
            if (c != null && c.first == e.id && kotlin.math.abs(c.second - align) <= 1) { hit = c; break }
            if (hit == null) hit = Pair(e.id, align)
        }
        val h = hit ?: return null
        if (candidate != null && candidate == h) streak++ else { candidate = h; streak = 1 }
        if (streak < cfg.confirmHits) return null
        val s = scripts[h.first] ?: return null
        // Estimated start: this word's time minus the script offset of its n-gram, in words → seconds via the script's rate.
        val pos = (liveCount - cfg.n) - h.second
        val rate = if (s.words.size > 1) s.durationS / (s.words.size - 1) else 0.4
        return Pair(s, word.ts - max(0, pos) * rate)
    }
    private var missStreak = 0
}

/**
 * The fifth detector. Words in, votes out: 1.0 while a known script or a
 * boilerplate phrase is being heard (rolling grace after the last hit), 0
 * otherwise. Keeps the recent transcript for repetition learning and for the
 * teach-mode window. Never sees audio itself; the phone's recogniser feeds it.
 */
class TranscriptDetector(
    private val store: ScriptStore,
    private val cfg: TranscriptConfig = TranscriptConfig(),
    private val boilerplate: List<List<String>> = BOILERPLATE,
) : Detector {
    override val name = "transcript"
    private val matcher = TranscriptMatcher(store, cfg)
    private val learner = RepeatLearner(store, cfg)
    private val history = ArrayDeque<Word>()
    private var holdUntil = -1.0
    private var heldScript: Script? = null
    @Suppress("unused") private var heldStart = 0.0
    var lastReason = "quiet"
        private set
    var wordsHeard = 0L
        private set

    override fun warmup() { matcher.reset(); holdUntil = -1.0; heldScript = null }
    override fun observeAudio(block: AudioBlock) {}

    val transcript: List<Word> get() = history.toList()

    @Synchronized fun observeWord(raw: Word) {
        val text = normalizeWord(raw.text)
        if (text.isEmpty()) return
        val w = Word(raw.ts, text)
        history.addLast(w); wordsHeard++
        while (history.isNotEmpty() && w.ts - history.first().ts > cfg.historyS) history.removeFirst()
        // Only an *aligned* hit extends the grace: unrelated words while held must let it lapse.
        matcher.feed(w)?.let { (s, start) ->
            heldScript = s; heldStart = start
            holdUntil = max(holdUntil, w.ts + cfg.graceS)
            lastReason = "script id=${s.id} words=${s.words.size}"
        }
        if (boilerplateEndsHere()) { holdUntil = max(holdUntil, w.ts + cfg.boilerplateHoldS); lastReason = "boilerplate" }
    }

    private fun boilerplateEndsHere(): Boolean {
        val tail = history.takeLast(6).map { it.text }
        for (phrase in boilerplate) if (tail.size >= phrase.size && tail.subList(tail.size - phrase.size, tail.size) == phrase) return true
        return false
    }

    /** Teach mode: everything heard in the bracketed break is a script. */
    @Synchronized fun learnWindow(startTs: Double, endTs: Double): Int? {
        val words = history.filter { it.ts in startTs..endTs }.map { it.text }
        if (words.size < cfg.minRepeatWords) return null
        val id = store.add(words, endTs - startTs); matcher.refresh(); return id
    }

    /** Run repetition learning over what has been heard; returns how many new scripts were found. */
    @Synchronized fun learnFromHistory(): Int {
        val added = learner.learn(history.toList())
        if (added.isNotEmpty()) matcher.refresh()
        return added.size
    }

    @Synchronized override fun vote(ts: Double): DetectorVote {
        if (ts < holdUntil) return vote(ts, 1.0, lastReason)
        heldScript = null
        return vote(ts, 0.0, "quiet")
    }
}
