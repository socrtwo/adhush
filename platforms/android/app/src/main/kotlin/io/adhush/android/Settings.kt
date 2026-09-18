package io.adhush.android

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.adhush.core.Aquos
import io.adhush.core.DuckPersistence

/** TV credentials live in EncryptedSharedPreferences; never in logs or crash reports. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "adhush.secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        AppLog.w("settings", "encrypted settings unavailable, using plain settings: ${e.javaClass.simpleName}: ${e.message}")
        context.getSharedPreferences("adhush.fallback", Context.MODE_PRIVATE)
    }

    var host: String get() = prefs.getString("host", "") ?: ""; set(v) = prefs.edit().putString("host", v).apply()
    var port: Int get() = prefs.getInt("port", Aquos.DEFAULT_PORT); set(v) = prefs.edit().putInt("port", v).apply()
    var loginId: String get() = prefs.getString("login", "") ?: ""; set(v) = prefs.edit().putString("login", v).apply()
    var password: String get() = prefs.getString("password", "") ?: ""; set(v) = prefs.edit().putString("password", v).apply()
    var duckLevel: Int get() = prefs.getInt("duck", 4); set(v) = prefs.edit().putInt("duck", v).apply()
    var normalVolume: Int get() = prefs.getInt("normal", 20); set(v) = prefs.edit().putInt("normal", v).apply()
    var useMute: Boolean get() = prefs.getBoolean("use_mute", false); set(v) = prefs.edit().putBoolean("use_mute", v).apply()
    /**
     * How this phone reaches its TV: "ip" (a Sharp's network port), "serial" (RS-232C over USB-OTG), "ir" (the phone's
     * own blaster), or any other brand's path the wizard found (ADR 0025): "sony", "lg", "upnp", "samsung", "roku", "vizio".
     */
    var control: String get() = prefs.getString("control", "ip") ?: "ip"; set(v) = prefs.edit().putString("control", v).apply()
    /** What the wizard found: the maker and model, for the TV page and the log. */
    var tvBrand: String get() = prefs.getString("tv_brand", "") ?: ""; set(v) = prefs.edit().putString("tv_brand", v).apply()
    var tvModel: String get() = prefs.getString("tv_model", "") ?: ""; set(v) = prefs.edit().putString("tv_model", v).apply()
    /** The UPnP RenderingControl control URL of a DLNA renderer. */
    var upnpControlUrl: String get() = prefs.getString("upnp_url", "") ?: ""; set(v) = prefs.edit().putString("upnp_url", v).apply()
    /** Sony: the pre-shared key typed into the set. Samsung, LG, Vizio: the token the set handed back when the owner allowed AdHush once. */
    var sonyPsk: String get() = prefs.getString("sony_psk", "") ?: ""; set(v) = prefs.edit().putString("sony_psk", v).apply()
    var samsungToken: String get() = prefs.getString("samsung_token", "") ?: ""; set(v) = prefs.edit().putString("samsung_token", v).apply()
    var lgClientKey: String get() = prefs.getString("lg_key", "") ?: ""; set(v) = prefs.edit().putString("lg_key", v).apply()
    var vizioToken: String get() = prefs.getString("vizio_token", "") ?: ""; set(v) = prefs.edit().putString("vizio_token", v).apply()
    var vizioDeviceId: String get() = prefs.getString("vizio_device", "") ?: ""; set(v) = prefs.edit().putString("vizio_device", v).apply()
    /** Which brand's infrared codes the blaster sends (IrCodeSets); "sharp" uses the address and codes typed on the TV page. */
    var irCodeSet: String get() = prefs.getString("ir_codes", "sharp") ?: "sharp"; set(v) = prefs.edit().putString("ir_codes", v).apply()
    /** Watch the screen with the back camera for the network bug (ADR 0011). */
    var camera: Boolean get() = prefs.getBoolean("camera", false); set(v) = prefs.edit().putBoolean("camera", v).apply()
    /** Recognise speech and learn commercials from their words (ADR 0012). */
    var speech: Boolean get() = prefs.getBoolean("speech", false); set(v) = prefs.edit().putBoolean("speech", v).apply()
    /** Read the captions off the screen with the camera and match them like speech (ADR 0013). */
    var captions: Boolean get() = prefs.getBoolean("captions", false); set(v) = prefs.edit().putBoolean("captions", v).apply()
    /** The three audio methods, on by default. */
    var silence: Boolean get() = prefs.getBoolean("silence", true); set(v) = prefs.edit().putBoolean("silence", v).apply()
    var loudness: Boolean get() = prefs.getBoolean("loudness", true); set(v) = prefs.edit().putBoolean("loudness", v).apply()
    var fingerprints: Boolean get() = prefs.getBoolean("fingerprints", true); set(v) = prefs.edit().putBoolean("fingerprints", v).apply()
    /** Camera zoom ratio (1.0 = none) chosen on the setup screen; the service uses the same so the template fits. */
    var cameraZoom: Float get() = prefs.getFloat("camera_zoom", 1f); set(v) = prefs.edit().putFloat("camera_zoom", v).apply()

    /** Method 7: ask Claude over the API (opt-in; text only ever leaves the phone). */
    var judgeCloud: Boolean get() = prefs.getBoolean("judge_cloud", false); set(v) = prefs.edit().putBoolean("judge_cloud", v).apply()
    var claudeKey: String get() = prefs.getString("claude_key", "") ?: ""; set(v) = prefs.edit().putString("claude_key", v).apply()
    var claudeModel: String get() = prefs.getString("claude_model", "claude-haiku-4-5") ?: "claude-haiku-4-5"; set(v) = prefs.edit().putString("claude_model", v).apply()
    /** "tie": ask only when the other methods are unsure or the set is ducked; "always": every ten seconds. */
    var judgeMode: String get() = prefs.getString("judge_mode", "tie") ?: "tie"; set(v) = prefs.edit().putString("judge_mode", v).apply()
    /** Method 8: a small language model on the phone. */
    var judgeLocal: Boolean get() = prefs.getBoolean("judge_local", false); set(v) = prefs.edit().putBoolean("judge_local", v).apply()
    var localModelUrl: String get() = prefs.getString("local_model_url", "") ?: ""; set(v) = prefs.edit().putString("local_model_url", v).apply()
    /** Which size of local model: "small" (0.5B), "medium" (1.5B) or "large" (4B); see LocalJudge.TIERS. */
    var localModelSize: String get() = prefs.getString("local_model_size", "small") ?: "small"; set(v) = prefs.edit().putString("local_model_size", v).apply()
    /** The channel's name, for the judges' question ("MSNOW"). */
    var channel: String get() = prefs.getString("channel", "") ?: ""; set(v) = prefs.edit().putString("channel", v).apply()
    /** What the camera watches: "bug" (the corner logo) or "ticker" (a news channel's lower-third band). */
    var cameraTarget: String get() = prefs.getString("camera_target", "bug") ?: "bug"; set(v) = prefs.edit().putString("camera_target", v).apply()
    /** Which offline speech model: "small" (40 MB) or "medium" (128 MB, hears better). */
    var speechModel: String get() = prefs.getString("speech_model", "small") ?: "small"; set(v) = prefs.edit().putString("speech_model", v).apply()

    /** Method 9: the break clock, a learned minute-of-hour prior (ADR 0017). On by default; inert until it has learned. */
    var clock: Boolean get() = prefs.getBoolean("clock", true); set(v) = prefs.edit().putBoolean("clock", v).apply()

    /** Method 10: the channel's break jingles (ADR 0020). On by default; inert until three breaks share a sting. */
    var jingles: Boolean get() = prefs.getBoolean("jingles", true); set(v) = prefs.edit().putBoolean("jingles", v).apply()
    /** Stream learning reads the player's "AD" badge off the screen (ADR 0020). */
    var badge: Boolean get() = prefs.getBoolean("badge", true); set(v) = prefs.edit().putBoolean("badge", v).apply()

    /** The set-up wizard (ADR 0022) has been offered once on first run, or run to the end. */
    var wizardOffered: Boolean get() = prefs.getBoolean("wizard_offered", false); set(v) = prefs.edit().putBoolean("wizard_offered", v).apply()

    /** How many of the ten methods are switched on. Zero means the app cannot work. */
    val methodsOn: Int get() = listOf(silence, loudness, fingerprints, camera, speech, captions, judgeCloud, judgeLocal, clock, jingles).count { it }
    var irAddress: Int get() = prefs.getInt("ir_address", 1); set(v) = prefs.edit().putInt("ir_address", v).apply()
    var irVolumeUp: Int get() = prefs.getInt("ir_vol_up", 0x14); set(v) = prefs.edit().putInt("ir_vol_up", v).apply()
    var irVolumeDown: Int get() = prefs.getInt("ir_vol_down", 0x15); set(v) = prefs.edit().putInt("ir_vol_down", v).apply()

    val login: Pair<String, String>? get() = if (loginId.isBlank() && password.isBlank()) null else Pair(loginId, password)
}

/** The pre-duck volume must survive the process: plain prefs, written before ducking. */
class PrefsDuckPersistence(context: Context) : DuckPersistence {
    private val prefs = context.getSharedPreferences("adhush.duck", Context.MODE_PRIVATE)
    override fun save(volume: Int) { prefs.edit().putInt("pre_duck", volume).commit() }
    override fun load(): Int? = if (prefs.contains("pre_duck")) prefs.getInt("pre_duck", -1).takeIf { it >= 0 } else null
    override fun clear() { prefs.edit().remove("pre_duck").commit() }
}
