package io.adhush.android

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.media.projection.MediaProjectionManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
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
                AdHushService.BROADCAST_STATUS -> showStatus(i.getStringExtra("text") ?: "", i.getBooleanExtra("running", AdHushService.running != null), i.getBooleanExtra("ducked", false), i.getBooleanExtra("teaching", false))
                AdHushService.BROADCAST_TEST -> testLine(i.getStringExtra("line") ?: "")
                AdHushService.BROADCAST_SURVEY -> {
                    log("— survey saved; press Share survey to send the numbers (no audio is stored) —")
                    (i.getStringExtra("summary") ?: "").lines().reversed().forEach { log(it) }
                }
            }
        }
    }
    private var pendingAction: String? = null

    /** Android's "record or cast" consent, which is how one app may hear another's sound (ADR 0018). */
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode != Activity.RESULT_OK || data == null) { log("screen capture was not allowed — stream learning needs it to hear the player"); return@registerForActivityResult }
        save()
        val i = Intent(this, AdHushService::class.java).setAction(AdHushService.ACTION_STREAM_START)
            .putExtra(AdHushService.EXTRA_RESULT_CODE, r.resultCode).putExtra(AdHushService.EXTRA_RESULT_DATA, data)
        ContextCompat.startForegroundService(this, i)
        log("stream learning started — play the channel's live stream in Chrome and leave it playing")
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importMemory(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        AppLog.init(this)
        load()
        val pages = mapOf(R.id.nav_home to R.id.pageHome, R.id.nav_tv to R.id.pageTv, R.id.nav_senses to R.id.pageSenses, R.id.nav_help to R.id.pageHelp, R.id.nav_log to R.id.pageLog)
        val nav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.nav)
        nav.setOnItemSelectedListener { item ->
            for ((menuId, pageId) in pages) findViewById<View>(pageId).visibility = if (menuId == item.itemId) View.VISIBLE else View.GONE
            if (item.itemId != R.id.nav_home) save()            // leaving a page keeps what was typed
            if (item.itemId == R.id.nav_log) refreshLog()
            true
        }
        buildHelp()
        findViewById<Button>(R.id.shareLog).setOnClickListener { shareLog() }
        findViewById<Button>(R.id.clearLog).setOnClickListener { AppLog.clear(); refreshLog(); log("log cleared") }
        findViewById<Button>(R.id.save).setOnClickListener { save(); log("saved") }
        findViewById<Button>(R.id.test).setOnClickListener { runTest() }
        findViewById<Button>(R.id.testHome).setOnClickListener { runTest() }
        findViewById<Button>(R.id.start).setOnClickListener { save(); if (checkBeforeStart()) startWithPermissions(null) }
        findViewById<Button>(R.id.stop).setOnClickListener { serviceAction(AdHushService.ACTION_STOP) }
        findViewById<Button>(R.id.notAd).setOnClickListener { serviceAction(AdHushService.ACTION_NOT_AD) }
        findViewById<Button>(R.id.isAd).setOnClickListener { serviceAction(AdHushService.ACTION_IS_AD) }
        findViewById<Button>(R.id.showBack).setOnClickListener { serviceAction(AdHushService.ACTION_SHOW_BACK) }
        for ((id, secs) in listOf(R.id.duck30 to 30, R.id.duck60 to 60, R.id.duck90 to 90, R.id.duck120 to 120))
            findViewById<Button>(id).setOnClickListener { serviceAction(AdHushService.ACTION_DUCK_FOR) { it.putExtra(AdHushService.EXTRA_SECONDS, secs) } }
        findViewById<Button>(R.id.openRemote).setOnClickListener { save(); startActivity(Intent(this, RemoteActivity::class.java)) }
        findViewById<Button>(R.id.survey).setOnClickListener { save(); if (checkBeforeStart()) startWithPermissions(AdHushService.ACTION_SURVEY) }
        findViewById<Button>(R.id.share).setOnClickListener { shareSurvey() }
        findViewById<Button>(R.id.streamStart).setOnClickListener { startStreamLearning() }
        findViewById<Button>(R.id.streamStop).setOnClickListener { serviceAction(AdHushService.ACTION_STOP) }
        findViewById<Button>(R.id.shareMemory).setOnClickListener { shareMemory() }
        findViewById<Button>(R.id.importMemory).setOnClickListener { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
        findViewById<Button>(R.id.cameraSetup).setOnClickListener { save(); startActivity(Intent(this, CameraSetupActivity::class.java)) }
        findViewById<Button>(R.id.speechModel).setOnClickListener { downloadSpeechModel() }
        findViewById<Button>(R.id.learnScripts).setOnClickListener { serviceAction(AdHushService.ACTION_LEARN_SCRIPTS) }
        findViewById<Button>(R.id.localModel).setOnClickListener { downloadLocalModel() }
        findViewById<android.widget.RadioGroup>(R.id.localModelChoice).setOnCheckedChangeListener { _, _ -> save(); findViewById<Button>(R.id.localModel).text = localModelLabel() }
        findViewById<Button>(R.id.testClaude).setOnClickListener { save(); testJudge(cloud = true) }
        findViewById<Button>(R.id.testLocal).setOnClickListener { save(); testJudge(cloud = false) }
        // The ⓘ buttons: one explanation each.
        val info = mapOf(
            R.id.infoMethods to Help.METHODS, R.id.infoSilence to Help.SILENCE, R.id.infoLoudness to Help.LOUDNESS, R.id.infoFingerprints to Help.FINGERPRINTS,
            R.id.infoCamera to Help.CAMERA, R.id.infoSpeech to Help.SPEECH, R.id.infoCaptions to Help.CAPTIONS,
            R.id.infoTeach to Help.TEACH, R.id.infoTest to Help.TEST, R.id.infoTv to Help.DUCK,
            R.id.infoClaude to Help.CLAUDE, R.id.infoLocal to Help.LOCAL,
            R.id.infoClock to Help.CLOCK, R.id.infoManual to Help.TIMED, R.id.infoStream to Help.STREAM,
        )
        for ((id, topic) in info) findViewById<View>(id).setOnClickListener { Help.show(this, topic) }
        for (id in listOf(R.id.silence, R.id.loudness, R.id.fingerprints, R.id.camera, R.id.speech, R.id.captions, R.id.judgeCloud, R.id.judgeLocal, R.id.clockOn))
            findViewById<CompoundButton>(id).setOnCheckedChangeListener { _, _ -> methodsNote() }
        findViewById<android.widget.RadioGroup>(R.id.cameraTarget).setOnCheckedChangeListener { _, id ->
            findViewById<Button>(R.id.cameraSetup).text = if (id == R.id.targetTicker) "Camera setup — find the news ticker (in colour)" else "Camera setup — find the bug (in colour)"
        }
        findViewById<android.widget.RadioGroup>(R.id.speechModelChoice).setOnCheckedChangeListener { _, _ -> save(); findViewById<Button>(R.id.speechModel).text = speechModelLabel() }
        showStatus(AdHushService.lastText, AdHushService.running != null, false, false)
    }

    /** The Help page: every explanation, in full, one card each. */
    private fun buildHelp() {
        val list = findViewById<LinearLayout>(R.id.helpList)
        list.removeAllViews()
        for (t in Help.ALL) {
            val card = MaterialCardView(this).apply { radius = 24f; strokeWidth = 1; setContentPadding(40, 32, 40, 32) }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply { text = t.title; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium) })
            col.addView(TextView(this).apply { text = t.body; setPadding(0, 12, 0, 0); setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium) })
            card.addView(col)
            list.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 32 })
        }
    }

    /** Refuse to start with nothing to go on or nowhere to send commands; say why on the Home page. */
    private fun checkBeforeStart(): Boolean {
        if (settings.methodsOn == 0) { log("nothing to go on: switch on at least one method under Methods"); showStatus("no method is on — see Methods", false, false, false); return false }
        if (settings.control == "ip" && settings.host.isBlank()) { log("no TV address: type it on the TV page and press Save"); showStatus("no TV address — see the TV page", false, false, false); return false }
        return true
    }

    private fun methodsNote() {
        val ids = listOf(R.id.silence, R.id.loudness, R.id.fingerprints, R.id.camera, R.id.speech, R.id.captions, R.id.judgeCloud, R.id.judgeLocal, R.id.clockOn)
        val n = ids.count { findViewById<CompoundButton>(it).isChecked }
        val onlyClock = n == 1 && findViewById<CompoundButton>(R.id.clockOn).isChecked
        val ai = findViewById<CompoundButton>(R.id.judgeCloud).isChecked || findViewById<CompoundButton>(R.id.judgeLocal).isChecked
        val words = findViewById<CompoundButton>(R.id.speech).isChecked || findViewById<CompoundButton>(R.id.captions).isChecked
        findViewById<TextView>(R.id.methodsNote).text = when {
            n == 0 -> "⚠ Nothing is switched on. The app cannot work with no method — turn on at least one."
            ai && !words -> "⚠ An AI judge is on but has no words to read: turn on Spoken words or On-screen captions too."
            onlyClock -> "⚠ Only the break clock is on. It never ducks alone — it tips the balance for the others. Turn on at least one more."
            else -> "$n of 9 methods on. At least one must be on. A coloured dot means that method is running right now."
        }
    }

    private fun speechModelLabel(): String {
        val which = if (findViewById<android.widget.RadioGroup>(R.id.speechModelChoice).checkedRadioButtonId == R.id.speechMedium) "medium" else "small"
        return if (SpeechSource.isInstalled(this)) "Speech model ($which): installed" else "Download the $which speech model (${SpeechSource.SIZES_MB[which]} MB)"
    }

    /** The status line, the card's colour, the chips and the buttons all follow the service. */
    private fun showStatus(text: String, running: Boolean, ducked: Boolean, teaching: Boolean) {
        findViewById<TextView>(R.id.status).text = text
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).subtitle = text.substringBefore(" · ").take(40)
        val ducking = ducked || text.startsWith("DUCKED") || text.startsWith("TEACHING")
        val colour = when { !running -> R.color.adhush_stopped_container; teaching || text.startsWith("TEACHING") -> R.color.adhush_teaching_container; ducking -> R.color.adhush_ducked_container; else -> R.color.adhush_program_container }
        findViewById<MaterialCardView>(R.id.statusCard).setCardBackgroundColor(ContextCompat.getColor(this, colour))
        val start = findViewById<MaterialButton>(R.id.start); val stop = findViewById<MaterialButton>(R.id.stop)
        start.text = if (running) "● Running" else "▶ Start"
        start.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, if (running) R.color.adhush_program else R.color.adhush_primary))
        stop.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, if (running) R.color.adhush_ducked else R.color.adhush_secondary_container))
        stop.setTextColor(ContextCompat.getColor(this, if (running) android.R.color.white else R.color.adhush_on_secondary_container))
        val r = AdHushService.running
        val on = mapOf(
            "silence" to (r?.silence == true), "loudness" to (r?.loudness == true), "fingerprints" to (r?.fingerprints == true),
            "camera" to (r?.logo == true), "speech" to (r?.speech == true), "captions" to (r?.captions == true),
            "claude" to (r?.cloud == true), "local" to (r?.local == true), "clock" to (r?.clock == true), "stream" to (r?.stream == true),
        )
        val colours = mapOf("silence" to R.color.method_silence, "loudness" to R.color.method_loudness, "fingerprints" to R.color.method_fingerprints, "camera" to R.color.method_camera, "speech" to R.color.method_speech, "captions" to R.color.method_captions, "claude" to R.color.method_claude, "local" to R.color.method_local, "clock" to R.color.method_clock, "stream" to R.color.method_stream)
        val chips = mapOf("silence" to R.id.chipSilence, "loudness" to R.id.chipLoudness, "fingerprints" to R.id.chipFingerprints, "camera" to R.id.chipCamera, "speech" to R.id.chipSpeech, "captions" to R.id.chipCaptions, "claude" to R.id.chipClaude, "local" to R.id.chipLocal, "clock" to R.id.chipClock, "stream" to R.id.chipStream)
        val dots = mapOf("silence" to R.id.dotSilence, "loudness" to R.id.dotLoudness, "fingerprints" to R.id.dotFingerprints, "camera" to R.id.dotCamera, "speech" to R.id.dotSpeech, "captions" to R.id.dotCaptions, "claude" to R.id.dotClaude, "local" to R.id.dotLocal, "clock" to R.id.dotClock, "stream" to R.id.dotStream)
        for ((k, chipId) in chips) {
            val active = running && on[k] == true
            val c = ContextCompat.getColor(this, if (active) colours[k]!! else R.color.method_off)
            findViewById<Chip>(chipId).apply { chipBackgroundColor = ColorStateList.valueOf(if (active) c else ContextCompat.getColor(this@MainActivity, R.color.adhush_stopped_container)); setTextColor(if (active) android.graphics.Color.WHITE else c); alpha = if (active) 1f else 0.7f }
            findViewById<TextView>(dots[k]!!).setTextColor(c)
        }
        // Buttons of a running method are tinted with its colour.
        tint(R.id.cameraSetup, running && on["camera"] == true, R.color.method_camera)
        tint(R.id.speechModel, running && on["speech"] == true, R.color.method_speech)
        tint(R.id.learnScripts, running && (on["speech"] == true || on["captions"] == true), R.color.method_speech)
        tint(R.id.survey, running && on["loudness"] == true, R.color.method_loudness)
        tint(R.id.testClaude, running && on["claude"] == true, R.color.method_claude)
        tint(R.id.localModel, running && on["local"] == true, R.color.method_local)
        findViewById<TextView>(R.id.dotTeach).setTextColor(ContextCompat.getColor(this, if (teaching || text.startsWith("TEACHING")) R.color.adhush_teaching else if (running) R.color.adhush_program else R.color.method_off))
        findViewById<TextView>(R.id.dotTest).setTextColor(ContextCompat.getColor(this, if (running) R.color.adhush_program else R.color.method_off))
        findViewById<TextView>(R.id.dotTv).setTextColor(ContextCompat.getColor(this, if (running) R.color.adhush_program else R.color.method_off))
        findViewById<TextView>(R.id.dotMethods).setTextColor(ContextCompat.getColor(this, if (running) R.color.adhush_program else R.color.method_off))
        val notSetUp = when {
            r?.logoNotSetUp == true -> "camera is on but the bug is not set up yet — press Camera setup"
            r?.speechNoModel == true -> "speech is on but the model is not downloaded — press Download speech model"
            r?.cloudNoKey == true -> "Ask Claude is on but there is no API key — paste one under Methods"
            r?.localNoModel == true -> "the local AI is on but its model is not downloaded — press Download the local AI model"
            r?.judgesDeaf == true -> "an AI judge is on but has no words to read — turn on Spoken words or On-screen captions"
            else -> null
        }
        notSetUp?.let { if (it != lastWarning) { lastWarning = it; log("⚠ $it") } }
    }
    private var lastWarning = ""

    private fun tint(id: Int, active: Boolean, colour: Int) {
        val b = findViewById<MaterialButton>(id)
        b.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(this, if (active) colour else R.color.method_off))
        b.strokeWidth = if (active) 4 else 0
    }

    private fun refreshLog() { findViewById<TextView>(R.id.log).text = AppLog.tail(150).ifEmpty { "(the log is empty)" } }

    /** The rolling log file, handed to mail, Drive or messages so a crash can be diagnosed. */
    private fun shareLog() {
        val file = AppLog.file(this)
        if (!file.isFile) { log("the log is empty"); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, "AdHush error log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share error log"))
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(AdHushService.BROADCAST_STATUS).apply { addAction(AdHushService.BROADCAST_SURVEY); addAction(AdHushService.BROADCAST_TEST) }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshLog()
        findViewById<Button>(R.id.speechModel).text = speechModelLabel()
        showStatus(AdHushService.lastText, AdHushService.running != null, false, false)
    }

    /** One-time 40 MB download of the offline recogniser's English model, into app-private storage. */
    private fun downloadSpeechModel() {
        save()
        if (SpeechSource.isInstalled(this)) { log("speech model is already installed"); return }
        log("downloading the ${settings.speechModel} speech model (${SpeechSource.SIZES_MB[settings.speechModel]} MB) …")
        thread {
            try {
                var last = -1
                SpeechSource.install(this) { p -> if (p / 10 != last / 10) { last = p; runOnUiThread { log("speech model: $p%") } } }
                runOnUiThread { log("speech model installed — switch on Spoken words and Start"); findViewById<Button>(R.id.speechModel).text = speechModelLabel() }
            } catch (e: Exception) { AppLog.e("speech", "model download failed", e); runOnUiThread { log("download failed: ${e.message}") } }
        }
    }

    /** The download button's caption for the chosen size. */
    private fun localModelLabel(): String {
        val tier = LocalJudge.tier(settings.localModelSize)
        return if (LocalJudge.isInstalled(this, tier)) "Local AI model: ${tier.label} installed" else "Download ${tier.label} (${tier.sizeMb} MB)"
    }

    /** One-time download of the chosen on-phone language model into app-private storage; resumes if interrupted. */
    private fun downloadLocalModel() {
        save()
        val tier = LocalJudge.tier(settings.localModelSize)
        if (LocalJudge.isInstalled(this, tier)) { log("${tier.label} is already installed"); return }
        val url = settings.localModelUrl.ifBlank { tier.url }
        log("downloading ${tier.label} (${tier.sizeMb} MB) — keep the app open, Wi-Fi recommended …")
        thread {
            try {
                var last = -1
                LocalJudge.install(this, url, { p -> if (p / 5 != last / 5) { last = p; runOnUiThread { log("${tier.label}: $p%") } } }, tier)
                runOnUiThread { log("${tier.label} installed — switch on Local AI and Start"); findViewById<Button>(R.id.localModel).text = localModelLabel() }
            } catch (e: Exception) { AppLog.e("judge", "model download failed", e); runOnUiThread { log("download failed: ${e.message} — press again to resume") } }
        }
    }

    /** Ask the chosen AI about two sample transcripts, so you can see what it answers and how long it takes. */
    private fun testJudge(cloud: Boolean) {
        val samples = listOf(
            "ask your doctor if it is right for you side effects may include headache nausea and dizziness do not take if you are allergic call now for a free trial",
            "the senate is expected to vote later today on the spending bill and we will bring you that as it happens joining me now is our correspondent on capitol hill",
        )
        val judge: io.adhush.core.TranscriptJudge = try {
            if (cloud) { if (settings.claudeKey.isBlank()) { log("paste an Anthropic API key first"); return }; ClaudeJudge(settings.claudeKey, settings.claudeModel) }
            else { if (!LocalJudge.isInstalled(this)) { log("download the local AI model first"); return }; LocalJudge.APP_CONTEXT = applicationContext; LocalJudge(LocalJudge.modelFile(this)) }
        } catch (e: Exception) { AppLog.e("judge", "could not start the judge", e); log("could not start: ${e.message}"); return }
        log(if (cloud) "asking ${settings.claudeModel} …" else "asking the local AI (the first answer takes longer while the model loads) …")
        thread {
            try {
                for (t in samples) {
                    val t0 = System.currentTimeMillis()
                    val v = judge.judge(t, settings.channel)
                    val ms = System.currentTimeMillis() - t0
                    runOnUiThread { log("  \"${t.take(50)}…\" → " + (v?.let { "${if (it.commercial) "COMMERCIAL" else "SHOW"} ${"%.0f".format(it.confidence * 100)}%: ${it.reason}" } ?: "no clear answer") + " ($ms ms)") }
                }
                runOnUiThread { log("done — the first should say COMMERCIAL, the second SHOW") }
            } catch (e: Exception) { AppLog.e("judge", "test failed", e); runOnUiThread { log("✗ FAILED: ${e.message}") } }
            finally { (judge as? AutoCloseable)?.let { runCatching { it.close() } } }
        }
    }

    /** Stream learning (ADR 0018): ask for the playback-capture consent, then the service does the rest. */
    private fun startStreamLearning() {
        if (Build.VERSION.SDK_INT < 29) { log("stream learning needs Android 10 or newer — play the stream on a laptop next to the phone and use Start instead"); return }
        if (AdHushService.running != null) { log("stop AdHush first, then start stream learning"); return }
        save()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 2); log("allow the microphone, then press Start learning again"); return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    /** The phone's memory — breaks, scripts, clock — as one zip, handed to whatever the user picks. */
    private fun shareMemory() {
        val file = try { Memory.export(this) } catch (e: Exception) { log("could not build the memory file: ${e.message}"); return }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, "AdHush memory")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share this phone's memory"))
        log("memory file built (${file.length() / 1024} KB) — send it to the other phone and Import it there")
    }

    private fun importMemory(uri: android.net.Uri) {
        if (AdHushService.running != null) { log("stop AdHush first — the memory is read when it starts"); return }
        thread {
            val result = try { contentResolver.openInputStream(uri)?.use { Memory.import(this, it) } ?: "could not open the file" }
            catch (e: Exception) { AppLog.e("memory", "import failed", e); "import failed: ${e.message}" }
            runOnUiThread { log(result) }
        }
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

    override fun onPause() { unregisterReceiver(receiver); super.onPause() }

    private fun load() {
        findViewById<EditText>(R.id.host).setText(settings.host)
        findViewById<EditText>(R.id.port).setText(settings.port.toString())
        findViewById<EditText>(R.id.login).setText(settings.loginId)
        findViewById<EditText>(R.id.password).setText(settings.password)
        findViewById<EditText>(R.id.duck).setText(settings.duckLevel.toString())
        findViewById<EditText>(R.id.normal).setText(settings.normalVolume.toString())
        findViewById<CompoundButton>(R.id.useMute).isChecked = settings.useMute
        findViewById<CompoundButton>(R.id.silence).isChecked = settings.silence
        findViewById<CompoundButton>(R.id.loudness).isChecked = settings.loudness
        findViewById<CompoundButton>(R.id.fingerprints).isChecked = settings.fingerprints
        findViewById<CompoundButton>(R.id.camera).isChecked = settings.camera
        findViewById<CompoundButton>(R.id.speech).isChecked = settings.speech
        findViewById<CompoundButton>(R.id.captions).isChecked = settings.captions
        findViewById<CompoundButton>(R.id.judgeCloud).isChecked = settings.judgeCloud
        findViewById<CompoundButton>(R.id.judgeLocal).isChecked = settings.judgeLocal
        findViewById<CompoundButton>(R.id.clockOn).isChecked = settings.clock
        findViewById<EditText>(R.id.claudeKey).setText(settings.claudeKey)
        findViewById<EditText>(R.id.channel).setText(settings.channel)
        findViewById<android.widget.RadioGroup>(R.id.claudeModel).check(when (settings.claudeModel) { "claude-sonnet-5" -> R.id.modelSonnet; "claude-opus-5" -> R.id.modelOpus; else -> R.id.modelHaiku })
        findViewById<android.widget.RadioGroup>(R.id.judgeMode).check(if (settings.judgeMode == "always") R.id.modeAlways else R.id.modeTie)
        findViewById<android.widget.RadioGroup>(R.id.cameraTarget).check(if (settings.cameraTarget == "ticker") R.id.targetTicker else R.id.targetBug)
        findViewById<android.widget.RadioGroup>(R.id.speechModelChoice).check(if (settings.speechModel == "medium") R.id.speechMedium else R.id.speechSmall)
        findViewById<Button>(R.id.cameraSetup).text = if (settings.cameraTarget == "ticker") "Camera setup — find the news ticker (in colour)" else "Camera setup — find the bug (in colour)"
        findViewById<android.widget.RadioGroup>(R.id.localModelChoice).check(when (settings.localModelSize) { "medium" -> R.id.localMedium; "large" -> R.id.localLarge; else -> R.id.localSmall })
        findViewById<Button>(R.id.localModel).text = localModelLabel()
        findViewById<Button>(R.id.speechModel).text = speechModelLabel()
        findViewById<android.widget.RadioGroup>(R.id.control).check(when (settings.control) { "serial" -> R.id.controlSerial; "ir" -> R.id.controlIr; else -> R.id.controlIp })
        findViewById<EditText>(R.id.irAddress).setText(settings.irAddress.toString())
        findViewById<EditText>(R.id.irVolUp).setText("%02X".format(settings.irVolumeUp))
        findViewById<EditText>(R.id.irVolDown).setText("%02X".format(settings.irVolumeDown))
        methodsNote()
    }

    private fun save() {
        settings.host = findViewById<EditText>(R.id.host).text.toString().trim()
        settings.port = findViewById<EditText>(R.id.port).text.toString().toIntOrNull() ?: Aquos.DEFAULT_PORT
        settings.loginId = findViewById<EditText>(R.id.login).text.toString().trim()
        settings.password = findViewById<EditText>(R.id.password).text.toString()
        settings.duckLevel = (findViewById<EditText>(R.id.duck).text.toString().toIntOrNull() ?: 4).coerceIn(0, 60)
        settings.normalVolume = (findViewById<EditText>(R.id.normal).text.toString().toIntOrNull() ?: 20).coerceIn(0, 60)
        settings.useMute = findViewById<CompoundButton>(R.id.useMute).isChecked
        settings.silence = findViewById<CompoundButton>(R.id.silence).isChecked
        settings.loudness = findViewById<CompoundButton>(R.id.loudness).isChecked
        settings.fingerprints = findViewById<CompoundButton>(R.id.fingerprints).isChecked
        settings.camera = findViewById<CompoundButton>(R.id.camera).isChecked
        settings.speech = findViewById<CompoundButton>(R.id.speech).isChecked
        settings.captions = findViewById<CompoundButton>(R.id.captions).isChecked
        settings.judgeCloud = findViewById<CompoundButton>(R.id.judgeCloud).isChecked
        settings.judgeLocal = findViewById<CompoundButton>(R.id.judgeLocal).isChecked
        settings.clock = findViewById<CompoundButton>(R.id.clockOn).isChecked
        settings.claudeKey = findViewById<EditText>(R.id.claudeKey).text.toString().trim()
        settings.channel = findViewById<EditText>(R.id.channel).text.toString().trim()
        settings.claudeModel = when (findViewById<android.widget.RadioGroup>(R.id.claudeModel).checkedRadioButtonId) { R.id.modelSonnet -> "claude-sonnet-5"; R.id.modelOpus -> "claude-opus-5"; else -> "claude-haiku-4-5" }
        settings.judgeMode = if (findViewById<android.widget.RadioGroup>(R.id.judgeMode).checkedRadioButtonId == R.id.modeAlways) "always" else "tie"
        settings.cameraTarget = if (findViewById<android.widget.RadioGroup>(R.id.cameraTarget).checkedRadioButtonId == R.id.targetTicker) "ticker" else "bug"
        settings.speechModel = if (findViewById<android.widget.RadioGroup>(R.id.speechModelChoice).checkedRadioButtonId == R.id.speechMedium) "medium" else "small"
        settings.localModelSize = when (findViewById<android.widget.RadioGroup>(R.id.localModelChoice).checkedRadioButtonId) { R.id.localMedium -> "medium"; R.id.localLarge -> "large"; else -> "small" }
        settings.control = when (findViewById<android.widget.RadioGroup>(R.id.control).checkedRadioButtonId) { R.id.controlSerial -> "serial"; R.id.controlIr -> "ir"; else -> "ip" }
        settings.irAddress = findViewById<EditText>(R.id.irAddress).text.toString().trim().toIntOrNull()?.coerceIn(0, 31) ?: 1
        settings.irVolumeUp = findViewById<EditText>(R.id.irVolUp).text.toString().trim().toIntOrNull(16)?.coerceIn(0, 255) ?: 0x14
        settings.irVolumeDown = findViewById<EditText>(R.id.irVolDown).text.toString().trim().toIntOrNull(16)?.coerceIn(0, 255) ?: 0x15
        if (settings.host.isBlank() && settings.control == "ip") log("⚠ the TV address is blank")
    }

    /** Test mode: the same exchange as a real duck, over whichever connection is chosen, printed line by line. */
    private fun runTest() {
        save()
        findViewById<TextView>(R.id.testResult).text = ""
        findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.nav).selectedItemId = R.id.nav_home
        // The Sharp allows one control connection: while the service holds it, the test must go through the service.
        if (AdHushService.running != null) { testLine("AdHush is running — testing through its own connection"); serviceAction(AdHushService.ACTION_TEST); return }
        when (settings.control) { "serial" -> testSerial(); "ir" -> testIr(); else -> testTv() }
    }

    /** A test line goes to the Home card, the Log page and the log file. */
    private fun testLine(line: String) {
        log(line)
        val v = findViewById<TextView>(R.id.testResult)
        v.text = (v.text.toString() + "\n" + line).trim().lines().takeLast(14).joinToString("\n")
    }

    /**
     * One connection for the whole test, like the service uses. Every raw
     * exchange is logged (escaped bytes), so a screenshot of this log says
     * exactly what the set answers — the first phone test showed a set that
     * ignored per-command connections and says nothing to some commands.
     */
    private fun testTv() {
        if (settings.host.isBlank()) { testLine("no TV address — type it on the TV page first"); return }
        testLine("testing ${settings.host}:${settings.port} …")
        thread {
            fun say(line: String) = runOnUiThread { testLine(line) }
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
                    say("✓ done — if the sound dipped twice, the TV path works")
                } catch (e: ControlError) {
                    say("✗ FAILED: ${e.message}")
                }
            }
        }
    }

    /** Serial: the same command sequence as the network test, over the cable, after USB permission. */
    private fun testSerial() {
        val transport = SerialTransport(this, 2500)
        val device = transport.device()
        if (device == null) { testLine("no USB serial adapter found — plug the cable in (OTG) and try again"); return }
        val usb = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        if (!usb.hasPermission(device)) {
            val pi = android.app.PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB).setPackage(packageName), android.app.PendingIntent.FLAG_MUTABLE)
            usb.requestPermission(device, pi)
            testLine("allow USB access in the dialog, then press Test TV again"); return
        }
        testLine("testing the serial cable …")
        thread {
            fun say(line: String) = runOnUiThread { testLine(line) }
            transport.trace = { say("  $it") }
            val client = SharpIpClient(transport)
            fun confirmed(ok: Boolean) = if (ok) "OK" else "sent, no answer"
            transport.use {
                try {
                    val vol = client.queryVolume(); say("VOLM? → " + (vol?.toString() ?: "no answer: Normal volume will be used"))
                    val m1 = client.muteOn(); Thread.sleep(1500); val m2 = client.muteOff(); say("MUTE1 → ${confirmed(m1)}; MUTE2 → ${confirmed(m2)}")
                    val back = vol ?: settings.normalVolume
                    val d1 = client.setVolume(settings.duckLevel); Thread.sleep(1500); val d2 = client.setVolume(back)
                    say("VOLM ${settings.duckLevel} → ${confirmed(d1)}; VOLM $back → ${confirmed(d2)}")
                    say("✓ done — if the sound dipped twice, the cable works")
                } catch (e: ControlError) { say("✗ FAILED: ${e.message}") }
            }
        }
    }

    /** Infrared: three presses down, three up — watch the set's volume bar. */
    private fun testIr() {
        val sender = IrKeySender(this, settings.irAddress, settings.irVolumeUp, settings.irVolumeDown)
        if (!sender.available) { testLine("this phone has no infrared blaster"); return }
        testLine("testing infrared: volume down ×3, then up ×3 — point the top edge of the phone at the TV")
        thread {
            try {
                sender.press(io.adhush.core.TvKey.VOLUME_DOWN, 3); Thread.sleep(1500); sender.press(io.adhush.core.TvKey.VOLUME_UP, 3)
                runOnUiThread { testLine("sent. Did the volume bar move down and back up? If not, the codes are wrong: see the README for alternatives") }
            } catch (e: ControlError) { runOnUiThread { testLine("✗ FAILED: ${e.message}") } }
        }
    }

    private fun startWithPermissions(action: String?) {
        pendingAction = action
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        val wantedAll = if (settings.camera || settings.captions) wanted + Manifest.permission.CAMERA else wanted
        val missing = wantedAll.filter { ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1); return }
        val intent = Intent(this, AdHushService::class.java)
        pendingAction?.let { intent.setAction(it) }
        ContextCompat.startForegroundService(this, intent)
        log(when (pendingAction) {
            AdHushService.ACTION_SURVEY -> "survey started — 10 minutes of normal TV, phone where it will live"
            else -> "started"
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) { if (grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) startStreamLearning() else log("microphone permission is required"); return }
        if (requestCode == 1 && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) startWithPermissions(pendingAction)
        else log("microphone permission is required")
    }

    companion object { const val ACTION_USB = "io.adhush.android.USB_PERMISSION" }

    /** Control actions need a running service; the notification and tile go through the same door. */
    private fun serviceAction(action: String, extras: (Intent) -> Unit = {}) {
        val control = action in listOf(AdHushService.ACTION_NOT_AD, AdHushService.ACTION_IS_AD, AdHushService.ACTION_SHOW_BACK, AdHushService.ACTION_LEARN_SCRIPTS, AdHushService.ACTION_TEST, AdHushService.ACTION_DUCK_FOR)
        if (control && AdHushService.running == null) { log("not running — press Start first"); return }
        ContextCompat.startForegroundService(this, Intent(this, AdHushService::class.java).setAction(action).also(extras))
    }

    /** Everything the app tells the user also goes to the error log, and to the Log page. */
    private fun log(line: String) {
        AppLog.i("app", line)
        val v = findViewById<TextView>(R.id.log)
        v.text = (v.text.toString() + "\n" + line).lines().takeLast(150).joinToString("\n")
    }
}
