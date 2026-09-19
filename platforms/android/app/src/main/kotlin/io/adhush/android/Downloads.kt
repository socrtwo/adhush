package io.adhush.android

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Model downloads that cannot leave a half file behind as if it were whole
 * (0.28.4). The bytes go to a `.part` file next to the target; the expected
 * size from Content-Length is kept in a `.total` file beside it; a download
 * that stops short is an error that says how far it got, and the next press
 * resumes from there. Only a `.part` of exactly the expected size is ever
 * moved into place.
 */
object Downloads {
    /** How far a stopped download got: (bytes so far, bytes expected or null), or null when nothing is pending. */
    fun partial(part: File): Pair<Long, Long?>? {
        if (!part.isFile) return null
        val total = File(part.path + ".total").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
        return Pair(part.length(), total)
    }

    fun describePartial(p: Pair<Long, Long?>): String {
        val (have, total) = p
        return if (total != null && total > 0) "${have * 100 / total} % of ${total / 1_000_000} MB" else "${have / 1_000_000} MB"
    }

    /** Remove every stopped download under [dir]; returns how many files went. */
    fun deletePartials(dir: File): Int = dir.listFiles { f -> f.name.endsWith(".part") || f.name.endsWith(".total") }?.count { it.delete() } ?: 0

    /**
     * Fetch [url] into [part], resuming what is there; progress is 0..99.
     * Throws with a plain message when the server refuses or the bytes stop
     * short, leaving the `.part` for the next attempt.
     */
    fun fetch(url: String, part: File, onProgress: (Int) -> Unit, connectTimeoutMs: Int = 20_000, readTimeoutMs: Int = 60_000) {
        part.parentFile?.mkdirs()
        val totalFile = File(part.path + ".total")
        val have = if (part.isFile) part.length() else 0L
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = connectTimeoutMs; conn.readTimeout = readTimeoutMs
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        val code = conn.responseCode
        if (code == 416) { part.delete(); totalFile.delete(); throw IOException("the saved part did not match the file — press again to start over") }
        if (code !in 200..299) throw IOException("download failed: HTTP $code")
        val resuming = code == 206
        val total = (if (resuming) have else 0L) + conn.contentLengthLong.coerceAtLeast(0L)
        if (total > 0) totalFile.writeText(total.toString()) else totalFile.delete()
        conn.inputStream.use { inp -> FileOutputStream(part, resuming).use { out ->
            val buf = ByteArray(256 * 1024); var got = if (resuming) have else 0L; var n: Int
            while (inp.read(buf).also { n = it } > 0) { out.write(buf, 0, n); got += n; if (total > 0) onProgress((got * 100 / total).toInt().coerceIn(0, 99)) }
            out.fd.sync()
        } }
        val size = part.length()
        if (total > 0 && size != total) throw IOException("the download stopped at ${size * 100 / total} % (${size / 1_000_000} of ${total / 1_000_000} MB) — press again to resume")
        totalFile.delete()
    }

    /** Move a complete `.part` into place; never touches a finished [target] unless the part is there to replace it. */
    fun install(part: File, target: File) {
        check(part.isFile) { "nothing downloaded" }
        if (!part.renameTo(target)) { target.delete(); if (!part.renameTo(target)) throw IOException("could not move the file into place") }
    }
}
