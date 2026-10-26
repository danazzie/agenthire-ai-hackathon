package mutualstake.agents

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.StructuredOutputConfig
import java.time.Duration

/** How hard Claude thinks on a call. Set explicitly every time: Claude Opus 5.5 defaults to medium. */
enum class Effort(internal val sdk: OutputConfig.Effort) { LOW(OutputConfig.Effort.LOW), MEDIUM(OutputConfig.Effort.MEDIUM), HIGH(OutputConfig.Effort.HIGH) }

/** One structured call: the reply must deserialize into [type]. The agents never get free text back. */
data class StructuredCall<T>(val system: String, val user: String, val type: Class<T>, val effort: Effort, val maxTokens: Long = 4000)

/** Claude, as the agents see it. A test double stands in for it in every test that is not tagged "claude". */
interface Claude {
    /** False when there is no way to reach Claude (no key): callers say so instead of pretending. */
    val available: Boolean get() = true

    fun <T : Any> call(call: StructuredCall<T>): T
}

/** Claude declined (stop_reason refusal) or the answer was cut off. The agent stops; no state changes. */
class AgentStoppedException(message: String) : RuntimeException(message)

/**
 * The real Claude, through the Anthropic Java SDK. Server-side fallbacks are on: if a request is refused, the
 * API retries it on a fallback model it chooses ("default"), so a refusal is rare rather than a dead end.
 */
class AnthropicClaude(apiKey: String, private val model: String = MODEL, timeout: Duration = Duration.ofSeconds(90)) : Claude {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(timeout).build()

    override fun <T : Any> call(call: StructuredCall<T>): T {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(call.maxTokens)
            .system(call.system)
            .outputConfig(StructuredOutputConfig.builder<T>().effort(call.effort.sdk).format(call.type).build())
            .addUserMessage(call.user)
            .putAdditionalHeader("anthropic-beta", FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build()
        val reply = client.messages().create(params)
        when (reply.stopReason().orElse(null)) {
            StopReason.REFUSAL -> throw AgentStoppedException("Claude declined this turn")
            StopReason.MAX_TOKENS -> throw AgentStoppedException("Claude's answer was cut off at ${call.maxTokens} tokens")
            else -> Unit
        }
        return reply.content().firstNotNullOfOrNull { it.text().orElse(null)?.text() }
            ?: throw AgentStoppedException("Claude returned no structured answer")
    }

    companion object {
        const val MODEL = "claude-opus-5-5"
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    }
}
