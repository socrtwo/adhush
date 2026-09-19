package io.adhush.android

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.TextBlockParam
import io.adhush.core.JudgeDetector
import io.adhush.core.TranscriptJudge
import io.adhush.core.Verdict

/**
 * The seventh method's brain: Claude, over the API. One short question per
 * call — the fixed instruction (cached on Anthropic's side, so it is billed
 * once) plus the last 40 seconds of words — and a one-line answer. Only
 * text ever leaves the phone, never audio, and only while the switch is on.
 * Haiku 4.5 is the cheap default; Sonnet 5 and Opus 5 are choices.
 */
class ClaudeJudge(apiKey: String, private val model: String = DEFAULT_MODEL) : TranscriptJudge, AutoCloseable {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
    @Volatile var calls = 0L
        private set
    @Volatile var inputTokens = 0L
        private set
    @Volatile var outputTokens = 0L
        private set

    override fun judge(transcript: String, channel: String): Verdict? {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(64L)
            .systemOfTextBlockParams(listOf(
                TextBlockParam.builder()
                    .text(JudgeDetector.prompt(channel))
                    .cacheControl(CacheControlEphemeral.builder().build())
                    .build()))
            .addUserMessage("Transcript of the last 40 seconds:\n$transcript")
            .build()
        val response = try {
            client.messages().create(params)
        } catch (e: RateLimitException) {
            throw IllegalStateException("Claude rate limit — try again in a minute", e)
        } catch (e: AnthropicServiceException) {
            throw IllegalStateException("Claude API ${e.statusCode()}: ${e.message?.take(80)}", e)
        }
        calls++
        inputTokens += response.usage().inputTokens()
        outputTokens += response.usage().outputTokens()
        val text = response.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString(" ")
        return Verdict.parse(text)
    }

    override fun close() { runCatching { client.close() } }

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-4-5"
        val MODELS = linkedMapOf(
            "claude-haiku-4-5" to "Haiku 4.5 — cheapest (about 13 ¢ an hour of TV)",
            "claude-sonnet-5" to "Sonnet 5 — sharper (about 26 ¢ an hour)",
            "claude-opus-5" to "Opus 5 — best judgement (about 65 ¢ an hour)",
        )
    }
}
