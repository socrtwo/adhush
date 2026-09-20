package io.adhush.android

import android.content.Intent
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import io.adhush.core.ControlError
import io.adhush.core.RemoteKey
import io.adhush.core.SharpIpClient
import io.adhush.core.SocketTransport
import io.adhush.core.press
import java.util.concurrent.Executors

/**
 * The Remote tab (ADR 0017; its own tab since 0.28.5): AdHush's own controls
 * on top, then the TV's remote drawn as one — power, input and mute, the
 * number pad, the volume and channel rockers either side of a round D-pad,
 * then menu, transport, the colour keys and the rest. While the service
 * runs, keys go through its connection (the Sharp allows one); otherwise
 * this panel opens its own and closes it when the app leaves the foreground.
 */
class RemotePanel(
    private val activity: AppCompatActivity,
    private val settings: Settings,
    private val serviceAction: (String, Button?, (Intent) -> Unit) -> Unit,
    private val say: (String) -> Unit,
) {
    private val io = Executors.newSingleThreadExecutor()
    private var own: SharpIpClient? = null
    private var ownTransport: AutoCloseable? = null
    private val keyButtons = HashMap<RemoteKey, MaterialButton>()

    init {
        activity.findViewById<Button>(R.id.rStart).onTap { ContextCompat.startForegroundService(activity, Intent(activity, AdHushService::class.java)) }
        activity.findViewById<Button>(R.id.rStop).onTap { b -> serviceAction(AdHushService.ACTION_STOP, b) {} }
        activity.findViewById<Button>(R.id.rIsAd).onTap { b -> serviceAction(AdHushService.ACTION_IS_AD, b) {} }
        activity.findViewById<Button>(R.id.rShowBack).onTap { b -> serviceAction(AdHushService.ACTION_SHOW_BACK, b) {} }
        activity.findViewById<Button>(R.id.rNotAd).onTap { b -> serviceAction(AdHushService.ACTION_NOT_AD, b) {} }
        for ((id, secs) in DUCKS) activity.findViewById<Button>(id).onTap { b -> serviceAction(AdHushService.ACTION_DUCK_FOR, b) { it.putExtra(AdHushService.EXTRA_SECONDS, secs) } }
        activity.findViewById<Button>(R.id.rDuckMore).onTap { b -> serviceAction(AdHushService.ACTION_DUCK_MORE, b) {} }
        for ((id, key) in KEYS) {
            val b = activity.findViewById<MaterialButton>(id)
            keyButtons[key] = b
            b.onTap { press(key) }
        }
        if (settings.control == "ir") activity.findViewById<android.widget.TextView>(R.id.remoteNote).text = "Infrared control knows only volume and mute; the TV keys need the network or the serial cable (TV page)."
        status(AdHushService.lastText, AdHushService.running != null, false, false)
    }

    /** The service's status line: the teach buttons are bright only when they would act. */
    fun status(text: String, running: Boolean, ducked: Boolean, teaching: Boolean) {
        activity.findViewById<android.widget.TextView>(R.id.remoteStatus).text = text
        val teachingNow = teaching || text.startsWith("TEACHING")
        val duckedNow = ducked || text.startsWith("DUCKED") || teachingNow
        activity.findViewById<Button>(R.id.rIsAd).alpha = if (running && !teachingNow) 1f else 0.55f
        activity.findViewById<Button>(R.id.rShowBack).alpha = if (running && duckedNow) 1f else 0.55f
        activity.findViewById<Button>(R.id.rNotAd).alpha = if (running && duckedNow) 1f else 0.55f
        for ((id, _) in DUCKS) activity.findViewById<Button>(id).alpha = if (running) 1f else 0.55f
        activity.findViewById<Button>(R.id.rDuckMore).alpha = if (running) 1f else 0.55f
    }

    /** The service says whether the action went through: the button that asked turns green or red. */
    fun actionDone(i: Intent) {
        val ok = i.getBooleanExtra("ok", false)
        val b: Button = when (i.getStringExtra("action")) {
            AdHushService.ACTION_NOT_AD -> activity.findViewById(R.id.rNotAd)
            AdHushService.ACTION_IS_AD -> activity.findViewById(R.id.rIsAd)
            AdHushService.ACTION_SHOW_BACK -> activity.findViewById(R.id.rShowBack)
            AdHushService.ACTION_DUCK_MORE -> activity.findViewById(R.id.rDuckMore)
            AdHushService.ACTION_DUCK_FOR -> DUCKS.firstOrNull { it.second == i.getIntExtra(AdHushService.EXTRA_SECONDS, 0) }?.let { activity.findViewById<Button>(it.first) } ?: return
            AdHushService.ACTION_KEY -> i.getStringExtra(AdHushService.EXTRA_KEY)?.let { RemoteKey.of(it) }?.let { keyButtons[it] } ?: return
            else -> return
        }
        if (ok) Feedback.ok(b) else Feedback.fail(b)
    }

    fun onPause() { closeOwn() }   // give the set's single connection back
    fun onDestroy() { closeOwn(); io.shutdown() }

    /** Through the service while it runs, else through a connection of our own. */
    private fun press(key: RemoteKey) {
        if (AdHushService.running != null) {
            ContextCompat.startForegroundService(activity, Intent(activity, AdHushService::class.java).setAction(AdHushService.ACTION_KEY).putExtra(AdHushService.EXTRA_KEY, key.name))
            return
        }
        io.execute {
            val b = keyButtons[key]
            try {
                val client = ownClient() ?: run { activity.runOnUiThread { Feedback.fail(b) }; return@execute }
                val sent = client.press(key)
                if (!sent) say("the set did not accept ${key.label}")
                activity.runOnUiThread { if (sent) Feedback.ok(b) else Feedback.fail(b) }
            } catch (e: ControlError) { say("${key.label} failed: ${e.message}"); closeOwn(); activity.runOnUiThread { Feedback.fail(b) } }
        }
    }

    private fun ownClient(): SharpIpClient? {
        own?.let { return it }
        when (settings.control) {
            "ir" -> { say("infrared knows only volume and mute"); return null }
            "serial" -> {
                val t = SerialTransport(activity, 2500)
                if (t.device() == null) { say("no USB serial adapter found"); return null }
                ownTransport = t; own = SharpIpClient(t)
            }
            else -> {
                if (settings.host.isBlank()) { say("no TV address — see the TV page"); return null }
                val t = SocketTransport(settings.host, settings.port, 2500, settings.login)
                ownTransport = t; own = SharpIpClient(t)
            }
        }
        return own
    }

    private fun closeOwn() { val t = ownTransport; own = null; ownTransport = null; if (t != null) io.execute { runCatching { t.close() } } }

    companion object {
        val DUCKS = listOf(R.id.rDuck30 to 30, R.id.rDuck60 to 60, R.id.rDuck90 to 90, R.id.rDuck120 to 120, R.id.rDuck150 to 150,
            R.id.rDuck180 to 180, R.id.rDuck210 to 210, R.id.rDuck240 to 240, R.id.rDuck270 to 270, R.id.rDuck300 to 300)
        /** Every key on the drawn remote and the code it sends. */
        val KEYS: Map<Int, RemoteKey> = mapOf(
            R.id.keyPower to RemoteKey.POWER, R.id.keyInput to RemoteKey.INPUT, R.id.keyMute to RemoteKey.MUTE,
            R.id.key1 to RemoteKey.DIGIT_1, R.id.key2 to RemoteKey.DIGIT_2, R.id.key3 to RemoteKey.DIGIT_3,
            R.id.key4 to RemoteKey.DIGIT_4, R.id.key5 to RemoteKey.DIGIT_5, R.id.key6 to RemoteKey.DIGIT_6,
            R.id.key7 to RemoteKey.DIGIT_7, R.id.key8 to RemoteKey.DIGIT_8, R.id.key9 to RemoteKey.DIGIT_9,
            R.id.keyDot to RemoteKey.DOT, R.id.key0 to RemoteKey.DIGIT_0, R.id.keyEnt to RemoteKey.ENT,
            R.id.keyUp to RemoteKey.UP, R.id.keyDown to RemoteKey.DOWN, R.id.keyLeft to RemoteKey.LEFT, R.id.keyRight to RemoteKey.RIGHT, R.id.keyOk to RemoteKey.ENTER,
            R.id.keyVolUp to RemoteKey.VOL_UP, R.id.keyVolDown to RemoteKey.VOL_DOWN, R.id.keyChUp to RemoteKey.CH_UP, R.id.keyChDown to RemoteKey.CH_DOWN,
            R.id.keyMenu to RemoteKey.MENU, R.id.keyReturn to RemoteKey.RETURN, R.id.keyExit to RemoteKey.EXIT, R.id.keySmart to RemoteKey.SMART,
            R.id.keyRew to RemoteKey.REW, R.id.keyPlay to RemoteKey.PLAY, R.id.keyPause to RemoteKey.PAUSE, R.id.keyStop to RemoteKey.STOP, R.id.keyFf to RemoteKey.FF,
            R.id.keyRed to RemoteKey.RED, R.id.keyGreen to RemoteKey.GREEN, R.id.keyYellow to RemoteKey.YELLOW, R.id.keyBlue to RemoteKey.BLUE,
            R.id.keyDisplay to RemoteKey.DISPLAY, R.id.keyCc to RemoteKey.CC, R.id.keyAudio to RemoteKey.AUDIO, R.id.keySleep to RemoteKey.SLEEP,
            R.id.keyFlashback to RemoteKey.FLASHBACK, R.id.keyFav to RemoteKey.FAV, R.id.keyAvMode to RemoteKey.AV_MODE,
            R.id.keyViewMode to RemoteKey.VIEW_MODE, R.id.keyFreeze to RemoteKey.FREEZE, R.id.keyNetflix to RemoteKey.NETFLIX,
        )
    }
}
