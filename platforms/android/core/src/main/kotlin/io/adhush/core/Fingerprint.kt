package io.adhush.core

import java.io.File
import kotlin.math.abs
import kotlin.math.round

/** Ported from config.FingerprintConfig, keeping the validated defaults. */
data class FingerprintConfig(
    val sampleIntervalS: Double = 0.5,
    val windowS: Double = 6.0,
    val confirmHits: Int = 3,
    val slotSnapS: List<Double> = listOf(15.0, 30.0, 45.0, 60.0),
    val minLearnS: Double = 8.0,
    val maxLearnS: Double = 120.0,
    val snapMinSamples: Int = 3,
    /** Chroma agreement to accept a candidate. 0.7 is the Python corroboration threshold;
     *  as a *primary* key on room audio it is a starting guess — calibrate it. */
    val audioMinAgreement: Double = 0.7,
    /** Distinct pair-key hits an ad needs before it is even verified. */
    val minKeyHits: Int = 3,
    /** Aligned blocks the verification must cover (6 × 0.5 s = 3 s). */
    val minVerifyBlocks: Int = 6,
    /**
     * Teach mode: a match on taught *material* holds the duck only while the live
     * audio keeps agreeing with something known; this is how long a lapse may
     * last (the gap between two spots, a noisy second) before the duck is released.
     */
    val materialGraceS: Double = 5.0,
    /** A taught break shorter than this is a slip of the finger, not material. */
    val minMaterialS: Double = 5.0,
    /** The rolling hold judges only this much of the most recent audio, so it lets go soon after a spot ends. */
    val materialRecentS: Double = 2.0,
)

/** What a record is: a single spot with a known length, or a taught break of *material* matched piecewise. */
enum class AdKind(val wire: String) { AD("ad"), MATERIAL("material");
    companion object { fun of(wire: String) = entries.firstOrNull { it.wire == wire } ?: AD }
}

data class AdRecord(val adId: Int, val durationS: Double, val sampleCount: Int, val kind: AdKind = AdKind.AD)

/** Same fields as store.py's tables; audio-only, so no video_hashes. */
interface FingerprintStore {
    fun addAd(durationS: Double, audio: List<Pair<Double, Int>>, nowTs: Double = 0.0, kind: AdKind = AdKind.AD): Int
    fun get(adId: Int): AdRecord?
    fun ads(): List<AdRecord>
    fun audioBlocks(adId: Int): List<Pair<Double, Int>>
    fun updateDuration(adId: Int, durationS: Double, sampleCount: Int)
    fun deleteAd(adId: Int)
    fun count(): Int
}

/**
 * In-memory store with a plain tab-separated file behind it (no JSON or
 * SQLite dependency in the core). One `ad` line per learned ad, then its
 * `blk` lines — the same fields store.py keeps in SQLite. The Android app
 * points it at a file in app-private storage.
 */
class FileFingerprintStore(private val file: File? = null) : FingerprintStore {
    private class Ad(val adId: Int, var durationS: Double, var sampleCount: Int, val createdTs: Double, var updatedTs: Double, val blocks: List<Pair<Double, Int>>, val kind: AdKind)
    private val ads = LinkedHashMap<Int, Ad>()
    private var nextId = 1

    init { file?.takeIf { it.isFile }?.let { load(it) } }

    @Synchronized override fun addAd(durationS: Double, audio: List<Pair<Double, Int>>, nowTs: Double, kind: AdKind): Int {
        val id = nextId++
        ads[id] = Ad(id, durationS, 1, nowTs, nowTs, audio.toList(), kind)
        persist()
        return id
    }
    @Synchronized override fun get(adId: Int): AdRecord? = ads[adId]?.let { AdRecord(it.adId, it.durationS, it.sampleCount, it.kind) }
    @Synchronized override fun ads(): List<AdRecord> = ads.values.map { AdRecord(it.adId, it.durationS, it.sampleCount, it.kind) }
    @Synchronized override fun audioBlocks(adId: Int): List<Pair<Double, Int>> = ads[adId]?.blocks?.sortedBy { it.first } ?: emptyList()
    @Synchronized override fun updateDuration(adId: Int, durationS: Double, sampleCount: Int) {
        ads[adId]?.let { it.durationS = durationS; it.sampleCount = sampleCount; persist() }
    }
    @Synchronized override fun deleteAd(adId: Int) { ads.remove(adId); persist() }
    @Synchronized override fun count(): Int = ads.size

    private fun persist() {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            w.write("# adhush fingerprints v1\n")
            for (ad in ads.values) {
                w.write("ad\t${ad.adId}\t${ad.durationS}\t${ad.sampleCount}\t${ad.createdTs}\t${ad.updatedTs}\t${ad.kind.wire}\n")
                for ((off, bits) in ad.blocks) w.write("blk\t${ad.adId}\t$off\t$bits\n")
            }
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun load(f: File) {
        val blocks = HashMap<Int, MutableList<Pair<Double, Int>>>()
        val heads = ArrayList<Array<String>>()
        f.forEachLine { line ->
            val parts = line.split('\t')
            when (parts[0]) {
                "ad" -> heads.add(parts.toTypedArray())
                "blk" -> blocks.getOrPut(parts[1].toInt()) { ArrayList() }.add(Pair(parts[2].toDouble(), parts[3].toInt()))
            }
        }
        for (p in heads) {
            val id = p[1].toInt()
            val kind = if (p.size > 6) AdKind.of(p[6]) else AdKind.AD   // files from before teach mode have no kind
            ads[id] = Ad(id, p[2].toDouble(), p[3].toInt(), p[4].toDouble(), p[5].toDouble(), blocks[id] ?: emptyList(), kind)
            if (id >= nextId) nextId = id + 1
        }
    }
}

/**
 * A confirmed match. For a single spot the duck is expected to end at
 * estStartTs + durationS; for taught material [expectedEndTs] is rolling — the
 * detector pushes it forward while the live audio keeps agreeing.
 */
data class Match(
    val adId: Int, val estStartTs: Double, val durationS: Double, val confirmedTs: Double, val agreement: Double,
    val kind: AdKind = AdKind.AD, val expectedEndTs: Double = estStartTs + durationS,
)

/**
 * Audio-primary matching, the piece the Python core does not have (its matcher
 * keys on video pHash and uses chroma only as corroboration). Pair keys —
 * (bits[t] << 12) | bits[t + Δ] for Δ ∈ {2, 4, 8} blocks — feed an inverted
 * index; candidates are verified with the same agreement() the Python side
 * uses for corroboration, then confirmed over consecutive samples exactly as
 * the video matcher does (docs/android-app-design.md).
 */
class AudioMatcher(private val store: FingerprintStore, private val cfg: FingerprintConfig) {
    private class Entry(val adId: Int, val blockIndex: Int)
    private var index = HashMap<Int, MutableList<Entry>>()
    private var blocksByAd = HashMap<Int, List<Pair<Double, Int>>>()
    private var candidate: Int? = null
    private var streak = 0
    private var estStart = 0.0

    init { refresh() }

    fun refresh() {
        val newIndex = HashMap<Int, MutableList<Entry>>()
        val newBlocks = HashMap<Int, List<Pair<Double, Int>>>()
        for (ad in store.ads()) {
            val blocks = store.audioBlocks(ad.adId)
            newBlocks[ad.adId] = blocks
            for (t in blocks.indices) for (delta in DELTAS) {
                if (t + delta >= blocks.size) continue
                val key = (blocks[t].second shl Chroma.BITS) or blocks[t + delta].second
                newIndex.getOrPut(key) { ArrayList() }.add(Entry(ad.adId, t))
            }
        }
        index = newIndex; blocksByAd = newBlocks
    }

    fun reset() { candidate = null; streak = 0 }

    fun effectiveDuration(adId: Int): Double {
        val record = store.get(adId) ?: return 0.0
        if (record.kind == AdKind.MATERIAL) return record.durationS   // a break is not a slot
        return if (record.sampleCount >= cfg.snapMinSamples) record.durationS else snapToSlot(record.durationS, cfg.slotSnapS)
    }

    fun kindOf(adId: Int): AdKind = store.get(adId)?.kind ?: AdKind.AD

    /** Does the most recent audio still agree with this record at this alignment? */
    fun stillAgrees(adId: Int, estStartTs: Double, live: List<Pair<Double, Int>>): Pair<Boolean, Double> {
        val recent = live.takeLast((cfg.materialRecentS / cfg.sampleIntervalS).toInt().coerceAtLeast(2))
        val score = corroborate(adId, estStartTs, recent)
        return Pair(score.first >= cfg.audioMinAgreement && score.second >= recent.size - 1, score.first)
    }

    /** Best (adId, estimated start) for a window of live blocks, verified, or null. */
    fun identify(live: List<Pair<Double, Int>>): Pair<Int, Double>? {
        if (index.isEmpty() || live.size < 2) return null
        val tally = HashMap<Pair<Int, Long>, Int>()
        val starts = HashMap<Pair<Int, Long>, Double>()
        for (t in live.indices) for (delta in DELTAS) {
            if (t + delta >= live.size) continue
            val key = (live[t].second shl Chroma.BITS) or live[t + delta].second
            for (entry in index[key] ?: continue) {
                val stored = blocksByAd[entry.adId] ?: continue
                val est = live[t].first - stored[entry.blockIndex].first
                val bucket = Pair(entry.adId, round(est / cfg.sampleIntervalS).toLong())
                tally[bucket] = (tally[bucket] ?: 0) + 1
                starts.putIfAbsent(bucket, est)
            }
        }
        val best = tally.maxByOrNull { it.value } ?: return null
        if (best.value < cfg.minKeyHits) return null
        val (adId, _) = best.key
        val est = starts[best.key]!!
        val score = corroborate(adId, est, live)
        return if (score.first >= cfg.audioMinAgreement && score.second >= cfg.minVerifyBlocks) Pair(adId, est) else null
    }

    /** (agreement, aligned block count) between live blocks and an ad's stored blocks. */
    fun corroborate(adId: Int, estStartTs: Double, live: List<Pair<Double, Int>>): Pair<Double, Int> {
        val stored = blocksByAd[adId] ?: return Pair(0.0, 0)
        if (stored.isEmpty() || live.isEmpty()) return Pair(0.0, 0)
        val half = cfg.sampleIntervalS / 2
        val storedBits = ArrayList<Int>(); val liveBits = ArrayList<Int>()
        for ((ts, bits) in live) {
            val offset = ts - estStartTs
            val nearest = stored.minByOrNull { abs(it.first - offset) } ?: continue
            if (abs(nearest.first - offset) <= half) { storedBits.add(nearest.second); liveBits.add(bits) }
        }
        return Pair(Chroma.agreement(storedBits, liveBits), storedBits.size)
    }

    /** Feed the current live window each sample; a Match once confirmHits samples agree. */
    fun feed(ts: Double, live: List<Pair<Double, Int>>): Match? {
        val found = identify(live)
        if (found == null) { reset(); return null }
        val (adId, est) = found
        if (adId == candidate) streak += 1 else { candidate = adId; streak = 1; estStart = est }
        if (streak < cfg.confirmHits) return null
        val score = corroborate(adId, estStart, live)
        return match(adId, estStart, ts, score.first)
    }

    /** Build a Match for a verified (adId, estStart): duration-bounded for a spot, grace-bounded for material. */
    fun match(adId: Int, estStart: Double, ts: Double, agreement: Double): Match {
        val kind = kindOf(adId)
        val dur = effectiveDuration(adId)
        val end = if (kind == AdKind.MATERIAL) ts + cfg.materialGraceS else estStart + dur
        return Match(adId, estStart, dur, ts, agreement, kind, end)
    }

    companion object { val DELTAS = intArrayOf(2, 4, 8) }
}

/** Learns from fusion-confirmed segments; folds airings into durations. Port of fingerprint/learner.py. */
class AudioLearner(private val store: FingerprintStore, private val matcher: AudioMatcher, private val cfg: FingerprintConfig) {
    fun learnSegment(startTs: Double, durationS: Double, audioBlocks: List<Pair<Double, Int>>, force: Boolean = false): Int? {
        if (!force && (durationS < cfg.minLearnS || durationS > cfg.maxLearnS)) return null
        val windowEnd = startTs + cfg.windowS
        val inWindow = audioBlocks.filter { it.first in startTs..windowEnd }
        if (inWindow.size < cfg.confirmHits) return null
        val known = matcher.identify(inWindow)
        if (known != null) { observeDuration(known.first, durationS); return known.first }
        val relative = inWindow.map { Pair(it.first - startTs, it.second) }
        val id = store.addAd(durationS, relative, startTs)
        matcher.refresh()
        return id
    }

    /**
     * Teach mode: the whole break the user bracketed, every block of it, as one
     * material record. Recognition later works on any stretch of it, so the
     * spots may come back in any order, alone, or cut down.
     */
    fun learnMaterial(startTs: Double, endTs: Double, audioBlocks: List<Pair<Double, Int>>): Int? {
        val durationS = endTs - startTs
        if (durationS < cfg.minMaterialS) return null
        val blocks = audioBlocks.filter { it.first in startTs..endTs }
        if (blocks.size < cfg.minVerifyBlocks) return null
        val id = store.addAd(durationS, blocks.map { Pair(it.first - startTs, it.second) }, startTs, AdKind.MATERIAL)
        matcher.refresh()
        return id
    }

    fun forget(adId: Int) { store.deleteAd(adId); matcher.refresh() }

    fun observeDuration(adId: Int, durationS: Double) {
        val record = store.get(adId) ?: return
        val n = record.sampleCount
        store.updateDuration(adId, (record.durationS * n + durationS) / (n + 1), n + 1)
        matcher.refresh()
    }
}
