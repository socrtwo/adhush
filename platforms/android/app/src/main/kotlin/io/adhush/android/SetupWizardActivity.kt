package io.adhush.android

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import io.adhush.core.Dsp
import io.adhush.core.Gray
import io.adhush.core.LoudnessDetector
import io.adhush.core.SharpIpClient
import io.adhush.core.SocketTransport
import io.adhush.core.Vision
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The optional set-up wizard (ADR 0022): a few questions, a listen to the
 * room with the show on and then with the set muted, a look through the
 * camera at what it would watch, a word with the TV, and a suggested
 * configuration with the reason for every line. "Apply and review" writes
 * the suggestion into Settings and opens the Methods page, where every
 * switch can still be changed by hand. Nothing here is stored but numbers:
 * no audio, no picture.
 *
 * The service is stopped while the wizard runs: two things cannot hold the
 * microphone or the camera at once.
 */
class SetupWizardActivity : AppCompatActivity() {
    private lateinit var settings: Settings
    private lateinit var stepLabel: TextView
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var content: LinearLayout
    private lateinit var back: MaterialButton
    private lateinit var next: MaterialButton
    private val main = Handler(Looper.getMainLooper())
    private var step = 0

    // -- answers and measurements ---------------------------------------------
    private var control = "ip"
    private var host = ""; private var port = 10002; private var loginId = ""; private var password = ""
    private var tvVolume: Int? = null
    private var tvTested: String? = null
    private var placement = "near"        // near | far | elsewhere
    private var channel = ""
    private var news = true
    private var captionsShown = false
    private var cameraFacesTv = true
    private val showDbfs = ArrayList<Double>(); private val showFlat = ArrayList<Double>(); private val showCrest = ArrayList<Double>()
    private val roomDbfs = ArrayList<Double>(); private val roomFlat = ArrayList<Double>()
    private var mic: MicSource? = null
    private var camera: CameraSource? = null
    private var camFrames = 0; private var camSeen = 0; private var camComplete = 0; private var camWidthFrac = 0.0; private var camMaxZoom = 1f
    private var plan: Plan? = null

    /** What the wizard proposes, and why, one line per decision. */
    class Plan {
        val reasons = ArrayList<String>()
        var silence = true; var loudness = true; var fingerprints = true; var clock = true; var jingles = true
        var camera = false; var cameraTarget = "bug"; var cameraZoom = 1f; var captions = false
        var speech = false; var judgeLocal = false; var localSize = "small"; var judgeCloud = false
        var duckLevel = 4; var normalVolume = 20
        var followUp = ArrayList<String>()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.startForegroundService(this, Intent(this, AdHushService::class.java).setAction(AdHushService.ACTION_STOP))
        setContentView(R.layout.activity_setup_wizard)
        settings = Settings(this)
        stepLabel = findViewById(R.id.wizStep); title = findViewById(R.id.wizTitle); body = findViewById(R.id.wizBody)
        content = findViewById(R.id.wizContent); back = findViewById(R.id.wizBack); next = findViewById(R.id.wizNext)
        control = settings.control; host = settings.host; port = settings.port; loginId = settings.loginId; password = settings.password
        channel = settings.channel; captionsShown = settings.captions
        back.setOnClickListener { stopSensors(); if (step == 0) finish() else { step--; show() } }
        next.setOnClickListener { stopSensors(); if (leave()) { step++; show() } }
        show()
    }

    override fun onDestroy() { stopSensors(); super.onDestroy() }

    private fun stopSensors() { mic?.stop(); mic = null; camera?.stop(); camera = null; main.removeCallbacksAndMessages(null) }

    // -- the steps ---------------------------------------------------------------

    private val steps = listOf("Welcome", "Your TV", "Your room", "Listen: the show", "Listen: the room", "The camera", "Suggested set-up")

    private fun show() {
        content.removeAllViews()
        stepLabel.text = "Step ${step + 1} of ${steps.size}"
        title.text = steps[step]
        back.text = if (step == 0) "Not now" else "Back"
        next.text = if (step == steps.size - 1) "Apply and review" else "Next"
        next.isEnabled = true
        when (step) {
            0 -> welcome(); 1 -> tv(); 2 -> room(); 3 -> listen(true); 4 -> listen(false); 5 -> cameraStep(); 6 -> summary()
        }
    }

    /** Collect the step's answers; false keeps the user on the step. */
    private fun leave(): Boolean = when (step) {
        1 -> { readTv(); if (control == "ip" && host.isBlank()) { say("Type the TV's address, or choose another connection."); false } else true }
        2 -> { readRoom(); true }
        6 -> { apply(); false }
        else -> true
    }

    private fun welcome() {
        body.text = "About three minutes. Put the phone where it will live, switch the TV to the channel you watch most, at your normal volume, with a show on (not a commercial). " +
            "The wizard listens to the room, looks through the camera, talks to the TV, then suggests settings — every one of them can be changed afterwards. Nothing is recorded: numbers only."
    }

    private fun tv() {
        body.text = "How does this phone reach the TV?"
        val group = RadioGroup(this)
        val opts = listOf("ip" to "Network (a Sharp with a network port; Wi-Fi)", "serial" to "Serial cable (RS-232C through a USB adapter)", "ir" to "Infrared (the phone's own blaster; volume and mute only)")
        for ((key, label) in opts) group.addView(RadioButton(this).apply { text = label; tag = key; id = View.generateViewId(); isChecked = key == control })
        content.addView(group)
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hostF = field("TV address (for example 192.168.1.12)", host, "host")
        val portF = field("Port", port.toString(), "port", InputType.TYPE_CLASS_NUMBER)
        val loginF = field("Login (blank if the TV has none)", loginId, "login")
        val passF = field("Password", password, "password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        for (f in listOf(hostF, portF, loginF, passF)) fields.addView(f)
        val result = TextView(this).apply { text = tvTested ?: ""; setPadding(0, 8, 0, 8) }
        val test = MaterialButton(this).apply { text = "Ask the TV its volume"; setOnClickListener { readTv(); testTv(result) } }
        fields.addView(test); fields.addView(result)
        content.addView(fields)
        group.setOnCheckedChangeListener { g, id -> control = g.findViewById<RadioButton>(id).tag as String; fields.visibility = if (control == "ip") View.VISIBLE else View.GONE }
        fields.visibility = if (control == "ip") View.VISIBLE else View.GONE
        if (control != "ip") content.addView(note(if (control == "serial") "The cable is tested by Test TV on the Home page once the wizard is done (it needs the USB permission)." else "Infrared only knows volume up, down and mute; the app steps the volume instead of setting it."))
    }

    private fun readTv() {
        host = text("host"); port = text("port").toIntOrNull() ?: 10002; loginId = text("login"); password = text("password")
    }

    private fun testTv(result: TextView) {
        result.text = "asking …"
        Thread {
            val line = try {
                SocketTransport(host, port, login = if (loginId.isBlank() && password.isBlank()) null else Pair(loginId, password)).use { t ->
                    val vol = SharpIpClient(t).queryVolume()
                    tvVolume = vol
                    if (vol != null) "✓ The TV answered: volume $vol. That becomes your Normal volume." else "The TV connected but did not say its volume; the app will use the Normal volume from the TV page."
                }
            } catch (e: Exception) { "✗ No answer from $host:$port — ${e.message}. Check the address, that the TV is on, and its network control setting." }
            tvTested = line
            runOnUiThread { result.text = line }
        }.start()
    }

    private fun room() {
        body.text = "A few questions about the room."
        content.addView(note("Where will the phone sit?"))
        val where = RadioGroup(this)
        for ((key, label) in listOf("near" to "On or beside the TV, facing the screen", "far" to "Across the room, facing the screen (a couch, a shelf)", "elsewhere" to "Somewhere it cannot see the screen")) where.addView(RadioButton(this).apply { text = label; tag = key; id = View.generateViewId(); isChecked = key == placement })
        where.setOnCheckedChangeListener { g, id -> placement = g.findViewById<RadioButton>(id).tag as String }
        content.addView(where)
        content.addView(field("Which channel do you mostly watch? (for the break clock and the AI judges)", channel, "channel"))
        content.addView(note("Is it a news channel with a ticker or headline band along the bottom?"))
        val n = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        n.addView(RadioButton(this).apply { text = "Yes"; id = View.generateViewId(); isChecked = news }); n.addView(RadioButton(this).apply { text = "No"; id = View.generateViewId(); isChecked = !news })
        n.setOnCheckedChangeListener { g, id -> news = g.findViewById<RadioButton>(id).text.toString() == "Yes" }
        content.addView(n)
        content.addView(note("Does the TV show closed captions?"))
        val c = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        c.addView(RadioButton(this).apply { text = "Yes"; id = View.generateViewId(); isChecked = captionsShown }); c.addView(RadioButton(this).apply { text = "No"; id = View.generateViewId(); isChecked = !captionsShown })
        c.setOnCheckedChangeListener { g, id -> captionsShown = g.findViewById<RadioButton>(id).text.toString() == "Yes" }
        content.addView(c)
    }

    private fun readRoom() { channel = text("channel"); cameraFacesTv = placement != "elsewhere" }

    /** Fifteen seconds of the show, then ten of the room with the set muted. Numbers only. */
    private fun listen(show: Boolean) {
        val secs = if (show) 15 else 10
        body.text = if (show) "Keep the show playing at your normal volume and do not talk. Press Listen; the phone measures the room for $secs seconds."
                    else "Now MUTE the TV with its own remote (or pause it) and keep the room as it is — fans, air conditioning, the dishwasher. Press Listen for $secs seconds of the room by itself."
        val meter = TextView(this).apply { textSize = 18f; setPadding(0, 12, 0, 12) }
        val listen = MaterialButton(this).apply { text = "Listen ($secs s)" }
        next.isEnabled = (if (show) showDbfs else roomDbfs).isNotEmpty()
        listen.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 11); return@setOnClickListener }
            val db = if (show) showDbfs else roomDbfs; val fl = if (show) showFlat else roomFlat
            db.clear(); fl.clear(); if (show) showCrest.clear()
            listen.isEnabled = false
            val m = MicSource(this) { block ->
                synchronized(db) { db.add(Dsp.blockDbfs(block.samples)); fl.add(Dsp.spectralFlatness(block.samples)); if (show) showCrest.add(LoudnessDetector.blockCrestDb(block.samples)) }
            }
            try { m.start() } catch (e: Exception) { meter.text = "microphone: ${e.message}"; listen.isEnabled = true; return@setOnClickListener }
            mic = m
            val started = System.currentTimeMillis()
            val tick = object : Runnable {
                override fun run() {
                    val elapsed = (System.currentTimeMillis() - started) / 1000
                    val last = synchronized(db) { db.lastOrNull() }
                    meter.text = "listening ${min(elapsed, secs.toLong())} / $secs s" + (last?.let { "  ·  ${fmt(it)} dBFS" } ?: "")
                    if (elapsed >= secs) {
                        m.stop(); mic = null
                        val n = synchronized(db) { db.size }
                        meter.text = if (n < 10) "too little audio arrived — try again" else (if (show) "the show: median ${fmt(pct(db, 0.5))} dBFS, peaks ${fmt(pct(db, 0.9))}" else "the room: median ${fmt(pct(db, 0.5))} dBFS, flatness ${fmt(pct(fl, 0.5))}")
                        listen.isEnabled = true; next.isEnabled = n >= 10
                    } else main.postDelayed(this, 250)
                }
            }
            main.post(tick)
        }
        content.addView(listen); content.addView(meter)
    }

    private fun cameraStep() {
        if (!cameraFacesTv) { body.text = "You said the phone cannot see the screen, so the camera methods stay off. Press Next."; return }
        body.text = "Point the phone at the TV as it will sit. Press Look: for eight seconds the phone looks for a lit screen and checks that the whole TV is in the picture."
        val preview = LogoOverlayView(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240)) }
        val line = TextView(this).apply { setPadding(0, 8, 0, 8); text = if (camFrames > 0) cameraVerdict() else "" }
        val look = MaterialButton(this).apply { text = "Look (8 s)" }
        look.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 12); return@setOnClickListener }
            camFrames = 0; camSeen = 0; camComplete = 0; camWidthFrac = 0.0
            look.isEnabled = false
            val cam = CameraSource(this, this, intervalMs = 250, wantColor = true) { gray, color -> onCameraFrame(gray, color, preview) }
            camera = cam
            cam.start { msg -> runOnUiThread { line.text = msg } }
            main.postDelayed({ camMaxZoom = cam.maxZoom }, 1500)
            val started = System.currentTimeMillis()
            val tick = object : Runnable {
                override fun run() {
                    val elapsed = (System.currentTimeMillis() - started) / 1000
                    line.text = "looking ${min(elapsed, 8)} / 8 s · screen seen in $camSeen of $camFrames looks"
                    if (elapsed >= 8) { cam.stop(); camera = null; line.text = cameraVerdict(); look.isEnabled = true } else main.postDelayed(this, 250)
                }
            }
            main.post(tick)
        }
        content.addView(preview); content.addView(look); content.addView(line)
    }

    private fun onCameraFrame(gray: Gray, color: ColorFrame?, preview: LogoOverlayView) {
        val screen = Vision.findScreen(gray)
        val complete = screen != null && Vision.screenComplete(screen, gray.w, gray.h)
        camFrames++
        if (screen != null) { camSeen++; camWidthFrac += screen.w.toDouble() / gray.w }
        if (complete) camComplete++
        val shown = if (color != null) Bitmap.createBitmap(color.argb, color.w, color.h, Bitmap.Config.ARGB_8888) else null
        if (shown != null) runOnUiThread { preview.update(shown, gray.w, gray.h, screen, null, screen != null && !complete) }
    }

    private fun cameraVerdict(): String {
        if (camFrames == 0) return ""
        val seen = camSeen.toDouble() / camFrames; val frac = if (camSeen > 0) camWidthFrac / camSeen else 0.0
        return when {
            seen < 0.5 -> "No lit screen found in most looks. The camera methods stay off — try again with a show on, or pick a spot that faces the TV."
            camComplete < camSeen * 0.7 -> "The TV runs off the edge of the picture. Step back or move the phone; the camera methods stay off until the whole TV fits."
            else -> "✓ The whole TV is in view, ${(frac * 100).toInt()} % of the picture wide." + if (frac < 0.4 && camMaxZoom > 1.2f) " It is small: the wizard will suggest zoom ${fmt1(suggestZoom(frac))}×." else ""
        }
    }

    private fun suggestZoom(frac: Double): Float = if (frac <= 0.0) 1f else (0.8 / frac).toFloat().coerceIn(1f, min(4f, camMaxZoom))

    // -- the suggestion ------------------------------------------------------------

    private fun makePlan(): Plan {
        val p = Plan()
        val showP50 = if (showDbfs.isEmpty()) null else pct(showDbfs, 0.5)
        val roomP50 = if (roomDbfs.isEmpty()) null else pct(roomDbfs, 0.5)
        val roomFlatP50 = if (roomFlat.isEmpty()) 0.0 else pct(roomFlat, 0.5)
        val margin = if (showP50 != null && roomP50 != null) showP50 - roomP50 else null
        p.normalVolume = tvVolume ?: settings.normalVolume
        if (tvVolume != null) p.reasons += "Normal volume $tvVolume: what the TV said it was set to."
        val noisyRoom = roomP50 != null && roomP50 > -50.0 && roomFlatP50 >= 0.2
        if (noisyRoom) p.reasons += "The room has steady noise (fans or air) at ${fmt(roomP50!!)} dBFS."
        when {
            margin == null -> { p.reasons += "The room was not measured, so the sound methods keep their defaults."; p.duckLevel = settings.duckLevel }
            margin >= 20 -> { p.reasons += "The show sits ${fmt(margin)} dB above the room: excellent contrast. Quiet gaps and loudness jumps both on; duck to 4."; p.duckLevel = 4 }
            margin >= 10 -> { p.reasons += "The show sits ${fmt(margin)} dB above the room: usable. Quiet gaps and loudness on; duck to 6 so the phone still hears the set while ducked."; p.duckLevel = 6 }
            margin >= 6 -> { p.silence = false; p.duckLevel = 9; p.reasons += "The show sits only ${fmt(margin)} dB above the room. Quiet gaps off (the room fills them); loudness on; duck to 9 so loudness can still hear the ducked set and time the unmute (the mute paradox)." }
            else -> { p.silence = false; p.duckLevel = 10; p.reasons += "The show is barely ${fmt(margin)} dB above the room: the phone cannot hear it well from here. Quiet gaps off, duck to 10; move the phone closer to the TV, or let the camera and remembered breaks carry the decision."; p.followUp += "Move the phone nearer the TV and run the wizard again." }
        }
        p.reasons += "Remembered breaks, the break clock and break jingles on: they cost nothing and learn by themselves."
        // Camera
        val seen = if (camFrames > 0) camSeen.toDouble() / camFrames else 0.0
        val frac = if (camSeen > 0) camWidthFrac / camSeen else 0.0
        val whole = camFrames > 0 && seen >= 0.5 && camComplete >= camSeen * 0.7
        if (whole) {
            p.camera = true; p.cameraTarget = if (news) "ticker" else "bug"; p.cameraZoom = suggestZoom(frac)
            p.reasons += "The camera sees the whole TV: the ${if (news) "news ticker" else "channel bug"} method on" + (if (p.cameraZoom > 1.05f) ", zoom ${fmt1(p.cameraZoom)}×" else "") + "."
            val template = File(filesDir, if (news) AdHushService.TICKER_FILE else AdHushService.LOGO_FILE).isFile
            if (!template) p.followUp += "Methods → Camera setup → Watch 45 s, so the camera learns where the ${if (news) "ticker" else "bug"} is."
            if (captionsShown) {
                if (frac >= 0.35 || p.cameraZoom > 1.05f) { p.captions = true; p.reasons += "Captions are on the screen and the TV is big enough in the picture: On-screen captions on." }
                else p.reasons += "Captions are shown but the TV is too small in the picture to read them; zoom in on Camera setup, then switch On-screen captions on."
            }
        } else if (cameraFacesTv && camFrames > 0) p.reasons += "The camera could not see the whole TV, so the camera methods stay off."
        else if (!cameraFacesTv) p.reasons += "The phone cannot see the screen: camera methods off."
        else p.reasons += "The camera was not tried: camera methods off (Camera setup can turn them on later)."
        // Words
        val speechInstalled = SpeechSource.isInstalled(this)
        if (margin != null && margin >= 10 && speechInstalled) { p.speech = true; p.reasons += "The speech model is installed and the room is quiet enough: Spoken words on." }
        else if (margin != null && margin < 10) p.reasons += "Spoken words off: over this much room noise the recogniser mishears" + (if (p.captions) "; captions do the same job without ears." else ".")
        else if (!speechInstalled) p.reasons += "Spoken words off until its model is downloaded (Methods → Speech → Download)."
        // Judges
        val mem = ActivityManager.MemoryInfo().also { (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }.totalMem / (1024.0 * 1024 * 1024)
        p.localSize = when { mem >= 11.0 -> "large"; mem >= 5.5 -> "medium"; else -> "small" }
        val words = p.speech || p.captions
        val tier = LocalJudge.tier(p.localSize)
        if (words && LocalJudge.isInstalled(this, tier)) { p.judgeLocal = true; p.reasons += "Local AI on, the ${tier.label} model (this phone has ${fmt1(mem)} GB): a free tie-breaker over the words." }
        else if (words) p.reasons += "This phone has ${fmt1(mem)} GB, enough for the ${tier.label} local model; download it under Methods → Local AI and switch it on for a free tie-breaker."
        if (words && settings.claudeKey.isNotBlank()) { p.judgeCloud = true; p.reasons += "A Claude key is stored: Ask Claude on as a tie-breaker (a few cents an hour)." }
        if (!words) p.reasons += "No words method is on, so the AI judges stay off: they need Spoken words or On-screen captions to read."
        if (control == "ip" && host.isBlank()) p.followUp += "Type the TV's address on the TV page."
        return p
    }

    private fun summary() {
        val p = makePlan(); plan = p
        body.text = "Here is what the wizard suggests, and why. Apply and review writes it and opens the Methods page, where every switch can be changed."
        val on = { b: Boolean -> if (b) "on" else "off" }
        content.addView(note("Quiet gaps ${on(p.silence)} · Loudness ${on(p.loudness)} · Remembered breaks ${on(p.fingerprints)} · Camera ${on(p.camera)}${if (p.camera) " (${p.cameraTarget})" else ""} · Captions ${on(p.captions)} · Speech ${on(p.speech)} · Local AI ${on(p.judgeLocal)} · Claude ${on(p.judgeCloud)} · Clock ${on(p.clock)} · Jingles ${on(p.jingles)}\nConnection: $control · Normal volume ${p.normalVolume} · Duck to ${p.duckLevel}", bold = true))
        for (r in p.reasons) content.addView(note("• $r"))
        if (p.followUp.isNotEmpty()) { content.addView(note("After this:", bold = true)); for (f in p.followUp) content.addView(note("→ $f")) }
    }

    private fun apply() {
        val p = plan ?: return
        settings.control = control; settings.host = host; settings.port = port; settings.loginId = loginId; settings.password = password
        settings.normalVolume = p.normalVolume; settings.duckLevel = p.duckLevel
        settings.silence = p.silence; settings.loudness = p.loudness; settings.fingerprints = p.fingerprints; settings.clock = p.clock; settings.jingles = p.jingles
        settings.camera = p.camera; settings.cameraTarget = p.cameraTarget; settings.cameraZoom = p.cameraZoom; settings.captions = p.captions
        settings.speech = p.speech; settings.judgeLocal = p.judgeLocal; settings.localModelSize = p.localSize; settings.judgeCloud = p.judgeCloud
        if (channel.isNotBlank()) settings.channel = channel
        settings.wizardOffered = true
        AppLog.i("wizard", "applied: " + p.reasons.joinToString(" | "))
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_PAGE, "methods").putExtra(MainActivity.EXTRA_NOTE, "the wizard's settings are in — check them here, then Start" + (if (p.followUp.isNotEmpty()) "; next: " + p.followUp.first() else "")))
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (!ok) say(if (requestCode == 11) "The microphone permission is needed to listen." else "The camera permission is needed to look.")
    }

    // -- small view helpers --------------------------------------------------------

    private fun field(hint: String, value: String, tag: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(this).apply { this.hint = hint; setText(value); this.tag = tag; inputType = type }

    private fun text(tag: String): String = content.findViewWithTag<EditText>(tag)?.text?.toString()?.trim() ?: ""

    private fun note(s: String, bold: Boolean = false): TextView = TextView(this).apply { text = s; setPadding(0, 6, 0, 6); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD) }

    private fun say(s: String) { com.google.android.material.snackbar.Snackbar.make(content, s, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show() }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun pct(values: List<Double>, p: Double): Double {
        val sorted = synchronized(values) { values.toDoubleArray() }; sorted.sort()
        if (sorted.isEmpty()) return 0.0
        return sorted[(p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)]
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun fmt1(v: Float) = String.format(Locale.US, "%.1f", v)
    private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
}
