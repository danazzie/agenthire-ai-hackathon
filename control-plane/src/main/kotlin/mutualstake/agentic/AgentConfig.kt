package mutualstake.agentic

import mutualstake.agents.AnthropicClaude
import mutualstake.agents.BuyerAgent
import mutualstake.agents.Claude
import mutualstake.agents.Negotiation
import mutualstake.agents.SellerAgent
import mutualstake.agents.StructuredCall
import mutualstake.config.MutualStakeProperties
import mutualstake.outbox.ClassifiedFailure
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.ZoneId

/** The agents are off without a key, and say so honestly instead of pretending to negotiate. */
class AgentsUnavailableException : RuntimeException("no_credentials: ANTHROPIC_API_KEY is not set, so the Claude agents are off"), ClassifiedFailure {
    override val reason: String = message!!
    override val retryable = false
}

@Configuration
class AgentConfig(private val props: MutualStakeProperties) {
    @Bean
    fun claude(): Claude =
        if (props.agents.configured) {
            AnthropicClaude(props.agents.anthropicApiKey)
        } else {
            object : Claude {
                override val available = false

                override fun <T : Any> call(call: StructuredCall<T>): T = throw AgentsUnavailableException()
            }
        }

    @Bean
    fun agentZone(): ZoneId = props.agents.timezone.takeIf { it.isNotBlank() }?.let(ZoneId::of) ?: ZoneId.systemDefault()

    @Bean
    fun buyerAgent(claude: Claude, agentZone: ZoneId) = BuyerAgent(claude, agentZone)

    @Bean
    fun sellerAgent(claude: Claude) = SellerAgent(claude, props.agents.writingAttempts)

    @Bean
    fun negotiation(port: AgentPort, buyerAgent: BuyerAgent, sellerAgent: SellerAgent) = Negotiation(port, buyerAgent, sellerAgent, props.agents.maxOffers)
}
