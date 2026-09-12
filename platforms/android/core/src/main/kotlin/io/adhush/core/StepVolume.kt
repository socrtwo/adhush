package io.adhush.core

/** What every TV path offers the service, whether it can read the set's volume back or not. */
interface DuckController : MuteController {
    val ducked: Boolean
    fun duck()
    fun restore()
    /** Repair a duck that a crash left behind; true when something was restored. */
    fun recoverOnStart(): Boolean
    /** While ducked: did the user touch the remote? (Only answerable with readback.) */
    fun pollUserOverride(): Boolean
    /** While at rest: follow the user's volume. (No-op without readback.) */
    fun trackNormal()
}

enum class TvKey { VOLUME_UP, VOLUME_DOWN, MUTE }

/** Sends remote-control key presses: the phone's IR blaster, a networked blaster, anything one-way. */
fun interface KeySender { fun press(key: TvKey, times: Int) }

/**
 * Ducking over a one-way path (infrared): press volume-down (normal − duck)
 * times, later volume-up the same number. There is no readback, so the count
 * is persisted before the first press and restore replays exactly what was
 * pressed; a missed press drifts the set by one step until the user's remote
 * or "Restore volume" resets it. Mute is never used: a toggle with no readback
 * cannot be made fail-safe.
 */
class StepVolumeController(
    private val sender: KeySender,
    private val persistence: DuckPersistence,
    val duckLevel: Int = 4,
    var normalVolume: Int = 20,
) : DuckController {
    override var ducked = false
        private set

    private val steps: Int get() = (normalVolume - duckLevel).coerceAtLeast(0)

    override fun recoverOnStart(): Boolean {
        val pending = persistence.load() ?: return false
        if (pending > 0) sender.press(TvKey.VOLUME_UP, pending)
        persistence.clear()
        return true
    }

    override fun duck() {
        if (ducked) return
        val n = steps
        persistence.save(n)                 // before pressing: a crash mid-burst still restores
        if (n > 0) sender.press(TvKey.VOLUME_DOWN, n)
        ducked = true
    }

    override fun restore() {
        val n = persistence.load() ?: steps
        if (n > 0) sender.press(TvKey.VOLUME_UP, n)
        persistence.clear()
        ducked = false
    }

    override fun pollUserOverride(): Boolean = false
    override fun trackNormal() {}

    override fun mute() = duck()
    override fun unmute() = restore()
    override fun state(): Boolean? = ducked
    override fun close() { if (ducked) runCatching { restore() } }
}
