package io.adhush.android

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.adhush.core.Aquos
import io.adhush.core.ControlError
import io.adhush.core.SharpIpClient
import io.adhush.core.SocketTransport
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var settings: Settings
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val i = intent ?: return
            when (i.action) {
                AdHushService.BROADCAST_STATUS -> findViewById<TextView>(R.id.status).text = i.getStringExtra("text") ?: ""
                AdHushService.BROADCAST_SURVEY -> {
                    log("— survey saved; press Share survey to send the numbers (no audio is stored) —")
                    (i.getStringExtra("summary") ?: "").lines().reversed().forEach { log(it) }
                }
            }
        }
    }
    private var pendingAction: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        load()
        findViewById<Button>(R.id.save).setOnClickListener { save(); log("saved") }
        findViewById<Button>(R.id.test).setOnClickListener { save(); testTv() }
        findViewById<Button>(R.id.start).setOnClickListener { save(); startWithPermissions(null) }
        findViewById<Button>(R.id.stop).setOnClickListener { serviceAction(AdHushService.ACTION_STOP) }
        findViewById<Button>(R.id.notAd).setOnClickListener { serviceAction(AdHushService.ACTION_NOT_AD) }
        findViewById<Button>(R.id.isAd).setOnClickListener { serviceAction(AdHushService.ACTION_IS_AD) }
        findViewById<Button>(R.id.survey).setOnClickListener { save(); startWithPermissions(AdHushService.ACTION_SURVEY) }
        findViewById<Button>(R.id.share).setOnClickListener { shareSurvey() }
    }

    /** The newest survey file, handed to whatever the user picks (mail, Drive, messages) through FileProvider. */
    private fun shareSurvey() {
        val dir = java.io.File(filesDir, AdHushService.SURVEY_DIR)
        val file = dir.listFiles { f -> f.name.endsWith(".tsv") }?.maxByOrNull { it.lastModified() }
        if (file == null) { log("no survey yet — press Survey room first"); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/tab-separated-values")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, "AdHush room survey ${file.name}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share survey"))
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(AdHushService.BROADCAST_STATUS).apply { addAction(AdHushService.BROADCAST_SURVEY) }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() { unregisterReceiver(receiver); super.onPause() }

    private fun load() {
        findViewById<EditText>(R.id.host).setText(settings.host)
        findViewById<EditText>(R.id.port).setText(settings.port.toString())
        findViewById<EditText>(R.id.login).setText(settings.loginId)
        findViewById<EditText>(R.id.password).setText(settings.password)
        findViewById<EditText>(R.id.duck).setText(settings.duckLevel.toString())
        findViewById<EditText>(R.id.normal).setText(settings.normalVolume.toString())
        findViewById<CheckBox>(R.id.useMute).isChecked = settings.useMute
    }

    private fun save() {
        settings.host = findViewById<EditText>(R.id.host).text.toString().trim()
        settings.port = findViewById<EditText>(R.id.port).text.toString().toIntOrNull() ?: Aquos.DEFAULT_PORT
        settings.loginId = findViewById<EditText>(R.id.login).text.toString().trim()
        settings.password = findViewById<EditText>(R.id.password).text.toString()
        settings.duckLevel = (findViewById<EditText>(R.id.duck).text.toString().toIntOrNull() ?: 4).coerceIn(0, 60)
        settings.normalVolume = (findViewById<EditText>(R.id.normal).text.toString().toIntOrNull() ?: 20).coerceIn(0, 60)
        settings.useMute = findViewById<CheckBox>(R.id.useMute).isChecked
    }

    /** Answers the design's open question on the real set: does VOLM? return the volume? */
    /**
     * One connection for the whole test, like the service uses. Every raw
     * exchange is logged (escaped bytes), so a screenshot of this log says
     * exactly what the set answers — the first phone test showed a set that
     * ignored per-command connections and says nothing to some commands.
     */
    private fun testTv() {
        log("testing ${settings.host}:${settings.port} …")
        thread {
            fun say(line: String) = runOnUiThread { log(line) }
            val transport = SocketTransport(settings.host, settings.port, 2500, settings.login)
            transport.trace = { say("  $it") }
            val client = SharpIpClient(transport)
            fun confirmed(ok: Boolean) = if (ok) "OK" else "sent, the set said nothing — did it happen?"
            transport.use {
                try {
                    val vol = client.queryVolume()
                    say("VOLM? → " + (vol?.toString() ?: "no answer: the app will use your Normal volume"))
                    val mute = client.queryMute()
                    say("MUTE? → " + (mute?.let { if (it) "muted" else "not muted" } ?: "no answer"))
                    val m1 = client.muteOn(); Thread.sleep(1500); val m2 = client.muteOff()
                    say("MUTE1 → ${confirmed(m1)}; MUTE2 → ${confirmed(m2)}")
                    val back = vol ?: settings.normalVolume
                    val d1 = client.setVolume(settings.duckLevel); Thread.sleep(1500); val d2 = client.setVolume(back)
                    say("VOLM ${settings.duckLevel} → ${confirmed(d1)}; VOLM $back → ${confirmed(d2)} (ducking is what the app does)")
                    say("done — if the sound dipped twice, the TV path works")
                } catch (e: ControlError) {
                    say("FAILED: ${e.message}")
                }
            }
        }
    }

    private fun startWithPermissions(action: String?) {
        pendingAction = action
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1); return }
        val intent = Intent(this, AdHushService::class.java)
        pendingAction?.let { intent.setAction(it) }
        ContextCompat.startForegroundService(this, intent)
        log(if (pendingAction == AdHushService.ACTION_SURVEY) "survey started — 10 minutes of normal TV, phone where it will live" else "started")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) startWithPermissions(pendingAction)
        else log("microphone permission is required")
    }

    private fun serviceAction(action: String) {
        ContextCompat.startForegroundService(this, Intent(this, AdHushService::class.java).setAction(action))
    }

    private fun log(line: String) {
        val v = findViewById<TextView>(R.id.log)
        v.text = "$line\n${v.text}".lines().take(30).joinToString("\n")
    }
}
