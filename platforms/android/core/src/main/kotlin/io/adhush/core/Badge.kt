package io.adhush.core

/**
 * The ad badge (ADR 0020, after AdMuteTV and AutoAdMuter): streaming players
 * draw an explicit "AD", "Ad 1 of 3" or "Ad · 0:15" in a corner of the
 * picture during a break. During stream learning the phone reads its own
 * screen's corners with the caption text recogniser and hands the words here.
 * A badge seen is a near-certain break (the logo weight, so it drives the
 * learning duck alone); a scan that finds no badge shortly after one did is
 * programme evidence. Whole words only: AutoAdMuter's substring match fired on
 * "road" and "already", so a bare "ad" inside a word never counts here.
 */
data class BadgeConfig(
    /** A badge keeps the vote this long after the last sighting (scans run about once a second). */
    val holdS: Double = 3.0,
    /** No badge for this long after the last one, with scans still arriving: the break is over. */
    val programAfterS: Double = 2.0,
    /** Without a scan for this long the detector is inert (no screen being read). */
    val staleS: Double = 10.0,
)

class BadgeDetector(private val cfg: BadgeConfig = BadgeConfig()) : Detector {
    override val name = "badge"
    private var seenTs: Double? = null
    private var scannedTs: Double? = null
    private var lastTs = 0.0
    var lastBadge = ""
        private set
    var scans = 0L
        private set

    override fun warmup() { seenTs = null; scannedTs = null; lastBadge = "" }
    override fun observeAudio(block: AudioBlock) { lastTs = block.ts }

    /** Text read off the picture's corners at media time [ts]. */
    fun observeText(ts: Double, text: String) {
        scannedTs = ts; lastTs = ts; scans++
        val m = badgeIn(text) ?: return
        seenTs = ts; lastBadge = m
    }

    /** Inert until the screen is being read: no dilution when there is no badge to see. */
    override val voting: Boolean get() = scannedTs?.let { lastTs - it < cfg.staleS } ?: false

    /** Scans keep arriving and the badge has been gone for a moment: the programme is back. */
    override val programPresent: Boolean
        get() {
            val scanned = scannedTs ?: return false
            val seen = seenTs ?: return false
            return lastTs - scanned < cfg.staleS && lastTs - seen > cfg.programAfterS && lastTs - seen < 60.0
        }

    override fun vote(ts: Double): DetectorVote {
        val seen = seenTs
        if (seen != null && ts - seen < cfg.holdS) return vote(ts, 1.0, "badge \"$lastBadge\" age_s=${"%.1f".format(ts - seen)}")
        return vote(ts, 0.0, "badge_none scans=$scans")
    }

    fun describe(): String = when {
        scannedTs == null -> "no screen read yet"
        seenTs != null && lastTs - seenTs!! < cfg.holdS -> "\"$lastBadge\""
        else -> "none in view"
    }

    companion object {
        private val WORD = Regex("""(?i)(?<![\p{L}\p{N}])(ad|ads|advert|advertisement|advertising|sponsored|commercial|commercials)(?![\p{L}\p{N}])""")
        private val COUNT = Regex("""(?i)\b(\d+)\s*(of|/)\s*(\d+)\b""")
        private val TIMER = Regex("""\b\d{1,2}:\d{2}\b""")
        private val PHRASE = Regex("""(?i)(your (video|show|programme|program) will (resume|continue)|ad break|ad will end)""")

        /** The badge as read, or null when the text carries none. */
        fun badgeIn(text: String): String? {
            PHRASE.find(text)?.let { return it.value }
            val w = WORD.find(text) ?: return null
            val tail = text.substring(w.range.last + 1).take(12)
            val extra = COUNT.find(tail)?.value ?: TIMER.find(tail)?.value
            return if (extra != null) "${w.value} $extra" else w.value
        }
    }
}
