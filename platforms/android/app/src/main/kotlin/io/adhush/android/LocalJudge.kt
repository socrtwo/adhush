package io.adhush.android

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import io.adhush.core.JudgeDetector
import io.adhush.core.TranscriptJudge
import io.adhush.core.Verdict
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The eighth method's brain: a small language model running on the phone
 * itself through MediaPipe's LLM Inference task. Nothing leaves the phone
 * and nothing costs money; each question takes a few seconds of CPU, which
 * is why the judge asks on a cadence rather than on every word. The model
 * file (about 550 MB) is downloaded once into app-private storage.
 */
class LocalJudge(modelFile: File) : TranscriptJudge, AutoCloseable {
    private val llm: LlmInference
    @Volatile var calls = 0L
        private set
    @Volatile var lastMillis = 0L
        private set

    init {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(MAX_TOKENS)
            .build()
        llm = LlmInference.createFromOptions(APP_CONTEXT!!, options)
    }

    override fun judge(transcript: String, channel: String): Verdict? {
        val t0 = System.currentTimeMillis()
        val prompt = chatPrompt(JudgeDetector.prompt(channel), "Transcript of the last 40 seconds:\n$transcript")
        val out = synchronized(llm) { llm.generateResponse(prompt) }
        lastMillis = System.currentTimeMillis() - t0
        calls++
        return Verdict.parse(out.lineSequence().firstOrNull { it.isNotBlank() } ?: out)
    }

    override fun close() { runCatching { llm.close() } }

    companion object {
        /** The instruction + transcript must fit with the answer: 1280 tokens of context in the bundled model. */
        const val MAX_TOKENS = 1024
        const val MODEL_FILE = "judge.task"
        /** Qwen2.5 0.5B, 8-bit, packaged for LiteRT / MediaPipe by the LiteRT community; Apache-2.0, no login needed. */
        const val DEFAULT_URL = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        const val DEFAULT_SIZE_MB = 547
        /** A larger choice for phones with the room: Qwen2.5 1.5B, 8-bit, about 1.6 GB. */
        const val LARGE_URL = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        @Volatile var APP_CONTEXT: Context? = null

        fun modelFile(context: Context) = File(File(context.filesDir, "llm"), MODEL_FILE)
        fun isInstalled(context: Context) = modelFile(context).let { it.isFile && it.length() > 10_000_000 }

        /** The Qwen chat layout; the model answers after the final assistant tag. */
        fun chatPrompt(system: String, user: String): String =
            "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"

        /** Download the model once; progress is 0..100. Blocking — call off the main thread. Resumable on retry. */
        fun install(context: Context, url: String, onProgress: (Int) -> Unit) {
            val target = modelFile(context); target.parentFile?.mkdirs()
            val part = File(target.path + ".part")
            val have = if (part.isFile) part.length() else 0L
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20_000; conn.readTimeout = 60_000
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("download failed: HTTP $code")
            val resuming = code == 206
            val total = (if (resuming) have else 0L) + conn.contentLengthLong.coerceAtLeast(0L)
            conn.inputStream.use { inp -> FileOutputStream(part, resuming).use { out ->
                val buf = ByteArray(256 * 1024); var got = if (resuming) have else 0L; var n: Int
                while (inp.read(buf).also { n = it } > 0) { out.write(buf, 0, n); got += n; if (total > 0) onProgress((got * 100 / total).toInt().coerceIn(0, 99)) }
            } }
            if (!part.renameTo(target)) { target.delete(); part.renameTo(target) }
            onProgress(100)
        }
    }
}
