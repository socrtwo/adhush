package io.adhush.core

import java.io.File
import java.util.Calendar
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Break jingles (ADR 0020, after AdVent): the short sting a channel plays
 * going into and out of every break. Instead of one fingerprint per
 * commercial, one per channel covers every break for good. Nothing is
 * hard-coded: every break the engine ends contributes the seconds around
 * its start (an opener) and its end (a closer) as *candidates*; a candidate
 * that turns out to open several different breaks is promoted to a jingle
 * and starts voting. An opener heard live ducks the set on its own (the
 * logo weight); a closer heard while ducked is programme evidence, which
 * ends a break as fast as a returning logo does.
 *
 * Families and hours (ADR 0027): a channel re-cuts its sting per show, so a
 * sting is also recognised transposed by a few semitones (the twelve chroma
 * bits rotate) or played a little faster or slower (the stored blocks
 * stretch), at a higher bar; a variant hit counts for the jingle it
 * resembles. Every jingle also remembers the local hours it opened breaks
 * in: heard again at one of those hours it is trusted a break earlier and
 * matched a little more loosely.
 */
enum class JingleKind(val wire: String) { OPEN("open"), CLOSE("close");
    companion object { fun of(s: String) = entries.firstOrNull { it.wire == s } ?: OPEN }
}

class Jingle(val id: Int, val kind: JingleKind, val blocks: List<Int>, var hits: Int, var falseHits: Int, val createdTs: Double,
             /** Local hours of day this sting opened (or closed) a break in. */
             val hours: MutableSet<Int> = HashSet())

/** The chroma word of the same block transposed up by [semitones] (down when negative): class c lives in bit 11 - c. */
fun pitchRotate(bits: Int, semitones: Int): Int {
    val k = ((semitones % Chroma.BITS) + Chroma.BITS) % Chroma.BITS
    val mask = (1 shl Chroma.BITS) - 1
    if (k == 0) return bits and mask
    return ((bits ushr k) or (bits shl (Chroma.BITS - k))) and mask
}

/** The same sting played at 1 / [factor] speed: 1.1 is ten per cent slower. */
fun tempoStretch(blocks: List<Int>, factor: Double): List<Int> {
    val n = max(1, (blocks.size * factor).roundToInt())
    return List(n) { i -> blocks[minOf(blocks.size - 1, (i / factor).toInt())] }
}

fun localHour(wall: Double): Int = Calendar.getInstance().apply { timeInMillis = (wall * 1000).toLong() }.get(Calendar.HOUR_OF_DAY)

interface JingleStore {
    fun load(): List<Jingle>
    fun save(jingles: List<Jingle>)
}

class FileJingleStore(private val file: File? = null) : JingleStore {
    override fun load(): List<Jingle> {
        val f = file?.takeIf { it.isFile } ?: return emptyList()
        val out = ArrayList<Jingle>()
        f.forEachLine { line ->
            val p = line.split('\t')
            if (p[0] == "j" && p.size >= 7) runCatching {
                val hours = if (p.size > 7) p[7].split(',').filter { it.isNotEmpty() }.map { it.toInt() }.toHashSet() else HashSet()  // v1 rows carry none
                out.add(Jingle(p[1].toInt(), JingleKind.of(p[2]), p[6].split(',').filter { it.isNotEmpty() }.map { it.toInt() }, p[3].toInt(), p[4].toInt(), p[5].toDouble(), hours))
            }
        }
        return out
    }
    override fun save(jingles: List<Jingle>) {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            w.write("# adhush jingles v2\tid\tkind\thits\tfalse_hits\tcreated\tblocks\thours\n")
            for (j in jingles) w.write("j\t${j.id}\t${j.kind.wire}\t${j.hits}\t${j.falseHits}\t${j.createdTs}\t${j.blocks.joinToString(",")}\t${j.hours.sorted().joinToString(",")}\n")
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}

data class JingleConfig(
    val sampleIntervalS: Double = 0.5,
    /** AdVent measured three seconds as enough to recognise a sting. */
    val jingleS: Double = 3.0,
    /** A candidate keeps a wider window than the sting, so the sting can sit anywhere in it. */
    val candidateS: Double = 6.0,
    /** Aligned chroma-bit agreement that counts as the same sting (0.5 is chance). */
    val minAgreement: Double = 0.72,
    /** Distinct breaks a candidate must open (or close) before it votes. */
    val promoteHits: Int = 3,
    /** An opener's vote lasts this long: the first seconds of the break. */
    val holdS: Double = 20.0,
    /** A closer counts as programme evidence for this long. */
    val closeHoldS: Double = 10.0,
    /** Rolling audio kept, so a break's opener can still be cut out when the break ends. */
    val historyS: Double = 400.0,
    val maxCandidates: Int = 80,
    /** ADR 0027, families: a sting is also recognised transposed by up to this many semitones either way ... */
    val pitchShifts: Int = 2,
    /** ... and stretched by up to this fraction ... */
    val tempoTolerance: Double = 0.1,
    /** ... but a transposed or stretched match must clear a higher bar. */
    val familyPenalty: Double = 0.05,
    /** ADR 0027, hours: at an hour the sting has opened breaks in before, it is matched this much more loosely. */
    val hourBonus: Double = 0.03,
)

class JingleDetector(private val store: JingleStore, private val cfg: JingleConfig = JingleConfig()) : Detector {
    override val name = "jingle"
    private val ring = ArrayDeque<Pair<Double, Int>>()   // (ts, chroma bits) per sampleInterval
    private val pending = ArrayList<FloatArray>()
    private var pendingN = 0
    private var blockStart: Double? = null
    private val jingles = ArrayList<Jingle>()
    private var nextId = 1
    private var pendingClose: Double? = null
    private var openHitTs: Double? = null
    private var openHitId: Int? = null
    private var closeHitTs: Double? = null
    private var lastAgreement = 0.0
    private var lastVariant = ""
    private var lastTs = 0.0
    private var hour: Int? = null
    private var pendingCloseHour: Int? = null
    private val stingBlocks: Int get() = max(2, (cfg.jingleS / cfg.sampleIntervalS).toInt())
    private val candidateBlocks: Int get() = max(stingBlocks, (cfg.candidateS / cfg.sampleIntervalS).toInt())

    init { store.load().forEach { jingles.add(it); if (it.id >= nextId) nextId = it.id + 1 } }

    override fun warmup() { ring.clear(); pending.clear(); pendingN = 0; blockStart = null; pendingClose = null; openHitTs = null; closeHitTs = null }

    override fun observeAudio(block: AudioBlock) {
        var start = blockStart ?: block.ts
        pending.add(block.samples); pendingN += block.samples.size
        val need = Math.round(cfg.sampleIntervalS * block.sampleRate).toInt()
        while (pendingN >= need) {
            val all = FloatArray(pendingN); var o = 0
            for (p in pending) { System.arraycopy(p, 0, all, o, p.size); o += p.size }
            val head = all.copyOfRange(0, need); val rest = all.copyOfRange(need, all.size)
            ring.addLast(Pair(start, Chroma.chromaBits(head, block.sampleRate)))
            while (ring.size > (cfg.historyS / cfg.sampleIntervalS).toInt()) ring.removeFirst()
            start += need.toDouble() / block.sampleRate
            pending.clear(); if (rest.isNotEmpty()) pending.add(rest); pendingN = rest.size
            onSample(start)
        }
        blockStart = start
    }

    private fun blocksBetween(t0: Double, t1: Double): List<Int> = ring.filter { it.first >= t0 && it.first < t1 }.map { it.second }

    /** Best aligned agreement of the live sting against a candidate's wider window. */
    private fun bestAgreement(live: List<Int>, candidate: List<Int>): Double {
        if (live.size < stingBlocks || candidate.size < stingBlocks) return 0.0
        var best = 0.0
        for (off in 0..(candidate.size - live.size)) {
            val a = Chroma.agreement(live, candidate.subList(off, off + live.size))
            if (a > best) best = a
        }
        return best
    }

    /** The stored sting as heard, then every transposition and stretch the config allows, each labelled; the identity first and unlabelled. */
    private fun variants(blocks: List<Int>): List<Pair<List<Int>, String>> {
        val out = ArrayList<Pair<List<Int>, String>>()
        out.add(Pair(blocks, ""))
        val shifts = (-cfg.pitchShifts..cfg.pitchShifts).filter { it != 0 }
        val factors = if (cfg.tempoTolerance > 0) listOf(1.0 - cfg.tempoTolerance, 1.0 + cfg.tempoTolerance) else emptyList()
        for (k in listOf(0) + shifts) {
            val rotated = if (k != 0) blocks.map { pitchRotate(it, k) } else blocks
            for (f in factors) out.add(Pair(tempoStretch(rotated, f), "%+dst/%.2fx".format(k, f)))
            if (k != 0) out.add(Pair(rotated, "%+dst".format(k)))
        }
        return out
    }

    /** Best agreement of [live] against the sting or any variant of it, the variants docked [JingleConfig.familyPenalty] so the sting as heard wins a tie. */
    private fun bestFamily(live: List<Int>, jingleBlocks: List<Int>): Pair<Double, String> {
        var best = 0.0; var label = ""
        for ((blocks, variant) in variants(jingleBlocks)) {
            val a = bestAgreement(live, blocks) - (if (variant.isEmpty()) 0.0 else cfg.familyPenalty)
            if (a > best) { best = a; label = variant }
        }
        return Pair(best, label)
    }

    private fun sameSting(a: List<Int>, b: List<Int>): Boolean {
        // Slide the shorter core of one over the other: the sting may sit anywhere in either window.
        val core = stingBlocks
        for (i in 0..(a.size - core)) if (bestFamily(a.subList(i, i + core), b).first >= cfg.minAgreement) return true
        return false
    }

    /** Promoted outright, or one break short of it at an hour it has opened breaks in before. */
    private fun trusted(j: Jingle): Boolean {
        if (j.hits >= cfg.promoteHits) return true
        val h = hour ?: return false
        return h in j.hours && cfg.promoteHits - 1 > 0 && j.hits >= cfg.promoteHits - 1
    }

    private fun threshold(j: Jingle): Double = cfg.minAgreement - (if (hour != null && hour in j.hours) cfg.hourBonus else 0.0)

    private fun onSample(ts: Double) {
        lastTs = ts
        pendingClose?.let { end ->
            if (ts >= end + cfg.candidateS - 2.0) {
                pendingClose = null; val h = pendingCloseHour; pendingCloseHour = null
                addCandidate(JingleKind.CLOSE, blocksBetween(end - 2.0, end + cfg.candidateS - 2.0), ts, h)
            }
        }
        val live = ring.takeLast(stingBlocks).map { it.second }
        if (live.size < stingBlocks) return
        var best: Jingle? = null; var bestA = 0.0; var bestV = ""
        for (j in jingles) {
            if (!trusted(j)) continue
            val (a, variant) = bestFamily(live, j.blocks)
            if (a >= threshold(j) && a > bestA) { best = j; bestA = a; bestV = variant }
        }
        val j = best ?: return
        lastAgreement = bestA; lastVariant = bestV
        if (j.kind == JingleKind.OPEN) { if (openHitTs == null || ts - openHitTs!! > cfg.holdS) { openHitTs = ts; openHitId = j.id } }
        else closeHitTs = ts
    }

    private fun addCandidate(kind: JingleKind, blocks: List<Int>, ts: Double, hourOfDay: Int? = null) {
        if (blocks.size < stingBlocks) return
        val same = jingles.firstOrNull { it.kind == kind && sameSting(blocks, it.blocks) }
        if (same != null) { same.hits++; if (hourOfDay != null) same.hours.add(hourOfDay); store.save(jingles); return }
        jingles.add(Jingle(nextId++, kind, blocks, 1, 0, ts, if (hourOfDay != null) hashSetOf(hourOfDay) else HashSet()))
        // Keep the candidate list bounded: the oldest one-off candidates go first.
        while (jingles.count { it.hits < cfg.promoteHits } > cfg.maxCandidates) {
            val victim = jingles.filter { it.hits < cfg.promoteHits }.minByOrNull { it.createdTs } ?: break
            jingles.remove(victim)
        }
        store.save(jingles)
    }

    /**
     * A real break ran from [startTs] to [endTs] (media time): the seconds
     * around its start are an opener candidate now, the seconds around its
     * end a closer candidate once they have been heard.
     */
    fun learnBreak(startTs: Double, endTs: Double, wallStart: Double? = null) {
        val h = if (wallStart != null) localHour(wallStart) else hour
        addCandidate(JingleKind.OPEN, blocksBetween(startTs - (cfg.candidateS - 2.0), startTs + 2.0), endTs, h)
        pendingClose = endTs; pendingCloseHour = h
    }

    /** Wall-clock time passes: the hour of day steers which stings are trusted (ADR 0027). */
    fun tick(wall: Double) { hour = localHour(wall) }

    /** "Not an ad": a jingle that just fired was wrong; enough wrong calls demote it back to a candidate. */
    override fun userSaysProgramme(ts: Double) {
        val id = openHitId
        if (id != null && openHitTs != null && ts - openHitTs!! < 60.0) {
            jingles.firstOrNull { it.id == id }?.let { j -> j.falseHits++; if (j.falseHits >= j.hits) { j.hits = 0 }; store.save(jingles) }
        }
        openHitTs = null; openHitId = null; closeHitTs = null; pendingClose = null
    }

    /** A closer was just heard: the programme is back, whatever the audio methods think. */
    override val programPresent: Boolean get() = closeHitTs?.let { lastTs - it < cfg.closeHoldS } ?: false

    /** Silent until a sting has opened enough breaks to be trusted: a learner
     *  with nothing to say must not dilute the detectors that do (fusion norm). */
    override val voting: Boolean get() = promoted().any { it.kind == JingleKind.OPEN }

    override fun vote(ts: Double): DetectorVote {
        val open = openHitTs
        if (open != null && ts - open < cfg.holdS && (closeHitTs == null || closeHitTs!! < open)) {
            val family = if (lastVariant.isEmpty()) "" else " family=$lastVariant"
            return vote(ts, 1.0, "jingle_open id=$openHitId agree=${"%.2f".format(lastAgreement)} age_s=${"%.1f".format(ts - open)}$family")
        }
        return vote(ts, 0.0, "jingle_quiet known=${promoted().size} candidates=${jingles.size - promoted().size}")
    }

    /** The jingles trusted right now (the hour of day can admit one a break early). */
    fun promoted(): List<Jingle> = jingles.filter { trusted(it) }

    fun describe(): String {
        val p = promoted()
        val known = "${p.count { it.kind == JingleKind.OPEN }} opener${if (p.count { it.kind == JingleKind.OPEN } == 1) "" else "s"}, ${p.count { it.kind == JingleKind.CLOSE }} closer${if (p.count { it.kind == JingleKind.CLOSE } == 1) "" else "s"}"
        val cands = jingles.size - p.size
        val hours = p.flatMap { it.hours }.toSortedSet()
        val when_ = if (hours.isEmpty()) "" else "; heard at " + hours.joinToString(", ") { "%02dh".format(it) }
        return if (p.isEmpty()) "learning ($cands candidate${if (cands == 1) "" else "s"}; a sting must open 3 breaks)" else "$known known, $cands candidate${if (cands == 1) "" else "s"}$when_"
    }
}
