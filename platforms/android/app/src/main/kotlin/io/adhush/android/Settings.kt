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
    /** How this phone reaches its TV: "ip" (network), "serial" (RS-232C over USB-OTG), "ir" (the phone's own blaster). */
    var control: String get() = prefs.getString("control", "ip") ?: "ip"; set(v) = prefs.edit().putString("control", v).apply()
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

    /** How many of the six methods are switched on. Zero means the app cannot work. */
    val methodsOn: Int get() = listOf(silence, loudness, fingerprints, camera, speech, captions).count { it }
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
