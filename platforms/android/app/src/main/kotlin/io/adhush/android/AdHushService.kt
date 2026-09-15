package io.adhush.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Activity
import android.app.Service
import android.content.Context
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.adhush.core.AdState
import io.adhush.core.Assembly
import io.adhush.core.BadgeDetector
import io.adhush.core.ClockDetector
import io.adhush.core.FileClockStore
import io.adhush.core.FileJingleStore
import io.adhush.core.JingleDetector
import io.adhush.core.RemoteKey
import io.adhush.core.press
import io.adhush.core.ControlError
import io.adhush.core.DuckController
import io.adhush.core.HANDHELD_LOGO_CONFIG
import io.adhush.core.LogoAbsenceDetector
import io.adhush.core.LogoFinder
import io.adhush.core.LogoTemplate
import io.adhush.core.FileScriptStore
import io.adhush.core.JudgeConfig
import io.adhush.core.JudgeDetector
import io.adhush.core.TranscriptDetector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.adhush.core.StepVolumeController
import io.adhush.core.Engine
import io.adhush.core.FileFingerprintStore
import io.adhush.core.MuteController
import io.adhush.core.RoomSurvey
import io.adhush.core.Override as CoreOverride
import io.adhush.core.SharpController
import io.adhush.core.SharpIpClient
import io.adhush.core.SocketTransport
import io.adhush.core.Status
import java.io.File
import java.util.concurrent.Executors

/**
 * The always-running part: a microphone foreground service that owns the
 * engine, the mic, and the TV controller. Everything that touches the network
 * runs on `io`; the engine is fed from the mic thread. The fail-safe rules from
 * docs/android-app-design.md live here: restore on every exit path, watch the
 * mic, let the remote win.
 */
class AdHushService : Service(), LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private lateinit var settings: Settings
    private var camera: CameraSource? = null
    private var speech: SpeechSource? = null
    private var captionReader: CaptionSource? = null
    private var cloudJudge: ClaudeJudge? = null
    private var localJudge: LocalJudge? = null
    /** The judges' questions run here: slow (a network call, or seconds of CPU) and never on the audio thread. */
    private val think = Executors.newSingleThreadExecutor { r -> Thread(r, "adhush-judge").apply { priority = Thread.MIN_PRIORITY } }
    private var scripts: FileScriptStore? = null
    private var lastRepeatLearnAt = 0L
    @Volatile private var finder: LogoFinder? = null
    @Volatile private var finderStartedAt = 0L
    private var engine: Engine? = null
    private var controller: DuckController? = null
    private var transport: AutoCloseable? = null
    private var mic: MicSource? = null
    /** Stream learning (ADR 0018): the phone's own playback instead of the mic, and no TV. */
    private var stream: StreamSource? = null
    private var projection: MediaProjection? = null
    private var streamStore: FileFingerprintStore? = null
    private var badgeReader: BadgeReader? = null
    private var streamAdsAtStart = 0
    private var streamScriptsAtStart = 0
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastStatus: Status? = null
    @Volatile private var survey: RoomSurvey? = null
    private val ticker = object : Runnable {
        override fun run() { tick(); main.postDelayed(this, POLL_MS) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registry.currentState = Lifecycle.State.RESUMED   // CameraX binds to this service's lifetime
        settings = Settings(this)
        createChannel()
        emergencyRestore = { controller?.restore() }   // the app-wide crash handler calls this before reporting
        AppLog.i("service", "created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start — including control actions delivered by startForegroundService —
        // must be answered with startForeground, or Android 8+ kills the app.
        if (lastText == "stopped") lastText = "starting…"
        val action = intent?.action
        // A control action (Is an ad from the tile or a button) before the app has ever been
        // granted the microphone: Android refuses a microphone-type foreground service and
        // the refusal is a crash on the main thread. Say so in the log and stop instead.
        try { startForegroundWithType(buildNotification(lastText), action == ACTION_STREAM_START || stream != null) } catch (e: SecurityException) {
            AppLog.w("service", "cannot run in the foreground yet (${e.message?.substringBefore(':')}) — open the app, allow the microphone, press Start")
            stopSelf(); return START_NOT_STICKY
        }
        if (action == ACTION_STREAM_START) { startStream(intent); return START_STICKY }
        if (engine == null && action != null && action != ACTION_SURVEY) {  // a control action, but nothing is running
            if (action != ACTION_STOP) update("not running — press Start in the app")
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_NOT_AD -> { io.execute { engine?.rejectAd(now()) }; return START_STICKY }
            ACTION_IS_AD -> { io.execute { engine?.confirmAd(now()) }; return START_STICKY }
            ACTION_SHOW_BACK -> { io.execute { engine?.showIsBack(now()); refresh() }; return START_STICKY }
            ACTION_DUCK_FOR -> {
                val seconds = intent.getIntExtra(EXTRA_SECONDS, 60).coerceIn(5, 600)
                io.execute { if (engine?.duckFor(now(), seconds.toDouble()) == true) refresh() else main.post { update("could not duck (an override is on?)") } }
                return START_STICKY
            }
            ACTION_KEY -> { intent.getStringExtra(EXTRA_KEY)?.let { k -> io.execute { pressKey(k) } }; return START_STICKY }
            ACTION_DUCK_MORE -> { io.execute { if (engine?.extendDuck(now(), 30.0) == true) refresh() }; return START_STICKY }
            ACTION_RESTORE -> { io.execute { runCatching { controller?.restore() }; refresh() }; return START_STICKY }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_SURVEY -> {
                if (engine == null) start()
                if (engine != null && survey == null) { survey = RoomSurvey(SURVEY_S); update("surveying the room: 0:00 / ${clock(SURVEY_S)}") }
                return START_STICKY
            }
            ACTION_TEST -> { io.execute { runTest() }; return START_STICKY }
            ACTION_LEARN_SCRIPTS -> {
                io.execute {
                    val n = engine?.learnScriptsFromTranscript() ?: 0
                    main.post { update(if (engine == null) "not running" else "repetition learning: $n new script(s), ${scripts?.count() ?: 0} total") }
                }
                return START_STICKY
            }
            ACTION_CAMERA_SETUP -> {
                if (engine == null) start()
                if (camera == null) { update("camera is off — tick 'Use the camera' and Start again"); return START_STICKY }
                if (finder == null) { finder = LogoFinder(); finderStartedAt = System.currentTimeMillis(); update("setting up the camera: keep a show on, 0 / ${SETUP_S} s") }
                return START_STICKY
            }
        }
        if (engine == null) start()
        return START_STICKY
    }

    private fun start() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            update("microphone permission missing"); stopSelf(); return
        }
        // The 0.13 log: a blank address was tried as ":10002" every five seconds. Refuse to start instead.
        if (settings.control == "ip" && settings.host.isBlank()) { update("no TV address — type it on the TV page and Save"); stopSelf(); return }
        if (settings.methodsOn == 0) { update("no method is switched on — turn one on under Methods"); stopSelf(); return }
        val ctl: DuckController = when (settings.control) {
            "serial" -> {
                val t = SerialTransport(this); transport = t
                SharpController(SharpIpClient(t), PrefsDuckPersistence(this), duckLevel = settings.duckLevel, normalVolume = settings.normalVolume, useMuteInstead = settings.useMute)
            }
            "ir" -> {
                transport = null
                StepVolumeController(IrKeySender(this, settings.irAddress, settings.irVolumeUp, settings.irVolumeDown), PrefsDuckPersistence(this), duckLevel = settings.duckLevel, normalVolume = settings.normalVolume)
            }
            else -> {
                val t = SocketTransport(settings.host, settings.port, 2500, settings.login); transport = t
                SharpController(SharpIpClient(t), PrefsDuckPersistence(this), duckLevel = settings.duckLevel, normalVolume = settings.normalVolume, useMuteInstead = settings.useMute)
            }
        }
        controller = ctl
        val store = FileFingerprintStore(File(filesDir, "ads.tsv"))
        val cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val logoWanted = settings.camera && cameraOk
        val captionsWanted = settings.captions && cameraOk
        val cameraWanted = logoWanted || captionsWanted
        val watchTicker = settings.cameraTarget == "ticker"   // not `ticker`, the poll Runnable
        val logo = if (logoWanted) LogoTemplate.load(File(filesDir, if (watchTicker) TICKER_FILE else LOGO_FILE))?.let {
            if (watchTicker) LogoAbsenceDetector(HANDHELD_LOGO_CONFIG.copy(searchPx = 4), it, name = "ticker_absence", noun = "ticker") else LogoAbsenceDetector(HANDHELD_LOGO_CONFIG, it)
        } else null
        val speechWanted = settings.speech && SpeechSource.isInstalled(this)
        val cloudWanted = settings.judgeCloud && settings.claudeKey.isNotBlank()
        val localWanted = settings.judgeLocal && LocalJudge.isInstalled(this)
        val breakClock = if (settings.clock) ClockDetector(FileClockStore(File(filesDir, CLOCK_FILE))) else null   // not `clock`, the m:ss helper
        val jingle = if (settings.jingles) JingleDetector(FileJingleStore(File(filesDir, JINGLES_FILE))) else null
        val scriptStore = if (speechWanted || captionsWanted || cloudWanted || localWanted) FileScriptStore(File(filesDir, SCRIPTS_FILE)).also { scripts = it } else null
        val transcript = if (speechWanted) scriptStore?.let { TranscriptDetector(it) } else null
        val captions = if (captionsWanted) scriptStore?.let { TranscriptDetector(it, name = "captions") } else null
        // The judges need words from somewhere: speech or captions.
        val judges = makeJudges(scriptStore, cloudWanted, localWanted)
        val eng = try {
            Assembly.engine(NetworkedController(ctl), store, logo = logo, transcript = transcript, captions = captions, judges = judges,
                silence = settings.silence, loudness = settings.loudness, fingerprints = settings.fingerprints, clock = breakClock, jingle = jingle)
        } catch (e: IllegalArgumentException) {
            update("no method is switched on — turn one on under Methods"); runCatching { ctl.close() }; controller = null; stopSelf(); return
        }
        eng.addListener { s -> lastStatus = s; main.post { update(describe(s)) } }
        engine = eng
        running = RunningInfo(
            silence = settings.silence, loudness = settings.loudness, fingerprints = settings.fingerprints,
            logo = logo != null, logoNotSetUp = logoWanted && logo == null, speech = speechWanted, speechNoModel = settings.speech && !speechWanted,
            captions = captionsWanted, control = settings.control,
            cloud = cloudWanted, cloudNoKey = settings.judgeCloud && !cloudWanted,
            local = localJudge != null, localNoModel = settings.judgeLocal && !LocalJudge.isInstalled(this),
            judgesDeaf = judges.isNotEmpty() && !speechWanted && !captionsWanted,
            clock = breakClock != null, jingle = jingle != null,
        )
        io.execute {
            try {
                if (ctl.recoverOnStart()) main.post { update("restored volume after an unclean exit") }
                ctl.trackNormal()
            } catch (e: ControlError) {
                AppLog.w("tv", "unreachable at start: ${e.message}")
                main.post { update("TV unreachable: ${e.message}") }
            }
        }
        // The speech model takes seconds to load: never on the main thread, where it would stall the service start.
        if (speechWanted) startSpeech(eng, scriptStore)
        val m = MicSource(this) { block ->
            eng.onAudio(block)
            speech?.feed(block)
            survey?.let { if (it.feed(block, controller?.ducked == true)) finishSurvey(it) }
        }
        try { m.start() } catch (e: Exception) { AppLog.e("mic", "failed to start", e); update("mic failed: ${e.message}"); stopSelf(); return }
        mic = m
        if (cameraWanted) {
            val reader = if (captionsWanted) runCatching { CaptionSource { words -> eng.onCaptions(words) } }.onFailure { AppLog.e("captions", "text recogniser failed to start", it) }.getOrNull() else null
            captionReader = reader
            val cam = CameraSource(this, this) { gray, _ ->   // 2 fps, upright, luma only
                val ts = mic?.mediaTime ?: 0.0
                eng.onFrame(gray, ts)
                reader?.feed(gray, ts)
                finder?.let { f ->
                    f.feed(gray)
                    val elapsed = (System.currentTimeMillis() - finderStartedAt) / 1000
                    if (elapsed >= SETUP_S) finishSetup(f) else if (f.frames % 10 == 0) main.post { update("setting up the camera: keep a show on, $elapsed / ${SETUP_S} s · screen seen ${f.frames}/${f.frames + f.screenMisses}") }
                }
            }
            camera = cam
            cam.setZoom(settings.cameraZoom)
            cam.start { msg -> main.post { update(msg) } }
        }
        val noun = if (watchTicker) "ticker" else "bug"
        val eye = if (logoWanted) (if (logo != null) " + camera ($noun)" else " + camera ($noun not set up)") else ""
        val cc = if (captionsWanted) " + captions" else ""
        val ear = if (speechWanted) " + speech (${scriptStore?.count() ?: 0} scripts)" else if (settings.speech) " + speech (model not downloaded)" else ""
        val ai = (if (cloudWanted) " + Claude" else "") + (if (localJudge != null) " + local AI" else "") + (if (judges.isNotEmpty() && !speechWanted && !captionsWanted) " (AI has no words: turn on speech or captions)" else "")
        update("listening (${m.sourceName}) via ${settings.control}$eye$cc$ear$ai")
        lastRepeatLearnAt = System.currentTimeMillis()
        main.postDelayed(ticker, POLL_MS)
    }

    /**
     * Test mode while running: the same exchange the app's Test TV does, but
     * over the connection the service already holds. The Sharp allows one
     * control connection at a time, so a second one from the app would be
     * hung up before the login prompt. Runs on `io`, serialised with the
     * engine's own commands; every line goes to the app's Test card.
     */
    private fun runTest() {
        fun say(line: String) { AppLog.i("test", line); sendBroadcast(Intent(BROADCAST_TEST).setPackage(packageName).putExtra("line", line)) }
        val ctl = controller ?: run { say("not running"); return }
        val t = transport
        try {
            when {
                ctl is StepVolumeController -> {
                    say("testing infrared through the running service: volume down ×3, then up ×3")
                    ctl.mute(); Thread.sleep(1500); ctl.unmute()
                    say("sent. Did the volume bar move down and back up?")
                }
                t is io.adhush.core.AquosTransport -> {
                    say("testing through the running connection (${settings.control}) …")
                    fun setTrace(f: ((String) -> Unit)?) { when (t) { is SocketTransport -> t.trace = f; is SerialTransport -> t.trace = f } }
                    setTrace { line -> say("  $line") }
                    try {
                        val client = SharpIpClient(t)
                        fun confirmed(ok: Boolean) = if (ok) "OK" else "sent, the set said nothing — did it happen?"
                        val vol = client.queryVolume(); say("VOLM? → " + (vol?.toString() ?: "no answer: the app will use your Normal volume"))
                        val mute = client.queryMute(); say("MUTE? → " + (mute?.let { if (it) "muted" else "not muted" } ?: "no answer"))
                        val m1 = client.muteOn(); Thread.sleep(1500); val m2 = client.muteOff(); say("MUTE1 → ${confirmed(m1)}; MUTE2 → ${confirmed(m2)}")
                        val back = vol ?: settings.normalVolume
                        val d1 = client.setVolume(settings.duckLevel); Thread.sleep(1500); val d2 = client.setVolume(back)
                        say("VOLM ${settings.duckLevel} → ${confirmed(d1)}; VOLM $back → ${confirmed(d2)} (ducking is what the app does)")
                        say("✓ done — if the sound dipped twice, the TV path works")
                    } finally { setTrace(null) }
                }
                else -> say("nothing to test on this connection")
            }
        } catch (e: ControlError) { say("✗ FAILED: ${e.message}") }
        catch (e: Exception) { AppLog.e("test", "test failed", e); say("✗ FAILED: ${e.message}") }
    }

    /** Numbers only — the survey never stores audio. Written off the mic thread, then handed to the app. */
    private fun finishSurvey(done: RoomSurvey) {
        survey = null
        io.execute {
            val file = File(File(filesDir, SURVEY_DIR), "survey-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".tsv")
            val summary = try { done.writeTsv(file); done.summary() } catch (e: Exception) { "survey could not be saved: ${e.message}" }
            main.post {
                update("survey done — open the app for the digest")
                sendBroadcast(Intent(BROADCAST_SURVEY).setPackage(packageName).putExtra("summary", summary).putExtra("file", file.absolutePath))
            }
        }
    }

    /** The one button's result: save the template and restart with the logo detector in the loop. */
    private fun finishSetup(f: LogoFinder) {
        finder = null
        val t = if (settings.cameraTarget == "ticker") f.bandResult() else f.result()
        main.post {
            if (t == null) {
                update("no logo found — was a show on, and is the whole screen in view? (screen seen ${f.frames}/${f.frames + f.screenMisses})")
                sendBroadcast(Intent(BROADCAST_STATUS).setPackage(packageName).putExtra("text", lastText))
                return@post
            }
            runCatching { t.save(File(filesDir, if (settings.cameraTarget == "ticker") TICKER_FILE else LOGO_FILE)) }
            update("logo found ${t.roi.corner} (stability ${"%.2f".format(java.util.Locale.US, t.stability)}) — restarting with the camera watching")
            restart()
        }
    }

    /** Tear the engine, mic and camera down and start again with the current settings. */
    private fun restart() {
        mic?.stop(); mic = null            // the mic feeds the speech engine: stop the feeder first
        camera?.stop(); camera = null
        speech?.close(); speech = null
        captionReader?.close(); captionReader = null
        cloudJudge?.close(); cloudJudge = null
        localJudge?.close(); localJudge = null
        main.removeCallbacks(ticker)
        running = null
        engine?.close(); engine = null
        controller?.let { runCatching { it.close() } }; controller = null
        (transport as? AutoCloseable)?.close(); transport = null
        start()
    }

    private fun clock(seconds: Double): String = "%d:%02d".format(java.util.Locale.US, (seconds / 60).toInt(), (seconds % 60).toInt())

    /** Every few seconds: follow the remote, and treat a dead mic while ducked as a reason to restore. */
    private fun tick() {
        // Every ten minutes, look for commercials that repeated in what was heard.
        if (speech != null && System.currentTimeMillis() - lastRepeatLearnAt > REPEAT_LEARN_MS) {
            lastRepeatLearnAt = System.currentTimeMillis()
            io.execute { val n = engine?.learnScriptsFromTranscript() ?: 0; if (n > 0) main.post { update("learned $n new script(s) from repetition · ${scripts?.count()} total") } }
        }
        survey?.let { update("surveying the room: ${clock(it.elapsedS)} / ${clock(SURVEY_S)} · ${lastStatus?.let(::describe) ?: "listening"}") }
        stream?.let { st ->
            when {
                System.currentTimeMillis() - st.lastBlockAt > MIC_DEAD_MS -> update("playback capture stopped delivering audio — Stop and start stream learning again")
                st.mediaTime > 40.0 && st.silentS > 30.0 -> update("hearing nothing from the player (${clock(st.mediaTime)} listened) — it may block capture; use the channel's web player in Chrome")
                else -> lastStatus?.let { update(describeStream(it)) }
            }
            return
        }
        val ctl = controller ?: return
        val m = mic ?: return
        io.execute {
            try {
                if (ctl.ducked) {
                    if (ctl.pollUserOverride()) { engine?.standDown(now()); main.post { update("you used the remote — standing down") } }
                    else if (System.currentTimeMillis() - m.lastBlockAt > MIC_DEAD_MS) { ctl.restore(); main.post { update("mic stopped; volume restored") } }
                } else ctl.trackNormal()
            } catch (e: ControlError) {
                main.post { update("TV: ${e.message}") }
            }
        }
    }

    /** The engine runs on media time (seconds of audio delivered), not the wall clock. */
    private fun now(): Double = mic?.mediaTime ?: stream?.mediaTime ?: 0.0

    private fun makeJudges(scriptStore: FileScriptStore?, cloudWanted: Boolean, localWanted: Boolean): List<JudgeDetector> {
        val judgeCfg = JudgeConfig(tieBreaker = settings.judgeMode != "always")
        val judges = ArrayList<JudgeDetector>()
        if (cloudWanted) {
            val cj = ClaudeJudge(settings.claudeKey, settings.claudeModel); cloudJudge = cj
            judges.add(JudgeDetector("judge_claude", judgeCfg, cj, { r -> think.execute(r) }, scriptStore, settings.channel))
        }
        if (localWanted) {
            LocalJudge.APP_CONTEXT = applicationContext
            try {
                val lj = LocalJudge(LocalJudge.modelFile(this)); localJudge = lj
                judges.add(JudgeDetector("judge_local", judgeCfg, lj, { r -> think.execute(r) }, scriptStore, settings.channel))
            } catch (t: Throwable) { AppLog.e("judge", "local model failed to load", t); update("local AI failed to load: ${t.message} (see the error log)") }
        }
        return judges
    }

    /** The speech model takes seconds to load: never on the main thread, where it would stall the service start. */
    private fun startSpeech(eng: Engine, scriptStore: FileScriptStore?) {
        io.execute {
            try {
                val sp = SpeechSource(SpeechSource.modelDir(this)) { words -> eng.onWords(words) }
                speech = sp
                AppLog.i("speech", "recogniser ready")
                main.post { update("speech recogniser ready · ${scriptStore?.count() ?: 0} scripts") }
            } catch (t: Throwable) {
                AppLog.e("speech", "recogniser failed to start", t)
                main.post { update("speech engine failed: ${t.message} (see the error log)") }
            }
        }
    }

    /** No TV in stream mode: the engine's ducks are bookkeeping, so a break is learned exactly as at the set. */
    private class VirtualController : MuteController {
        private var muted = false
        override fun mute() { muted = true }
        override fun unmute() { muted = false }
        override fun state() = muted
        override fun close() {}
    }

    /**
     * Stream learning (ADR 0018): hear the phone's own playback through
     * Android's playback capture and learn every break into the same memory
     * the TV mode uses. The camera and the break clock stay off (the stream
     * is delayed); fingerprints are always on, speech and the judges as set.
     */
    private fun startStream(intent: Intent) {
        if (Build.VERSION.SDK_INT < 29) { update("stream learning needs Android 10 or newer"); stopSelf(); return }
        if (engine != null) { update("stop AdHush first, then start stream learning"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { update("microphone permission missing"); stopSelf(); return }
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (code != Activity.RESULT_OK || data == null) { update("screen capture was not allowed — stream learning needs it to hear the player"); stopSelf(); return }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try { mpm.getMediaProjection(code, data) } catch (e: Exception) { AppLog.e("stream", "projection", e); null }
        if (mp == null) { update("could not start playback capture"); stopSelf(); return }
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { if (stream != null) { update("stream capture ended by Android — stream learning stopped"); stopSelf() } } }
        }, main)
        projection = mp
        val store = FileFingerprintStore(File(filesDir, "ads.tsv")); streamStore = store
        val speechWanted = settings.speech && SpeechSource.isInstalled(this)
        val cloudWanted = settings.judgeCloud && settings.claudeKey.isNotBlank()
        val localWanted = settings.judgeLocal && LocalJudge.isInstalled(this)
        val scriptStore = FileScriptStore(File(filesDir, SCRIPTS_FILE)).also { scripts = it }
        val transcript = if (speechWanted) TranscriptDetector(scriptStore) else null
        val judges = makeJudges(scriptStore, cloudWanted, localWanted)
        val jingle = if (settings.jingles) JingleDetector(FileJingleStore(File(filesDir, JINGLES_FILE))) else null
        val badge = if (settings.badge) BadgeDetector() else null
        val eng = Assembly.engine(VirtualController(), store, transcript = transcript, judges = judges,
            silence = settings.silence, loudness = settings.loudness, fingerprints = true, jingle = jingle, badge = badge)
        eng.addListener { s -> lastStatus = s; main.post { update(describeStream(s)) } }
        engine = eng
        streamAdsAtStart = store.count(); streamScriptsAtStart = scriptStore.count()
        running = RunningInfo(
            silence = settings.silence, loudness = settings.loudness, fingerprints = true,
            logo = false, logoNotSetUp = false, speech = speechWanted, speechNoModel = settings.speech && !speechWanted,
            captions = false, control = "none", cloud = cloudWanted, local = localWanted,
            judgesDeaf = judges.isNotEmpty() && !speechWanted, stream = true, jingle = jingle != null,
        )
        if (speechWanted) startSpeech(eng, scriptStore)
        val s = StreamSource(mp) { block -> eng.onAudio(block); speech?.feed(block) }
        try { s.start() } catch (e: Exception) { AppLog.e("stream", "capture failed to start", e); update("playback capture failed: ${e.message}"); stopSelf(); return }
        stream = s
        if (badge != null) {
            try { badgeReader = BadgeReader(mp, this, { ts, text -> eng.onBadgeText(ts, text) }, { stream?.mediaTime ?: 0.0 }).also { it.start() } }
            catch (e: Exception) { AppLog.w("badge", "screen reader failed to start: ${e.message}") }
        }
        main.postDelayed(ticker, POLL_MS)
        AppLog.i("stream", "learning from the phone's playback")
        update("STREAM LEARNING · play the channel's live stream in Chrome and leave it playing")
    }

    private fun describeStream(s: Status): String {
        val t = stream?.mediaTime ?: 0.0
        val breaks = (streamStore?.count() ?: 0) - streamAdsAtStart
        val newScripts = (scripts?.count() ?: 0) - streamScriptsAtStart
        val state = if (s.teaching) "teaching — press Show's back when the show returns" else if (s.muted) "commercial (learning)" else "show"
        val ai = s.judges.entries.joinToString("") { (k, v) -> " · ${if (k == "judge_claude") "Claude" else "local AI"}: $v" }
        val bdg = s.badge?.let { " · badge: $it" } ?: ""
        val jng = s.jingles?.let { " · jingles: $it" } ?: ""
        return "STREAM LEARNING · ${clock(t)} listened · $state$bdg$ai$jng · +$breaks breaks · +$newScripts scripts"
    }

    override fun onDestroy() {
        AppLog.i("service", "destroyed")
        emergencyRestore = null
        mic?.stop(); mic = null            // before the speech engine, which it feeds
        stream?.stop(); stream = null
        badgeReader?.close(); badgeReader = null
        projection?.stop(); projection = null
        streamStore = null
        camera?.stop(); camera = null
        speech?.close(); speech = null
        captionReader?.close(); captionReader = null
        cloudJudge?.close(); cloudJudge = null
        localJudge?.close(); localJudge = null
        think.shutdown()
        main.removeCallbacks(ticker)
        running = null
        val ctl = controller
        engine?.close(); engine = null
        if (ctl != null) runCatching { ctl.close() }   // restore if ducked
        transport?.close(); transport = null
        io.shutdown()
        registry.currentState = Lifecycle.State.DESTROYED
        lastText = "stopped"
        sendBroadcast(Intent(BROADCAST_STATUS).setPackage(packageName).putExtra("text", "stopped").putExtra("running", false))
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) { /* keep running; the notification is the UI */ }

    // -- notification ---------------------------------------------------------

    /** A remote-control key through the live connection (ADR 0017); the Sharp allows one, so it must be this one while running. */
    private fun pressKey(name: String) {
        val key = RemoteKey.of(name) ?: return
        val c = controller
        try {
            if (c is SharpController) { if (!c.client.press(key)) main.post { update("the set did not accept ${key.label}") } }
            else main.post { update("remote keys need the network or the serial cable; infrared knows only volume and mute") }
        } catch (e: ControlError) { main.post { update("remote ${key.label} failed: ${e.message}") } }
    }

    private fun describe(s: Status): String {
        if (s.timedS > 0.0) return "DUCKED — ${clock(s.timedS)} left (manual; +30 s on the notification) · ${s.adsLearned} learned"
        if (s.teaching) return "TEACHING — ducked; press Show's back when the show returns · ${s.adsLearned} learned"
        val left = s.breakLeftS   // local: no smart cast on a property from another module
        if (s.muted && s.override == CoreOverride.AUTO && left != null) {
            val cam = s.camera?.let { " · camera: $it" } ?: ""
            return "DUCKED — ad · about ${clock(left)} left$cam · ${s.adsLearned} learned"
        }
        val state = if (s.override != CoreOverride.AUTO) "override: ${s.override.wire}" else if (s.muted) "DUCKED — ad" else if (s.quietS > 0.0) "SHOW (you said not an ad; ${s.quietS.toInt()} s of quiet)" else if (s.state == AdState.PROGRAM) "SHOW" else s.state.wire.uppercase()
        val cam = s.camera?.let { " · camera: $it" } ?: ""
        val ai = s.judges.entries.joinToString("") { (k, v) -> " · ${if (k == "judge_claude") "Claude" else "local AI"}: $v" }
        val clk = s.clock?.let { " · clock $it" } ?: ""
        val jng = s.jingles?.let { if (it.startsWith("learning")) "" else " · jingles: $it" } ?: ""
        return "$state · ${"%.2f".format(s.confidence)}$cam$ai$clk$jng · ${s.adsLearned} learned"
    }

    private fun refresh() { lastStatus?.let { update(describe(it)) } }

    private fun update(text: String) {
        lastText = text
        if (text != lastLogged) { AppLog.i("status", text); lastLogged = text }   // the same line every five seconds is noise in the log
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text))
        sendBroadcast(Intent(BROADCAST_STATUS).setPackage(packageName).putExtra("text", text).putExtra("running", engine != null).putExtra("ducked", lastStatus?.muted == true).putExtra("teaching", lastStatus?.teaching == true))
    }
    private var lastLogged = ""

    private fun action(code: Int, act: String, label: String): NotificationCompat.Action {
        val pi = PendingIntent.getService(this, code, Intent(this, AdHushService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action(0, label, pi)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            // Android shows at most three: the teaching pair and the correction. Stop lives in the app.
            .addAction(action(2, ACTION_IS_AD, getString(R.string.action_is_ad)))
            .addAction(action(4, ACTION_SHOW_BACK, getString(R.string.action_show_back)))
            .addAction(action(1, ACTION_NOT_AD, getString(R.string.action_not_ad)))
            .addAction(action(5, ACTION_DUCK_MORE, "+30 s"))
            .build()
    }

    private fun startForegroundWithType(n: Notification, mediaProjection: Boolean = false) {
        val cameraOk = (settings.camera || settings.captions) && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        // Stream learning declares the media-projection type: Android 14 refuses playback capture without it.
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or (if (cameraOk && Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0) or
            (if (mediaProjection && Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, type)
        else startForeground(NOTIF_ID, n)
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
    }

    /** Runs the controller's network calls off the mic thread; failures surface in the notification, never in the engine. */
    private inner class NetworkedController(private val inner: DuckController) : MuteController {
        override fun mute() = io.execute { try { inner.mute() } catch (e: ControlError) { main.post { update("mute failed: ${e.message}") } } }
        override fun unmute() = io.execute { try { inner.unmute() } catch (e: ControlError) { main.post { update("restore failed: ${e.message}") } } }
        override fun state(): Boolean? = inner.state()
        override fun close() { /* the service closes the real controller on its own thread */ }
    }

    /** Which methods are actually running, for the app's indicators. */
    data class RunningInfo(
        val silence: Boolean, val loudness: Boolean, val fingerprints: Boolean,
        val logo: Boolean, val logoNotSetUp: Boolean, val speech: Boolean, val speechNoModel: Boolean,
        val captions: Boolean, val control: String,
        val cloud: Boolean = false, val cloudNoKey: Boolean = false,
        val local: Boolean = false, val localNoModel: Boolean = false,
        /** An AI judge is on but neither speech nor captions feed it words. */
        val judgesDeaf: Boolean = false,
        val clock: Boolean = false,
        /** Stream learning: hearing the phone's own playback, no TV (ADR 0018). */
        val stream: Boolean = false,
        val jingle: Boolean = false,
    )

    companion object {
        /** Set while a controller exists: the crash handler restores the volume through it before Android reports. */
        @Volatile var emergencyRestore: (() -> Unit)? = null
        /** Non-null while the engine runs (same process as the app): what is on, for the indicators. */
        @Volatile var running: RunningInfo? = null
        /** The last status line, for an app screen that opens while the service runs. */
        @Volatile var lastText = "stopped"
        const val CHANNEL = "adhush"
        const val NOTIF_ID = 1
        const val ACTION_NOT_AD = "io.adhush.android.NOT_AD"
        const val ACTION_IS_AD = "io.adhush.android.IS_AD"
        const val ACTION_RESTORE = "io.adhush.android.RESTORE"
        const val ACTION_STOP = "io.adhush.android.STOP"
        const val ACTION_SHOW_BACK = "io.adhush.android.SHOW_BACK"
        const val ACTION_CAMERA_SETUP = "io.adhush.android.CAMERA_SETUP"
        const val ACTION_LEARN_SCRIPTS = "io.adhush.android.LEARN_SCRIPTS"
        const val ACTION_TEST = "io.adhush.android.TEST"
        const val ACTION_DUCK_FOR = "io.adhush.android.DUCK_FOR"
        const val EXTRA_SECONDS = "seconds"
        const val ACTION_KEY = "io.adhush.android.KEY"
        const val EXTRA_KEY = "key"
        const val CLOCK_FILE = "clock.tsv"
        const val ACTION_STREAM_START = "io.adhush.android.STREAM_START"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_DUCK_MORE = "io.adhush.android.DUCK_MORE"
        const val JINGLES_FILE = "jingles.tsv"
        const val BROADCAST_TEST = "io.adhush.android.TEST_LINE"
        const val SCRIPTS_FILE = "scripts.tsv"
        const val REPEAT_LEARN_MS = 10 * 60 * 1000L
        const val LOGO_FILE = "logo.tsv"
        const val TICKER_FILE = "ticker.tsv"
        const val SETUP_S = 45L
        const val ACTION_SURVEY = "io.adhush.android.SURVEY"
        const val BROADCAST_STATUS = "io.adhush.android.STATUS"
        const val BROADCAST_SURVEY = "io.adhush.android.SURVEY_DONE"
        const val SURVEY_DIR = "survey"
        const val SURVEY_S = 600.0
        const val POLL_MS = 5_000L
        const val MIC_DEAD_MS = 8_000L
    }
}
