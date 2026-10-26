package mutualstake.support

import mutualstake.agents.Claude
import mutualstake.agents.StructuredCall
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Claude, scripted for integration tests: answers come back in the order the test queued them. Test-only: with
 * no key the real app answers no_credentials rather than ever falling back to this.
 */
class FakeClaude : Claude {
    @Volatile override var available: Boolean = true
    private val answers = ConcurrentLinkedDeque<Any>()
    val calls = CopyOnWriteArrayList<StructuredCall<*>>()

    fun answer(vararg next: Any) {
        answers.addAll(next)
    }

    fun reset() {
        available = true
        answers.clear()
        calls.clear()
    }

    override fun <T : Any> call(call: StructuredCall<T>): T {
        calls += call
        val next = answers.pollFirst() ?: error("FakeClaude has no answer queued for ${call.type.simpleName}")
        if (next is RuntimeException) throw next
        return call.type.cast(next)
    }
}

@TestConfiguration(proxyBeanMethods = false)
class FakeClaudeConfig {
    @Bean
    @Primary
    fun fakeClaude(): FakeClaude = FakeClaude()
}
