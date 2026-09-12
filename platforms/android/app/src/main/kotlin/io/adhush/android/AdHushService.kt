package io.adhush.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.adhush.core.Assembly
import io.adhush.core.ControlError
import io.adhush.core.DuckController
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
class AdHushService : Service() {
    private lateinit var settings: Settings
    private var engine: Engine? = null
    private var controller: DuckController? = null
    private var transport: AutoCloseable? = null
    private var mic: MicSource? = null
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
        settings = Settings(this)
        createChannel()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> runCatching { controller?.restore() }; android.os.Process.killProcess(android.os.Process.myPid()) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every start — including control actions delivered by startForegroundService —
        // must be answered with startForeground, or Android 8+ kills the app.
        startForegroundWithType(buildNotification(lastText))
        val action = intent?.action
        if (engine == null && action != null && action != ACTION_SURVEY) {  // a control action, but nothing is running
            if (action != ACTION_STOP) update("not running — press Start in the app")
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_NOT_AD -> { io.execute { engine?.rejectAd(now()) }; return START_STICKY }
            ACTION_IS_AD -> { io.execute { engine?.confirmAd(now()) }; return START_STICKY }
            ACTION_SHOW_BACK -> { io.execute { engine?.showIsBack(now()); refresh() }; return START_STICKY }
            ACTION_RESTORE -> { io.execute { runCatching { controller?.restore() }; refresh() }; return START_STICKY }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_SURVEY -> {
                if (engine == null) start()
                if (engine != null && survey == null) { survey = RoomSurvey(SURVEY_S); update("surveying the room: 0:00 / ${clock(SURVEY_S)}") }
                return START_STICKY
            }
        }
        if (engine == null) start()
        return START_STICKY
    }

    @Volatile private var lastText = "starting…"

    private fun start() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            update("microphone permission missing"); stopSelf(); return
        }
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
        val eng = Assembly.engine(NetworkedController(ctl), store)
        eng.addListener { s -> lastStatus = s; main.post { update(describe(s)) } }
        engine = eng
        io.execute {
            try {
                if (ctl.recoverOnStart()) main.post { update("restored volume after an unclean exit") }
                ctl.trackNormal()
            } catch (e: ControlError) {
                main.post { update("TV unreachable: ${e.message}") }
            }
        }
        val m = MicSource(this) { block ->
            eng.onAudio(block)
            survey?.let { if (it.feed(block, controller?.ducked == true)) finishSurvey(it) }
        }
        try { m.start() } catch (e: Exception) { update("mic failed: ${e.message}"); stopSelf(); return }
        mic = m
        update("listening (${m.sourceName}) via ${settings.control}")
        main.postDelayed(ticker, POLL_MS)
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

    private fun clock(seconds: Double): String = "%d:%02d".format(java.util.Locale.US, (seconds / 60).toInt(), (seconds % 60).toInt())

    /** Every few seconds: follow the remote, and treat a dead mic while ducked as a reason to restore. */
    private fun tick() {
        survey?.let { update("surveying the room: ${clock(it.elapsedS)} / ${clock(SURVEY_S)} · ${lastStatus?.let(::describe) ?: "listening"}") }
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
    private fun now(): Double = mic?.mediaTime ?: 0.0

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        mic?.stop(); mic = null
        val ctl = controller
        engine?.close(); engine = null
        if (ctl != null) runCatching { ctl.close() }   // restore if ducked
        transport?.close(); transport = null
        io.shutdown()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) { /* keep running; the notification is the UI */ }

    // -- notification ---------------------------------------------------------

    private fun describe(s: Status): String {
        if (s.teaching) return "TEACHING — ducked; press Show's back when the show returns · ${s.adsLearned} learned"
        val state = if (s.override != CoreOverride.AUTO) "override: ${s.override.wire}" else if (s.muted) "DUCKED — ad" else s.state.wire.uppercase()
        return "$state · ${"%.2f".format(s.confidence)} · ${s.adsLearned} learned"
    }

    private fun refresh() { lastStatus?.let { update(describe(it)) } }

    private fun update(text: String) {
        lastText = text
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text))
        sendBroadcast(Intent(BROADCAST_STATUS).setPackage(packageName).putExtra("text", text))
    }

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
            .build()
    }

    private fun startForegroundWithType(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
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

    companion object {
        const val CHANNEL = "adhush"
        const val NOTIF_ID = 1
        const val ACTION_NOT_AD = "io.adhush.android.NOT_AD"
        const val ACTION_IS_AD = "io.adhush.android.IS_AD"
        const val ACTION_RESTORE = "io.adhush.android.RESTORE"
        const val ACTION_STOP = "io.adhush.android.STOP"
        const val ACTION_SHOW_BACK = "io.adhush.android.SHOW_BACK"
        const val ACTION_SURVEY = "io.adhush.android.SURVEY"
        const val BROADCAST_STATUS = "io.adhush.android.STATUS"
        const val BROADCAST_SURVEY = "io.adhush.android.SURVEY_DONE"
        const val SURVEY_DIR = "survey"
        const val SURVEY_S = 600.0
        const val POLL_MS = 5_000L
        const val MIC_DEAD_MS = 8_000L
    }
}
