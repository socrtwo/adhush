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
import io.adhush.core.ControlError
import io.adhush.core.SharpIpClient
import io.adhush.core.SocketTransport
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var settings: Settings
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            findViewById<TextView>(R.id.status).text = intent?.getStringExtra("text") ?: ""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        load()
        findViewById<Button>(R.id.save).setOnClickListener { save(); log("saved") }
        findViewById<Button>(R.id.test).setOnClickListener { save(); testTv() }
        findViewById<Button>(R.id.start).setOnClickListener { save(); startWithPermissions() }
        findViewById<Button>(R.id.stop).setOnClickListener { serviceAction(AdHushService.ACTION_STOP) }
        findViewById<Button>(R.id.notAd).setOnClickListener { serviceAction(AdHushService.ACTION_NOT_AD) }
        findViewById<Button>(R.id.isAd).setOnClickListener { serviceAction(AdHushService.ACTION_IS_AD) }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(this, receiver, IntentFilter(AdHushService.BROADCAST_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
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
        settings.port = findViewById<EditText>(R.id.port).text.toString().toIntOrNull() ?: io.adhush.core.Aquos.DEFAULT_PORT
        settings.loginId = findViewById<EditText>(R.id.login).text.toString().trim()
        settings.password = findViewById<EditText>(R.id.password).text.toString()
        settings.duckLevel = (findViewById<EditText>(R.id.duck).text.toString().toIntOrNull() ?: 4).coerceIn(0, 60)
        settings.normalVolume = (findViewById<EditText>(R.id.normal).text.toString().toIntOrNull() ?: 20).coerceIn(0, 60)
        settings.useMute = findViewById<CheckBox>(R.id.useMute).isChecked
    }

    /** Answers the design's open question on the real set: does VOLM? return the volume? */
    private fun testTv() {
        log("testing ${settings.host}:${settings.port} …")
        thread {
            val client = SharpIpClient(SocketTransport(settings.host, settings.port, 2500, settings.login))
            val lines = ArrayList<String>()
            try {
                val vol = client.queryVolume()
                lines.add("VOLM? → " + (vol?.toString() ?: "no answer (ERR): the app will use your Normal volume"))
                val mute = client.queryMute()
                lines.add("MUTE? → " + (mute?.let { if (it) "muted" else "not muted" } ?: "no answer"))
                client.muteOn(); Thread.sleep(1500); client.muteOff()
                lines.add("MUTE 1 then MUTE 2 → OK (did the sound drop for a second?)")
                if (vol != null) { client.setVolume(settings.duckLevel); Thread.sleep(1500); client.setVolume(vol); lines.add("VOLM ${settings.duckLevel} then VOLM $vol → OK (ducking works)") }
            } catch (e: ControlError) {
                lines.add("FAILED: ${e.message}")
            }
            runOnUiThread { lines.forEach { log(it) } }
        }
    }

    private fun startWithPermissions() {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1); return }
        ContextCompat.startForegroundService(this, Intent(this, AdHushService::class.java))
        log("started")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) startWithPermissions()
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
