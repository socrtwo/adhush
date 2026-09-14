package io.adhush.android

import android.content.Context
import io.adhush.core.AudioBlock
import io.adhush.core.Word
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Offline speech recognition on the phone (Vosk, small English model). The
 * microphone's 48 kHz float blocks are averaged down to 16 kHz 16-bit, and
 * every finished utterance comes back as words with media-time stamps for
 * the transcript detector. Nothing leaves the phone.
 */
class SpeechSource(modelDir: File, private val onWords: (List<Word>) -> Unit) {
    private val model: Model
    private val recognizer: Recognizer
    private val executor = Executors.newSingleThreadExecutor()
    private var t0: Double? = null
    @Volatile var utterances = 0L
        private set
    @Volatile var lastText = ""
        private set

    init {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        model = Model(modelDir.absolutePath)
        recognizer = Recognizer(model, 16000f)
        recognizer.setWords(true)
    }

    /** Called from the mic thread; after [close] the executor is gone and the block is simply dropped. */
    fun feed(block: AudioBlock) {
        if (executor.isShutdown) return
        try { executor.execute { runCatching { process(block) } } } catch (_: java.util.concurrent.RejectedExecutionException) { /* closing */ }
    }

    private fun process(block: AudioBlock) {
        if (t0 == null) t0 = block.ts
        val f = (block.sampleRate / 16_000).coerceAtLeast(1)
        val n = block.samples.size / f
        val pcm = ShortArray(n) { i ->
            var s = 0f; for (k in 0 until f) s += block.samples[i * f + k]
            (s / f * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        if (recognizer.acceptWaveForm(pcm, n)) parse(recognizer.result)
    }

    /** {"result":[{"word":"ask","start":1.2,"end":1.5,"conf":0.9},…],"text":"…"} */
    private fun parse(json: String) {
        val o = JSONObject(json)
        lastText = o.optString("text")
        val arr = o.optJSONArray("result") ?: return
        val base = t0 ?: 0.0
        val words = ArrayList<Word>(arr.length())
        for (i in 0 until arr.length()) { val w = arr.getJSONObject(i); words.add(Word(base + w.getDouble("start"), w.getString("word"))) }
        if (words.isNotEmpty()) { utterances++; onWords(words) }
    }

    /** Stop feeding first and wait for the last block to finish: closing a native recogniser mid-call is a segfault. */
    fun close() {
        executor.shutdown()
        runCatching { executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS) }
        runCatching { recognizer.close() }; runCatching { model.close() }
    }

    companion object {
        const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
        fun modelDir(context: Context) = File(File(context.filesDir, "speech"), MODEL_NAME)
        fun isInstalled(context: Context) = File(modelDir(context), "am").isDirectory

        /** Download the ~40 MB model zip and unpack it; progress is 0..100. Blocking — call off the main thread. */
        fun install(context: Context, onProgress: (Int) -> Unit) {
            val root = File(context.filesDir, "speech"); root.mkdirs()
            val zip = File(root, "$MODEL_NAME.zip")
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            val total = conn.contentLength.toLong()
            conn.inputStream.use { inp -> FileOutputStream(zip).use { out ->
                val buf = ByteArray(64 * 1024); var got = 0L; var n: Int
                while (inp.read(buf).also { n = it } > 0) { out.write(buf, 0, n); got += n; if (total > 0) onProgress((got * 90 / total).toInt()) }
            } }
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val f = File(root, e.name)
                    if (!f.canonicalPath.startsWith(root.canonicalPath)) throw SecurityException("zip entry outside the model folder")
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); FileOutputStream(f).use { z.copyTo(it) } }
                    e = z.nextEntry
                }
            }
            zip.delete()
            onProgress(100)
        }
    }
}
