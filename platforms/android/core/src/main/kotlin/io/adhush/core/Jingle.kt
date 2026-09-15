package io.adhush.core

import java.io.File
import kotlin.math.max

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
 */
enum class JingleKind(val wire: String) { OPEN("open"), CLOSE("close");
    companion object { fun of(s: String) = entries.firstOrNull { it.wire == s } ?: OPEN }
}

class Jingle(val id: Int, val kind: JingleKind, val blocks: List<Int>, var hits: Int, var falseHits: Int, val createdTs: Double)

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
                out.add(Jingle(p[1].toInt(), JingleKind.of(p[2]), p[6].split(',').filter { it.isNotEmpty() }.map { it.toInt() }, p[3].toInt(), p[4].toInt(), p[5].toDouble()))
            }
        }
        return out
    }
    override fun save(jingles: List<Jingle>) {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            w.write("# adhush jingles v1\tid\tkind\thits\tfalse_hits\tcreated\tblocks\n")
            for (j in jingles) w.write("j\t${j.id}\t${j.kind.wire}\t${j.hits}\t${j.falseHits}\t${j.createdTs}\t${j.blocks.joinToString(",")}\n")
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
    private var lastTs = 0.0
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

    private fun sameSting(a: List<Int>, b: List<Int>): Boolean {
        // Slide the shorter core of one over the other: the sting may sit anywhere in either window.
        val core = stingBlocks
        for (i in 0..(a.size - core)) if (bestAgreement(a.subList(i, i + core), b) >= cfg.minAgreement) return true
        return false
    }

    private fun onSample(ts: Double) {
        lastTs = ts
        pendingClose?.let { end ->
            if (ts >= end + cfg.candidateS - 2.0) { pendingClose = null; addCandidate(JingleKind.CLOSE, blocksBetween(end - 2.0, end + cfg.candidateS - 2.0), ts) }
        }
        val live = ring.takeLast(stingBlocks).map { it.second }
        if (live.size < stingBlocks) return
        var best: Jingle? = null; var bestA = 0.0
        for (j in jingles) {
            if (j.hits < cfg.promoteHits) continue
            val a = bestAgreement(live, j.blocks)
            if (a >= cfg.minAgreement && a > bestA) { best = j; bestA = a }
        }
        val j = best ?: return
        lastAgreement = bestA
        if (j.kind == JingleKind.OPEN) { if (openHitTs == null || ts - openHitTs!! > cfg.holdS) { openHitTs = ts; openHitId = j.id } }
        else closeHitTs = ts
    }

    private fun addCandidate(kind: JingleKind, blocks: List<Int>, ts: Double) {
        if (blocks.size < stingBlocks) return
        val same = jingles.firstOrNull { it.kind == kind && sameSting(blocks, it.blocks) }
        if (same != null) { same.hits++; store.save(jingles); return }
        jingles.add(Jingle(nextId++, kind, blocks, 1, 0, ts))
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
    fun learnBreak(startTs: Double, endTs: Double) {
        addCandidate(JingleKind.OPEN, blocksBetween(startTs - (cfg.candidateS - 2.0), startTs + 2.0), endTs)
        pendingClose = endTs
    }

    /** "Not an ad": a jingle that just fired was wrong; enough wrong calls demote it back to a candidate. */
    override fun userSaysProgramme(ts: Double) {
        val id = openHitId
        if (id != null && openHitTs != null && ts - openHitTs!! < 60.0) {
            jingles.firstOrNull { it.id == id }?.let { j -> j.falseHits++; if (j.falseHits >= j.hits) { j.hits = 0 }; store.save(jingles) }
        }
        openHitTs = null; openHitId = null; closeHitTs = null; pendingClose = null
    }

    /** A closer was just heard: the programme is back, whatever the audio methods think. */
    val programPresent: Boolean get() = closeHitTs?.let { lastTs - it < cfg.closeHoldS } ?: false

    /** Silent until a sting has opened enough breaks to be trusted: a learner
     *  with nothing to say must not dilute the detectors that do (fusion norm). */
    override val voting: Boolean get() = promoted().any { it.kind == JingleKind.OPEN }

    override fun vote(ts: Double): DetectorVote {
        val open = openHitTs
        if (open != null && ts - open < cfg.holdS && (closeHitTs == null || closeHitTs!! < open)) {
            return vote(ts, 1.0, "jingle_open id=$openHitId agree=${"%.2f".format(lastAgreement)} age_s=${"%.1f".format(ts - open)}")
        }
        return vote(ts, 0.0, "jingle_quiet known=${promoted().size} candidates=${jingles.size - promoted().size}")
    }

    fun promoted(): List<Jingle> = jingles.filter { it.hits >= cfg.promoteHits }

    fun describe(): String {
        val p = promoted()
        val known = "${p.count { it.kind == JingleKind.OPEN }} opener${if (p.count { it.kind == JingleKind.OPEN } == 1) "" else "s"}, ${p.count { it.kind == JingleKind.CLOSE }} closer${if (p.count { it.kind == JingleKind.CLOSE } == 1) "" else "s"}"
        val cands = jingles.size - p.size
        return if (p.isEmpty()) "learning ($cands candidate${if (cands == 1) "" else "s"}; a sting must open 3 breaks)" else "$known known, $cands candidate${if (cands == 1) "" else "s"}"
    }
}
