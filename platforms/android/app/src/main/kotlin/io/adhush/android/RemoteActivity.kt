package io.adhush.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Button
import android.widget.GridLayout
import android.widget.TextView
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
 * One screen that does everything (ADR 0017): every key of the TV's own
 * remote through the control port, plus Start, Stop, Is an ad, Show's back,
 * Not an ad and the timed ducks. While the service runs, keys go through its
 * connection (the Sharp allows one); otherwise this screen opens its own and
 * closes it when it leaves the foreground.
 */
class RemoteActivity : AppCompatActivity() {
    private lateinit var settings: Settings
    private val io = Executors.newSingleThreadExecutor()
    private var own: SharpIpClient? = null
    private var ownTransport: AutoCloseable? = null

    private val status = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            findViewById<TextView>(R.id.remoteStatus).text = intent.getStringExtra("text") ?: ""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)
        settings = Settings(this)
        title = "AdHush remote"
        findViewById<Button>(R.id.rStart).setOnClickListener { ContextCompat.startForegroundService(this, Intent(this, AdHushService::class.java)) }
        findViewById<Button>(R.id.rStop).setOnClickListener { serviceAction(AdHushService.ACTION_STOP) }
        findViewById<Button>(R.id.rIsAd).setOnClickListener { serviceAction(AdHushService.ACTION_IS_AD) }
        findViewById<Button>(R.id.rShowBack).setOnClickListener { serviceAction(AdHushService.ACTION_SHOW_BACK) }
        findViewById<Button>(R.id.rNotAd).setOnClickListener { serviceAction(AdHushService.ACTION_NOT_AD) }
        for ((id, secs) in listOf(R.id.rDuck30 to 30, R.id.rDuck60 to 60, R.id.rDuck90 to 90, R.id.rDuck120 to 120, R.id.rDuck150 to 150,
                R.id.rDuck180 to 180, R.id.rDuck210 to 210, R.id.rDuck240 to 240, R.id.rDuck270 to 270, R.id.rDuck300 to 300))
            findViewById<Button>(id).setOnClickListener { serviceAction(AdHushService.ACTION_DUCK_FOR) { it.putExtra(AdHushService.EXTRA_SECONDS, secs) } }
        findViewById<Button>(R.id.rDuckMore).setOnClickListener { serviceAction(AdHushService.ACTION_DUCK_MORE) }
        val grid = findViewById<GridLayout>(R.id.keys)
        for (key in LAYOUT) {
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            b.text = key?.label ?: ""
            b.isEnabled = key != null
            b.insetTop = 0; b.insetBottom = 0
            val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f))
            lp.width = 0
            b.layoutParams = lp
            if (key != null) b.setOnClickListener { press(key) }
            grid.addView(b)
        }
        findViewById<TextView>(R.id.remoteStatus).text = AdHushService.lastText
        if (settings.control == "ir") findViewById<TextView>(R.id.remoteNote).text = "Infrared control knows only volume and mute; the TV keys need the network or the serial cable (TV page)."
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(this, status, IntentFilter(AdHushService.BROADCAST_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(status) }
        closeOwn()   // give the set's single connection back
    }

    override fun onDestroy() { super.onDestroy(); closeOwn(); io.shutdown() }

    private fun say(line: String) { AppLog.i("remote", line); runOnUiThread { findViewById<TextView>(R.id.remoteStatus).text = line } }

    private fun serviceAction(action: String, extras: (Intent) -> Unit = {}) {
        if (AdHushService.running == null && action != AdHushService.ACTION_STOP) { say("not running — press Start first"); return }
        if (action == AdHushService.ACTION_DUCK_MORE) say("+30 s")
        ContextCompat.startForegroundService(this, Intent(this, AdHushService::class.java).setAction(action).also(extras))
    }

    /** Through the service while it runs, else through a connection of our own. */
    private fun press(key: RemoteKey) {
        if (AdHushService.running != null) {
            ContextCompat.startForegroundService(this, Intent(this, AdHushService::class.java).setAction(AdHushService.ACTION_KEY).putExtra(AdHushService.EXTRA_KEY, key.name))
            return
        }
        io.execute {
            try {
                val client = ownClient() ?: return@execute
                if (!client.press(key)) say("the set did not accept ${key.label}")
            } catch (e: ControlError) { say("${key.label} failed: ${e.message}"); closeOwn() }
        }
    }

    private fun ownClient(): SharpIpClient? {
        own?.let { return it }
        when (settings.control) {
            "ir" -> { say("infrared knows only volume and mute"); return null }
            "serial" -> {
                val t = SerialTransport(this, 2500)
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
        /** Five columns; null is a spacer. */
        val LAYOUT: List<RemoteKey?> = listOf(
            RemoteKey.POWER, RemoteKey.INPUT, RemoteKey.DISPLAY, RemoteKey.CC, RemoteKey.SLEEP,
            RemoteKey.DIGIT_1, RemoteKey.DIGIT_2, RemoteKey.DIGIT_3, RemoteKey.VOL_UP, RemoteKey.CH_UP,
            RemoteKey.DIGIT_4, RemoteKey.DIGIT_5, RemoteKey.DIGIT_6, RemoteKey.VOL_DOWN, RemoteKey.CH_DOWN,
            RemoteKey.DIGIT_7, RemoteKey.DIGIT_8, RemoteKey.DIGIT_9, RemoteKey.MUTE, RemoteKey.FLASHBACK,
            RemoteKey.DOT, RemoteKey.DIGIT_0, RemoteKey.ENT, RemoteKey.FAV, RemoteKey.AUDIO,
            RemoteKey.MENU, RemoteKey.UP, RemoteKey.RETURN, RemoteKey.EXIT, RemoteKey.SMART,
            RemoteKey.LEFT, RemoteKey.ENTER, RemoteKey.RIGHT, RemoteKey.AV_MODE, RemoteKey.VIEW_MODE,
            RemoteKey.REW, RemoteKey.DOWN, RemoteKey.FF, RemoteKey.PLAY, RemoteKey.PAUSE,
        )
    }
}
