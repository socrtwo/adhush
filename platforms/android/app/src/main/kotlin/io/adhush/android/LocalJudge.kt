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
 * file is downloaded once into app-private storage; three sizes are offered
 * ([TIERS]) so a phone with the room can run a sharper model.
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

    /** One downloadable model. [file] keeps each size in its own file, so switching never clobbers a finished download. */
    data class Tier(val key: String, val label: String, val url: String, val sizeMb: Int, val file: String, val ramGb: Int)

    companion object {
        /** The instruction + transcript must fit with the answer: 1280 tokens of context in the smallest model. */
        const val MAX_TOKENS = 1024
        /**
         * All three are Qwen instruct models packaged by the LiteRT community on Hugging Face; Apache-2.0, no login
         * needed. The small and medium files are MediaPipe `.task` bundles; the large one is the newer `.litertlm`
         * bundle (there is no plain 3B Qwen chat bundle; Qwen3 4B is the next size up). Phones with 8 GB should stay
         * on small; 12 GB or more can run any of them.
         */
        val TIERS = listOf(
            Tier("small", "Qwen 2.5 — 0.5 billion", "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task", 547, "judge.task", 4),
            Tier("medium", "Qwen 2.5 — 1.5 billion", "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task", 1599, "judge-1.5b.task", 6),
            Tier("large", "Qwen 3 — 4 billion", "https://huggingface.co/litert-community/Qwen3-4B-Instruct-2507/resolve/main/qwen3_4b_instruct_2507_mixed_int4.litertlm", 2660, "judge-4b.litertlm", 8),
        )
        val DEFAULT_URL = TIERS[0].url
        val DEFAULT_SIZE_MB = TIERS[0].sizeMb
        @Volatile var APP_CONTEXT: Context? = null

        fun tier(context: Context): Tier = tier(Settings(context).localModelSize)
        fun tier(key: String): Tier = TIERS.firstOrNull { it.key == key } ?: TIERS[0]
        fun modelFile(context: Context, tier: Tier = tier(context)) = File(File(context.filesDir, "llm"), tier.file)
        /** The file is there and, when its expected size is known, every byte of it. */
        fun isInstalled(context: Context, tier: Tier = tier(context)): Boolean {
            val f = modelFile(context, tier)
            if (!f.isFile) return false
            val expected = File(f.path + ".size").takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
            return if (expected != null) f.length() == expected else f.length() > 10_000_000
        }
        /** Which sizes are already on the phone. */
        fun installed(context: Context): List<Tier> = TIERS.filter { isInstalled(context, it) }

        /** The Qwen chat layout; the model answers after the final assistant tag. */
        fun chatPrompt(system: String, user: String): String =
            "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"

        /** True while a download runs: a second press must not start a second writer on the same file. */
        @Volatile var installing = false
            private set

        fun partFile(context: Context, tier: Tier = tier(context)) = File(modelFile(context, tier).path + ".part")
        /** A stopped download waiting to be resumed, or null. */
        fun partial(context: Context, tier: Tier = tier(context)) = Downloads.partial(partFile(context, tier))
        fun deletePartials(context: Context) = Downloads.deletePartials(File(context.filesDir, "llm"))

        /**
         * Download the chosen size once; progress is 0..100. Blocking — call off
         * the main thread. Resumes a stopped download; a short file is never
         * installed (0.28.4). Its expected size is kept beside it, so a model
         * counts as installed only when every byte is there.
         */
        fun install(context: Context, url: String, onProgress: (Int) -> Unit, tier: Tier = tier(context)) {
            synchronized(this) { check(!installing) { "a download is already running — wait for it to finish" }; installing = true }
            try {
                val target = modelFile(context, tier)
                if (isInstalled(context, tier)) { onProgress(100); return }
                val part = partFile(context, tier)
                Downloads.fetch(url, part, onProgress)
                File(target.path + ".size").writeText(part.length().toString())
                Downloads.install(part, target)
                onProgress(100)
            } finally { installing = false }
        }
    }
}
