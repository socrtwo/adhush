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
        /** "small": 40 MB, quick, mishears over fans. "medium": 128 MB, the same recogniser with a bigger vocabulary and better accuracy. */
        val MODELS = mapOf("small" to "vosk-model-small-en-us-0.15", "medium" to "vosk-model-en-us-0.22-lgraph")
        val SIZES_MB = mapOf("small" to 40, "medium" to 128)
        const val MODEL_NAME = "vosk-model-small-en-us-0.15"
        fun modelName(context: Context) = MODELS[Settings(context).speechModel] ?: MODEL_NAME
        fun modelUrl(name: String) = "https://alphacephei.com/vosk/models/$name.zip"
        fun modelDir(context: Context) = File(File(context.filesDir, "speech"), modelName(context))
        fun isInstalled(context: Context) = File(modelDir(context), "am").isDirectory

        fun partFile(context: Context) = File(File(context.filesDir, "speech"), "${modelName(context)}.zip.part")
        /** A stopped download waiting to be resumed, or null. */
        fun partial(context: Context) = Downloads.partial(partFile(context))
        fun deletePartials(context: Context): Int {
            val root = File(context.filesDir, "speech")
            root.listFiles { f -> f.isDirectory && f.name.endsWith(".tmp") }?.forEach { it.deleteRecursively() }
            return Downloads.deletePartials(root)
        }
        @Volatile var installing = false
            private set

        /**
         * Download the chosen model zip and unpack it; progress is 0..100.
         * Blocking — call off the main thread. The zip resumes if it stopped
         * and is checked against its expected size; it is unpacked into a
         * folder of its own and moved into place whole, so a half model can
         * never count as installed (0.28.4).
         */
        fun install(context: Context, onProgress: (Int) -> Unit) {
            synchronized(this) { check(!installing) { "a download is already running — wait for it to finish" }; installing = true }
            try {
                val root = File(context.filesDir, "speech"); root.mkdirs()
                val name = modelName(context)
                if (isInstalled(context)) { onProgress(100); return }
                val part = partFile(context)
                Downloads.fetch(modelUrl(name), part, { p -> onProgress(p * 90 / 100) }, 15_000, 30_000)
                val tmp = File(root, "$name.tmp"); tmp.deleteRecursively(); tmp.mkdirs()
                ZipInputStream(part.inputStream().buffered()).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val f = File(tmp, e.name)
                        if (!f.canonicalPath.startsWith(tmp.canonicalPath)) throw SecurityException("zip entry outside the model folder")
                        if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); FileOutputStream(f).use { z.copyTo(it) } }
                        e = z.nextEntry
                    }
                }
                val unpacked = File(tmp, name).takeIf { File(it, "am").isDirectory } ?: tmp.takeIf { File(it, "am").isDirectory }
                    ?: throw java.io.IOException("the zip did not contain the model — press again to download it afresh").also { part.delete() }
                val dir = modelDir(context); dir.deleteRecursively()
                if (!unpacked.renameTo(dir)) throw java.io.IOException("could not move the model into place")
                tmp.deleteRecursively(); part.delete()
                onProgress(100)
            } finally { installing = false }
        }
    }
}
