package io.adhush.core

/**
 * A set whose volume can be read and written (ADR 0025): the Sharp over its
 * control port, a Sony Bravia over JSON-RPC, an LG over webOS, any DLNA
 * renderer over UPnP RenderingControl. [LevelController] ducks such a set
 * exactly, to a level, and notices the remote; a one-way path (infrared, a
 * Roku, a Samsung key socket) is a [KeySender] under [StepVolumeController].
 */
interface VolumeDevice : AutoCloseable {
    /** The scale's top: 60 on a Sharp, 100 on most others. */
    val maxVolume: Int
    /** Present volume, or null when the set will not say. */
    fun getVolume(): Int?
    fun setVolume(level: Int): Boolean
    fun setMute(on: Boolean): Boolean
    override fun close() {}
}

/**
 * Ducks instead of muting so the microphone keeps hearing the set, and restores
 * on every path back. mute()/unmute() are the MuteController face the engine
 * sees. The remote always wins: pollUserOverride() notices a volume that is
 * not the duck level and stands down. The Sharp controller is this with the
 * Sharp client underneath; every other brand with readback is this too.
 */
open class LevelController(
    val device: VolumeDevice,
    private val persistence: DuckPersistence,
    val duckLevel: Int = 4,
    var normalVolume: Int = 20,
    private val useMuteInstead: Boolean = false,
) : DuckController {
    override var ducked = false
        protected set

    /** Call once at start-up: a saved pre-duck volume means we died ducked. */
    override fun recoverOnStart(): Boolean {
        val saved = persistence.load() ?: return false
        normalVolume = saved
        restore()
        return true
    }

    override fun duck() {
        if (!useMuteInstead) {
            device.getVolume()?.let { if (it != duckLevel) normalVolume = it }
            persistence.save(normalVolume)
            device.setVolume(duckLevel.coerceIn(0, device.maxVolume))
        } else {
            persistence.save(normalVolume)
            device.setMute(true)
        }
        ducked = true
    }

    override fun restore() {
        val target = persistence.load() ?: normalVolume
        if (!useMuteInstead) device.setVolume(target.coerceIn(0, device.maxVolume)) else device.setMute(false)
        normalVolume = target
        persistence.clear()
        ducked = false
    }

    /** While ducked: did the user touch the remote? Then adopt their level and stand down. */
    override fun pollUserOverride(): Boolean {
        if (!ducked || useMuteInstead) return false
        val now = device.getVolume() ?: return false
        if (now == duckLevel) return false
        normalVolume = now
        persistence.clear()
        ducked = false
        return true
    }

    /** While at rest: follow the user's volume so a later restore lands on it. */
    override fun trackNormal() { if (!ducked) device.getVolume()?.let { normalVolume = it } }

    override fun mute() = duck()
    override fun unmute() = restore()
    override fun state(): Boolean? = ducked
    override fun close() { if (ducked) runCatching { restore() } }
}
