package io.adhush.android

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The error log: a rolling text file in app-private storage that every part
 * of the app writes to, and that the user can share. Uncaught exceptions
 * land here with their stack trace before Android is told about them, so a
 * crash is a line in a file rather than an app that "disappeared".
 */
object AppLog {
    private const val MAX_BYTES = 512 * 1024L
    private var file: File? = null
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized fun init(context: Context) {
        val dir = File(context.filesDir, "logs"); dir.mkdirs()
        file = File(dir, "adhush.log")
    }

    fun file(context: Context): File { if (file == null) init(context); return file!! }

    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    @Synchronized private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        when (level) { "E" -> Log.e(tag, msg, t); "W" -> Log.w(tag, msg, t); else -> Log.i(tag, msg) }
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) { val old = File(f.path + ".1"); old.delete(); f.renameTo(old) }
            f.appendText(buildString {
                append(stamp.format(Date())).append(' ').append(level).append('/').append(tag).append(": ").append(msg).append('\n')
                if (t != null) { val sw = StringWriter(); t.printStackTrace(PrintWriter(sw)); append(sw.toString()) }
            })
        } catch (_: Exception) {}
    }

    /** The last [lines] lines, newest last. */
    @Synchronized fun tail(lines: Int = 200): String {
        val f = file ?: return ""
        if (!f.isFile) return ""
        return try { f.readLines().takeLast(lines).joinToString("\n") } catch (_: Exception) { "" }
    }

    @Synchronized fun clear() { file?.let { it.delete(); File(it.path + ".1").delete() } }
}
