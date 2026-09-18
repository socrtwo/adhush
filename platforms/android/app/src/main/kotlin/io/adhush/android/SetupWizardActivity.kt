package io.adhush.android

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
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
import io.adhush.core.FoundTv
import io.adhush.core.IrCodeSet
import io.adhush.core.IrCodeSets
import io.adhush.core.LgWebOs
import io.adhush.core.RokuEcp
import io.adhush.core.SamsungTizen
import io.adhush.core.SonyBravia
import io.adhush.core.TvFinder
import io.adhush.core.TvPath
import io.adhush.core.TvPathKind
import io.adhush.core.UpnpRenderer
import io.adhush.core.VizioSmartCast
import io.adhush.core.Gray
import io.adhush.core.LoudnessDetector
import io.adhush.core.KeySender
import io.adhush.core.SharpIpClient
import io.adhush.core.TvKey
import io.adhush.core.Vision
import java.io.File
import java.net.Inet4Address
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
    // The TV check (0.25.0, any brand since 0.26.0): what each path said, and what worked.
    private var netLine = "not tried yet"
    private var serialLine = "not tried yet"
    private var irLine = "not tried yet"
    private var netWorks = false
    private var serialWorks = false
    private var irWorks: Boolean? = null
    private var irAsked = false
    @Volatile private var checking = false
    // What Find my TV found (ADR 0025) and which way in was proven.
    private var tvs: List<FoundTv> = emptyList()
    private var chosen: TvPath? = null
    private var chosenBrand = ""
    private var chosenModel = ""
    private var upnpUrl = ""
    private var sonyPsk = ""
    private var samsungToken = ""
    private var lgKey = ""
    private var vizioToken = ""
    private var vizioDeviceId = ""
    private var vizioReqToken = ""
    private var pendingKey: ArrayDeque<TvPath> = ArrayDeque()
    private var askingPath: TvPath? = null
    private var irSets: List<IrCodeSet> = IrCodeSets.KNOWN
    private var irIndex = 0
    private var irChosen: String? = null
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // The USB dialog answered: try the cable again.
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) Thread { checkSerial(); pickControl() }.start()
            else { serialLine = "USB access refused — the cable cannot be used"; refreshTvRows() }
        }
    }
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
        sonyPsk = settings.sonyPsk; samsungToken = settings.samsungToken; lgKey = settings.lgClientKey; vizioToken = settings.vizioToken; vizioDeviceId = settings.vizioDeviceId; upnpUrl = settings.upnpControlUrl
        chosenBrand = settings.tvBrand; chosenModel = settings.tvModel
        back.setOnClickListener { stopSensors(); if (step == 0) finish() else { step--; show() } }
        next.setOnClickListener { stopSensors(); if (leave()) { step++; show() } }
        show()
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(this, usbReceiver, IntentFilter(MainActivity.ACTION_USB), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() { runCatching { unregisterReceiver(usbReceiver) }; super.onPause() }

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
        1 -> { readTv(); if (control !in setOf("serial", "ir", "upnp") && host.isBlank()) { say("Press Find my TV, type the TV's address, or choose another connection."); false } else true }
        2 -> { readRoom(); true }
        6 -> { apply(); false }
        else -> true
    }

    private fun welcome() {
        body.text = "About three minutes. Put the phone where it will live, switch the TV to the channel you watch most, at your normal volume, with a show on (not a commercial). " +
            "The wizard listens to the room, looks through the camera, talks to the TV, then suggests settings — every one of them can be changed afterwards. Nothing is recorded: numbers only."
    }

    private fun tv() {
        body.text = "Press Find my TV. The phone asks every set on the Wi-Fi who it is — Sharp, Samsung, LG, Sony, Roku TV, Vizio, any DLNA set — and tries each way in until one works; then the serial cable if one is plugged in; then the infrared blaster, one brand's codes at a time, asking you whether the sound dipped. Whatever works is chosen; you can still change it."
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fields.addView(field("TV address (blank: search the Wi-Fi)", host, "host"))
        fields.addView(field("Sharp port", port.toString(), "port", InputType.TYPE_CLASS_NUMBER))
        fields.addView(field("Sharp login (blank if none)", loginId, "login"))
        fields.addView(field("Sharp password", password, "password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        fields.addView(field("Sony pre-shared key (only for a Bravia; set it under Network → IP Control)", sonyPsk, "psk"))
        content.addView(fields)
        val find = MaterialButton(this).apply { text = "Find my TV"; isEnabled = !checking }
        content.addView(find)
        content.addView(note("Wi-Fi:", bold = true)); content.addView(TextView(this).apply { tag = "rowNet"; text = netLine })
        // Vizio: the PIN the set shows during pairing
        val pinRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; tag = "pinRow"; visibility = if (vizioReqToken.isNotBlank() && vizioToken.isBlank()) View.VISIBLE else View.GONE }
        val pinField = EditText(this).apply { hint = "PIN on the Vizio's screen"; inputType = InputType.TYPE_CLASS_NUMBER; tag = "pin" }
        pinRow.addView(pinField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pinRow.addView(MaterialButton(this).apply { text = "Pair"; setOnClickListener { val pin = pinField.text.toString().trim(); if (pin.isNotBlank()) Thread { vizioPair(pin) }.start() } })
        content.addView(pinRow)
        // A key-only path (Samsung, Roku, Vizio) has no readback: the owner says whether the set muted.
        val askRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; tag = "askRow"; visibility = if (askingPath != null) View.VISIBLE else View.GONE }
        askRow.addView(note("Did the TV mute and unmute?  "))
        askRow.addView(MaterialButton(this).apply { text = "Yes"; setOnClickListener { keyPathAnswered(true) } })
        askRow.addView(MaterialButton(this).apply { text = "No, try the next"; setOnClickListener { keyPathAnswered(false) } })
        content.addView(askRow)
        content.addView(note("Serial cable:", bold = true)); content.addView(TextView(this).apply { tag = "rowSerial"; text = serialLine })
        content.addView(note("Infrared:", bold = true)); content.addView(TextView(this).apply { tag = "rowIr"; text = irLine })
        val irRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; tag = "irAsk"; visibility = if (irAsked && irWorks == null) View.VISIBLE else View.GONE }
        irRow.addView(note("Did the sound cut out and come back?  "))
        irRow.addView(MaterialButton(this).apply { text = "Yes"; setOnClickListener { irWorks = true; irChosen = irSets[irIndex].name; irLine = "✓ The set answered the ${irSets[irIndex].brand} codes: infrared reaches it (volume and mute only)"; irAsked = false; refreshTvRows(); pickControl() } })
        irRow.addView(MaterialButton(this).apply { text = "No, try the next"; setOnClickListener { irIndex++; if (irIndex < irSets.size) Thread { fireIr() }.start() else { irWorks = false; irAsked = false; irLine = "✗ No brand's codes moved the set — point the phone's top edge at the TV, or type the codes on the TV page"; refreshTvRows(); pickControl() } } })
        content.addView(irRow)
        content.addView(note("Chosen connection:", bold = true))
        val group = RadioGroup(this).apply { tag = "controlGroup" }
        val opts = listOf("ip" to "Network: a Sharp", "serial" to "Serial cable (RS-232C through USB)", "ir" to "Infrared (the phone's blaster; volume and mute only)", "found" to "Network: the brand path found above")
        for ((key, label) in opts) group.addView(RadioButton(this).apply { text = label; tag = key; id = View.generateViewId(); isChecked = key == radioFor(control) })
        group.setOnCheckedChangeListener { g, id -> val k = g.findViewById<RadioButton>(id).tag as String; control = if (k == "found") (chosen?.kind?.wire ?: control) else k }
        content.addView(group)
        find.setOnClickListener {
            readTv()
            if (checking) return@setOnClickListener
            checking = true; find.isEnabled = false
            netLine = "looking …"; serialLine = "looking …"; irLine = "looking …"; irWorks = null; irAsked = false; chosen = null; askingPath = null; pendingKey.clear(); netWorks = false; refreshTvRows()
            Thread {
                findTvs()
                if (pendingKey.isEmpty()) afterNetwork() else askNextKeyPath()
            }.start()
        }
    }

    private fun radioFor(c: String): String = if (c in setOf("ip", "serial", "ir")) c else "found"

    /** Serial, then infrared, then the choice; runs after the network paths are settled. */
    private fun afterNetwork() {
        refreshTvRows()
        checkSerial(); refreshTvRows()
        irSets = IrCodeSets.ordered(chosenBrand.ifBlank { tvs.firstOrNull()?.brand }); irIndex = 0
        checkInfrared()
        checking = false
        pickControl()
        runOnUiThread { if (step == 1) content.childViews().filterIsInstance<MaterialButton>().firstOrNull { it.text.toString() == "Find my TV" }?.isEnabled = true }
    }

    private fun refreshTvRows() {
        runOnUiThread {
            if (step != 1) return@runOnUiThread
            content.findViewWithTag<TextView>("rowNet")?.text = netLine
            content.findViewWithTag<TextView>("rowSerial")?.text = serialLine
            content.findViewWithTag<TextView>("rowIr")?.text = irLine
            content.findViewWithTag<View>("irAsk")?.visibility = if (irAsked && irWorks == null) View.VISIBLE else View.GONE
            content.findViewWithTag<View>("askRow")?.visibility = if (askingPath != null) View.VISIBLE else View.GONE
            content.findViewWithTag<View>("pinRow")?.visibility = if (vizioReqToken.isNotBlank() && vizioToken.isBlank()) View.VISIBLE else View.GONE
            content.findViewWithTag<EditText>("host")?.let { if (it.text.toString().trim() != host) it.setText(host) }
        }
    }

    /** What worked wins: an exact-volume network path, then a key path, then the cable, then the blaster. */
    private fun pickControl() {
        control = when {
            netWorks && chosen != null -> chosen!!.kind.wire
            serialWorks -> "serial"
            irWorks == true -> "ir"
            else -> control
        }
        runOnUiThread {
            if (step != 1) return@runOnUiThread
            val group = content.findViewWithTag<RadioGroup>("controlGroup") ?: return@runOnUiThread
            val want = radioFor(control)
            for (i in 0 until group.childCount) { val b = group.getChildAt(i) as RadioButton; if (b.tag == want && !b.isChecked) b.isChecked = true }
        }
    }

    private fun readTv() {
        host = text("host"); port = text("port").toIntOrNull() ?: 10002; loginId = text("login"); password = text("password"); sonyPsk = text("psk")
    }

    private val login: Pair<String, String>? get() = if (loginId.isBlank() && password.isBlank()) null else Pair(loginId, password)

    /** The phone's IPv4 on the active network as "a.b.c", or null. */
    private fun wifiSubnet(): String? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork ?: return null) ?: return null
        val addr = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress } ?: return null
        val parts = addr.hostAddress?.split(".") ?: return null
        return if (parts.size == 4) parts.take(3).joinToString(".") else null
    }

    // -- the network: every brand -------------------------------------------------------

    private fun findTvs() {
        val base = wifiSubnet()
        if (base == null && host.isBlank()) { netLine = "✗ Not on Wi-Fi: join the TV's network to search it, or type the address"; return }
        netLine = "asking every set on the Wi-Fi who it is (about ten seconds) …"; refreshTvRows()
        val log = ArrayList<String>()
        val found = ArrayList<FoundTv>()
        try { found.addAll(TvFinder.find(base, port, login, { log.add(it) })) } catch (e: Exception) { log.add("search: ${e.message}") }
        if (host.isNotBlank() && found.none { it.ip == host }) TvFinder.probeHost(host, port, login)?.let { found.add(0, it) }
        tvs = found
        AppLog.i("wizard", "find my tv: " + log.joinToString(" | "))
        if (tvs.isEmpty()) { netLine = "✗ No set answered on this Wi-Fi. A Sharp needs IP Control on in its menu; a Samsung, LG or Sony answers only when it is on (not in standby); a Roku TV answers always."; return }
        val lines = ArrayList<String>()
        for (t in tvs) lines.add("• ${t.brand.ifBlank { "unknown maker" }} ${t.model.ifBlank { t.name }} at ${t.ip}: " + (if (t.paths.isEmpty()) "no way in found" else t.paths.joinToString { it.kind.label }))
        netLine = "Found:\n" + lines.joinToString("\n") + "\nTrying each way in …"; refreshTvRows()
        // Exact-volume paths first, on every set; key-only paths queue up for the owner's yes/no.
        for (t in tvs) {
            for (p in t.ordered()) {
                if (p.kind.exact) {
                    if (tryExact(t, p)) { netLine += "\n✓ ${t.brand} at ${t.ip} answers over ${p.kind.label}" + (tvVolume?.let { " (volume $it)" } ?: ""); return }
                } else pendingKey.addLast(p)
            }
        }
        if (pendingKey.isEmpty()) netLine += "\n✗ Nothing answered with its volume" else netLine += "\nNo set reads its volume back; trying the key paths next, one at a time — watch the TV"
    }

    /** A path that reads the volume back proves itself. */
    private fun tryExact(t: FoundTv, p: TvPath): Boolean {
        refreshTvRows()
        val vol: Int? = try {
            when (p.kind) {
                TvPathKind.SHARP -> p.detail.toIntOrNull()
                TvPathKind.UPNP -> UpnpRenderer(p.detail).getVolume()
                TvPathKind.SONY -> if (sonyPsk.isBlank()) { netLine += "\n• Sony at ${t.ip}: type the pre-shared key above and press Find my TV again"; null } else SonyBravia(t.ip, sonyPsk).getVolume()
                TvPathKind.LG -> { netLine += "\n• LG at ${t.ip}: allow AdHush on the TV now (a prompt is on the screen) …"; refreshTvRows(); val lg = LgWebOs(t.ip, lgKey.ifBlank { null }); lg.connect()?.let { lgKey = it }; val v = lg.getVolume(); lg.close(); v }
                else -> null
            }
        } catch (e: Exception) { netLine += "\n• ${p.kind.label} at ${t.ip}: ${e.message}"; null }
        if (vol == null) return false
        chosen = p; chosenBrand = t.brand; chosenModel = t.model.ifBlank { t.name }; host = t.ip; netWorks = true; tvVolume = vol
        if (p.kind == TvPathKind.UPNP) upnpUrl = p.detail
        return true
    }

    /** Fire MUTE twice over the next key-only path and ask; runs until one is confirmed or the queue is empty. */
    private fun askNextKeyPath() {
        val p = pendingKey.removeFirstOrNull()
        if (p == null) { askingPath = null; afterNetwork(); return }
        val t = tvs.firstOrNull { it.ip == p.ip } ?: FoundTv(p.ip, "", "", "")
        try {
            val sender: KeySender = when (p.kind) {
                TvPathKind.SAMSUNG -> { netLine += "\n• Samsung at ${p.ip}: allow AdHush on the TV if it asks …"; refreshTvRows(); SamsungTizen(p.ip, samsungToken.ifBlank { null }).also { it.connect()?.let { tok -> samsungToken = tok } } }
                TvPathKind.ROKU -> RokuEcp(p.ip)
                TvPathKind.VIZIO -> {
                    if (vizioToken.isBlank()) {
                        if (vizioDeviceId.isBlank()) vizioDeviceId = "adhush-" + java.util.UUID.randomUUID().toString().take(8)
                        vizioReqToken = VizioSmartCast(p.ip, null).pairStart(vizioDeviceId)
                        askingPath = p
                        netLine += "\n• Vizio at ${p.ip}: type the PIN on its screen and press Pair"; refreshTvRows(); return
                    }
                    VizioSmartCast(p.ip, vizioToken)
                }
                else -> { askNextKeyPath(); return }
            }
            askingPath = p
            netLine += "\n• ${t.brand.ifBlank { p.kind.label }} at ${p.ip}: sending MUTE, then MUTE again in two seconds — watch the TV"; refreshTvRows()
            sender.press(TvKey.MUTE, 1); Thread.sleep(2000); sender.press(TvKey.MUTE, 1)
            (sender as? AutoCloseable)?.close()
            refreshTvRows()
        } catch (e: Exception) { netLine += "\n• ${p.kind.label} at ${p.ip}: ${e.message}"; askingPath = null; askNextKeyPath() }
    }

    private fun vizioPair(pin: String) {
        val p = askingPath ?: return
        try {
            vizioToken = VizioSmartCast(p.ip, null).pair(vizioDeviceId, pin, vizioReqToken)
            vizioReqToken = ""
            netLine += "\n• Vizio paired; sending MUTE twice — watch the TV"; refreshTvRows()
            val v = VizioSmartCast(p.ip, vizioToken)
            v.press(TvKey.MUTE, 1); Thread.sleep(2000); v.press(TvKey.MUTE, 1)
            refreshTvRows()
        } catch (e: Exception) { netLine += "\n• Vizio: ${e.message}"; vizioReqToken = ""; askingPath = null; refreshTvRows(); Thread { askNextKeyPath() }.start() }
    }

    private fun keyPathAnswered(yes: Boolean) {
        val p = askingPath ?: return
        askingPath = null
        if (yes) {
            val t = tvs.firstOrNull { it.ip == p.ip }
            chosen = p; chosenBrand = t?.brand ?: p.kind.label; chosenModel = t?.let { it.model.ifBlank { it.name } } ?: ""; host = p.ip; netWorks = true
            netLine += "\n✓ ${chosenBrand} at ${p.ip} answers over ${p.kind.label} (volume is stepped: it has no readback)"
            pendingKey.clear()
            Thread { afterNetwork() }.start()
        } else Thread { askNextKeyPath() }.start()
    }

    // -- the cable and the blaster ---------------------------------------------------------

    private fun checkSerial() {
        serialWorks = false
        val transport = SerialTransport(this, 2500)
        val device = transport.device()
        if (device == null) { serialLine = "no USB serial adapter plugged in (an OTG cable with an FTDI, Prolific, CH340 or CP210x adapter; Sharp's RS-232C only)"; return }
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usb.hasPermission(device)) {
            val pi = android.app.PendingIntent.getBroadcast(this, 0, Intent(MainActivity.ACTION_USB).setPackage(packageName), android.app.PendingIntent.FLAG_MUTABLE)
            usb.requestPermission(device, pi)
            serialLine = "adapter found — allow USB access in the dialog and the cable is tried again"; return
        }
        serialLine = "adapter found; asking the TV over the cable …"; refreshTvRows()
        val vol = try { transport.use { SharpIpClient(it).queryVolume() } } catch (e: Exception) { null }
        if (vol != null) { serialWorks = true; if (tvVolume == null) tvVolume = vol; serialLine = "✓ The TV answered over the cable: volume $vol" }
        else serialLine = "✗ Adapter found but the TV did not answer — is the cable in the TV's RS-232C port, and the set on?"
    }

    private fun checkInfrared() {
        val probe = IrKeySender(this, irSets[0])
        if (!probe.available) { irLine = "this phone has no infrared blaster"; irWorks = false; refreshTvRows(); return }
        fireIr()
    }

    /** MUTE twice with the current code set, then the question. */
    private fun fireIr() {
        val set = irSets[irIndex]
        irLine = "blaster found — trying ${set.brand} codes (${irIndex + 1} of ${irSets.size}): MUTE, then MUTE again in two seconds; watch the TV …"; irAsked = false; refreshTvRows()
        try {
            val ir = IrKeySender(this, set)
            ir.press(TvKey.MUTE, 1); Thread.sleep(2000); ir.press(TvKey.MUTE, 1)
            irLine = "${set.brand} codes fired twice (${irIndex + 1} of ${irSets.size})."; irAsked = true
        } catch (e: Exception) { irLine = "✗ blaster error: ${e.message}"; irWorks = false }
        refreshTvRows()
    }

    private fun android.view.ViewGroup.childViews(): List<View> = (0 until childCount).map { getChildAt(it) }

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
        val path = TvPathKind.ofWire(control)
        p.reasons += when {
            control == "serial" -> if (serialWorks) "Serial cable: the TV answered over the cable." else "Serial cable: chosen; Test TV on the Home page confirms it."
            control == "ir" -> if (irWorks == true) "Infrared: the set answered the ${irSets.getOrNull(irIndex)?.brand ?: "chosen"} codes; the app steps the volume with the blaster." else "Infrared: chosen; the blaster was not confirmed."
            path != null && netWorks -> "$chosenBrand $chosenModel at $host over ${path.label}" + (if (path.exact) ": the phone sets the volume exactly." else ": volume is stepped with key presses, since this path has no readback.")
            path != null -> "${path.label}: chosen, but not confirmed — run Find my TV again with the set on."
            else -> "Network: chosen, but the TV did not answer yet — check the address on the TV page."
        }
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
        settings.tvBrand = chosenBrand; settings.tvModel = chosenModel
        if (upnpUrl.isNotBlank()) settings.upnpControlUrl = upnpUrl
        if (sonyPsk.isNotBlank()) settings.sonyPsk = sonyPsk
        if (samsungToken.isNotBlank()) settings.samsungToken = samsungToken
        if (lgKey.isNotBlank()) settings.lgClientKey = lgKey
        if (vizioToken.isNotBlank()) { settings.vizioToken = vizioToken; settings.vizioDeviceId = vizioDeviceId }
        irChosen?.let { settings.irCodeSet = it }
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
