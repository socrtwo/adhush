package io.adhush.android

import android.app.Application

class AdHushApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            AppLog.e("crash", "uncaught on ${thread.name}: ${t.javaClass.simpleName}: ${t.message}", t)
            runCatching { AdHushService.emergencyRestore?.invoke() }   // never leave the set ducked
            previous?.uncaughtException(thread, t)                     // Android shows the dialog and restarts what it should
        }
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        AppLog.i("app", "AdHush $version starting")
    }
}
