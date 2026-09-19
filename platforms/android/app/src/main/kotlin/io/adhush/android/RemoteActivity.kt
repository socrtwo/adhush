package io.adhush.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.content.res.ColorStateList
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
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

    private val keyButtons = HashMap<RemoteKey, MaterialButton>()

    private val status = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AdHushService.BROADCAST_ACTION) { actionDone(intent); return }
            val text = intent.getStringExtra("text") ?: ""
            findViewById<TextView>(R.id.remoteStatus).text = text
            val running = intent.getBooleanExtra("running", AdHushService.running != null)
            val teaching = intent.getBooleanExtra("teaching", false) || text.startsWith("TEACHING")
            val ducked = intent.getBooleanExtra("ducked", false) || text.startsWith("DUCKED") || teaching
            findViewById<Button>(R.id.rIsAd).alpha = if (running && !teaching) 1f else 0.55f
            findViewById<Button>(R.id.rShowBack).alpha = if (running && ducked) 1f else 0.55f
            findViewById<Button>(R.id.rNotAd).alpha = if (running && ducked) 1f else 0.55f
        }
    }

    /** The service says whether the action went through: the button that asked turns green or red. */
    private fun actionDone(i: Intent) {
        val ok = i.getBooleanExtra("ok", false)
        val b: Button = when (i.getStringExtra("action")) {
            AdHushService.ACTION_NOT_AD -> findViewById(R.id.rNotAd)
            AdHushService.ACTION_IS_AD -> findViewById(R.id.rIsAd)
            AdHushService.ACTION_SHOW_BACK -> findViewById(R.id.rShowBack)
            AdHushService.ACTION_DUCK_MORE -> findViewById(R.id.rDuckMore)
            AdHushService.ACTION_DUCK_FOR -> DUCKS.firstOrNull { it.second == i.getIntExtra(AdHushService.EXTRA_SECONDS, 0) }?.let { findViewById<Button>(it.first) } ?: return
            AdHushService.ACTION_KEY -> i.getStringExtra(AdHushService.EXTRA_KEY)?.let { RemoteKey.of(it) }?.let { keyButtons[it] } ?: return
            else -> return
        }
        if (ok) Feedback.ok(b) else Feedback.fail(b)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)
        settings = Settings(this)
        title = "AdHush remote"
        findViewById<Button>(R.id.rStart).onTap { ContextCompat.startForegroundService(this, Intent(this, AdHushService::class.java)) }
        findViewById<Button>(R.id.rStop).onTap { b -> serviceAction(AdHushService.ACTION_STOP, b) }
        findViewById<Button>(R.id.rIsAd).onTap { b -> serviceAction(AdHushService.ACTION_IS_AD, b) }
        findViewById<Button>(R.id.rShowBack).onTap { b -> serviceAction(AdHushService.ACTION_SHOW_BACK, b) }
        findViewById<Button>(R.id.rNotAd).onTap { b -> serviceAction(AdHushService.ACTION_NOT_AD, b) }
        for ((id, secs) in DUCKS)
            findViewById<Button>(id).onTap { b -> serviceAction(AdHushService.ACTION_DUCK_FOR, b) { it.putExtra(AdHushService.EXTRA_SECONDS, secs) } }
        findViewById<Button>(R.id.rDuckMore).onTap { b -> serviceAction(AdHushService.ACTION_DUCK_MORE, b) }
        buildKeys(findViewById(R.id.keys))
        findViewById<TextView>(R.id.remoteStatus).text = AdHushService.lastText
        if (settings.control == "ir") findViewById<TextView>(R.id.remoteNote).text = "Infrared control knows only volume and mute; the TV keys need the network or the serial cable (TV page)."
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(this, status, IntentFilter(AdHushService.BROADCAST_STATUS).apply { addAction(AdHushService.BROADCAST_ACTION) }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(status) }
        closeOwn()   // give the set's single connection back
    }

    override fun onDestroy() { super.onDestroy(); closeOwn(); io.shutdown() }

    /**
     * The keys laid out the way a hand expects them (ADR 0027, amended): power
     * and input up top, the volume and channel rockers either side of a real
     * D-pad, the number pad below, transport and the odd keys last. Every key
     * is 56 dp tall; the rockers and OK are taller still.
     */
    private fun buildKeys(column: LinearLayout) {
        column.removeAllViews()
        fun key(key: RemoteKey?, parent: LinearLayout, tall: Boolean = false, accent: Int? = null): MaterialButton {
            val b = layoutInflater.inflate(R.layout.item_key, parent, false) as MaterialButton
            if (parent.orientation == LinearLayout.VERTICAL) (b.layoutParams as LinearLayout.LayoutParams).apply { width = LinearLayout.LayoutParams.MATCH_PARENT; weight = 0f }   // a rocker fills its column
            b.text = key?.label ?: ""
            if (key == null) { b.visibility = View.INVISIBLE; b.isEnabled = false }
            if (tall) b.minHeight = (72 * resources.displayMetrics.density).toInt()
            if (accent != null) { b.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, accent)); b.setTextColor(android.graphics.Color.WHITE) }
            if (key != null) { b.onTap { press(key) }; keyButtons[key] = b }
            parent.addView(b)
            return b
        }
        fun row(vararg keys: RemoteKey?, tall: Boolean = false, accents: Map<RemoteKey, Int> = emptyMap()): LinearLayout {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
            for (k in keys) key(k, r, tall, k?.let { accents[it] })
            column.addView(r)
            return r
        }
        fun heading(text: String) {
            column.addView(TextView(this).apply {
                this.text = text; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setPadding((6 * resources.displayMetrics.density).toInt(), (14 * resources.displayMetrics.density).toInt(), 0, (2 * resources.displayMetrics.density).toInt())
            })
        }
        row(RemoteKey.POWER, RemoteKey.INPUT, RemoteKey.MUTE, accents = mapOf(RemoteKey.POWER to R.color.adhush_ducked, RemoteKey.MUTE to R.color.adhush_teaching))
        // Volume rocker | D-pad | channel rocker.
        val cluster = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        fun col(weight: Float) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight) }
        val vol = col(1f); key(RemoteKey.VOL_UP, vol, tall = true); key(RemoteKey.VOL_DOWN, vol, tall = true)
        val pad = col(2f)
        for (line in listOf(listOf(null, RemoteKey.UP, null), listOf(RemoteKey.LEFT, RemoteKey.ENTER, RemoteKey.RIGHT), listOf(null, RemoteKey.DOWN, null))) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
            for (k in line) key(k, r, accent = if (k == RemoteKey.ENTER) R.color.adhush_primary else null)
            pad.addView(r)
        }
        val ch = col(1f); key(RemoteKey.CH_UP, ch, tall = true); key(RemoteKey.CH_DOWN, ch, tall = true)
        cluster.addView(vol); cluster.addView(pad); cluster.addView(ch)
        column.addView(cluster)
        row(RemoteKey.MENU, RemoteKey.RETURN, RemoteKey.EXIT, RemoteKey.SMART)
        heading("Channels")
        row(RemoteKey.DIGIT_1, RemoteKey.DIGIT_2, RemoteKey.DIGIT_3)
        row(RemoteKey.DIGIT_4, RemoteKey.DIGIT_5, RemoteKey.DIGIT_6)
        row(RemoteKey.DIGIT_7, RemoteKey.DIGIT_8, RemoteKey.DIGIT_9)
        row(RemoteKey.DOT, RemoteKey.DIGIT_0, RemoteKey.ENT)
        row(RemoteKey.FLASHBACK, RemoteKey.FAV)
        heading("Playback and picture")
        row(RemoteKey.REW, RemoteKey.PLAY, RemoteKey.PAUSE, RemoteKey.FF)
        row(RemoteKey.DISPLAY, RemoteKey.CC, RemoteKey.AUDIO, RemoteKey.SLEEP)
        row(RemoteKey.AV_MODE, RemoteKey.VIEW_MODE, RemoteKey.FREEZE, RemoteKey.NETFLIX)
    }

    companion object {
        val DUCKS = listOf(R.id.rDuck30 to 30, R.id.rDuck60 to 60, R.id.rDuck90 to 90, R.id.rDuck120 to 120, R.id.rDuck150 to 150,
            R.id.rDuck180 to 180, R.id.rDuck210 to 210, R.id.rDuck240 to 240, R.id.rDuck270 to 270, R.id.rDuck300 to 300)
    }

    private fun say(line: String) { AppLog.i("remote", line); runOnUiThread { findViewById<TextView>(R.id.remoteStatus).text = line } }

    private fun serviceAction(action: String, button: Button? = null, extras: (Intent) -> Unit = {}) {
        if (AdHushService.running == null && action != AdHushService.ACTION_STOP) { say("not running — press Start first"); Feedback.fail(button); return }
        if (action == AdHushService.ACTION_DUCK_MORE) say("+30 s")
        ContextCompat.startForegroundService(this, Intent(this, AdHushService::class.java).setAction(action).also(extras))
        if (action == AdHushService.ACTION_STOP) Feedback.ok(button)
    }

    /** Through the service while it runs, else through a connection of our own. */
    private fun press(key: RemoteKey) {
        if (AdHushService.running != null) {
            ContextCompat.startForegroundService(this, Intent(this, AdHushService::class.java).setAction(AdHushService.ACTION_KEY).putExtra(AdHushService.EXTRA_KEY, key.name))
            return
        }
        io.execute {
            val b = keyButtons[key]
            try {
                val client = ownClient() ?: run { runOnUiThread { Feedback.fail(b) }; return@execute }
                val sent = client.press(key)
                if (!sent) say("the set did not accept ${key.label}")
                runOnUiThread { if (sent) Feedback.ok(b) else Feedback.fail(b) }
            } catch (e: ControlError) { say("${key.label} failed: ${e.message}"); closeOwn(); runOnUiThread { Feedback.fail(b) } }
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

}
