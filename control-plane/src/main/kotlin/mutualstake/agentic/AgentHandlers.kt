package mutualstake.agentic

import mutualstake.agents.AgentStoppedException
import mutualstake.agents.Negotiation
import mutualstake.agents.SellerAgent
import mutualstake.agents.WriteOutcome
import mutualstake.contract.ContractRepository
import mutualstake.contract.ContractState
import mutualstake.outbox.ClassifiedFailure
import mutualstake.outbox.OutboxEvent
import mutualstake.outbox.OutboxHandler
import mutualstake.outbox.OutboxRepository
import mutualstake.outbox.OutboxType
import org.springframework.stereotype.Component

/** Claude declined or was cut off: retrying the same turn will not help. The contract says so. */
class AgentStopped(override val reason: String) : RuntimeException(reason), ClassifiedFailure {
    override val retryable = false
}

/**
 * One negotiation turn per outbox event (US-8). The buyer's and the seller's turns are different event types,
 * so the next turn can be queued while this one is still live. Whose turn it really is comes from the offer
 * log, so a retried or duplicated event does nothing.
 */
abstract class AgentTurnHandler(
    private val negotiation: Negotiation,
    private val port: AgentPort,
    private val outbox: OutboxRepository,
    private val contracts: ContractRepository,
    private val buyerSide: Boolean,
) : OutboxHandler {
    override fun handle(event: OutboxEvent) {
        val deal = port.deal(event.aggregateId)
        val party = if (buyerSide) deal.buyer else deal.seller
        val outcome = try {
            negotiation.turn(event.aggregateId, party)
        } catch (e: AgentStoppedException) {
            throw AgentStopped("agent_stopped: $party's agent: ${e.message}")
        }
        when (outcome) {
            is Negotiation.Outcome.Moved -> outbox.enqueue(event.aggregateId, if (buyerSide) OutboxType.AGENT_TURN_SELLER else OutboxType.AGENT_TURN_BUYER)
            is Negotiation.Outcome.NoDeal -> contracts.flag(event.aggregateId, "no_deal: ${outcome.reason}")
            Negotiation.Outcome.Locked, Negotiation.Outcome.NotMyTurn -> Unit
        }
    }

    override fun onDead(event: OutboxEvent, reason: String) = contracts.flag(event.aggregateId, reason)
}

@Component
class BuyerTurnHandler(negotiation: Negotiation, port: AgentPort, outbox: OutboxRepository, contracts: ContractRepository) :
    AgentTurnHandler(negotiation, port, outbox, contracts, buyerSide = true) {
    override val type = OutboxType.AGENT_TURN_BUYER
}

@Component
class SellerTurnHandler(negotiation: Negotiation, port: AgentPort, outbox: OutboxRepository, contracts: ContractRepository) :
    AgentTurnHandler(negotiation, port, outbox, contracts, buyerSide = false) {
    override val type = OutboxType.AGENT_TURN_SELLER
}

/** The seller's agent writes the delivery, dry-running the deal's own verifier until a draft passes (US-9). */
@Component
class AgentDeliverHandler(
    private val seller: SellerAgent,
    private val port: AgentPort,
    private val contracts: ContractRepository,
) : OutboxHandler {
    override val type = OutboxType.AGENT_DELIVER

    override fun handle(event: OutboxEvent) {
        if (contracts.find(event.aggregateId)?.state != ContractState.BOTH_HELD) return
        val outcome = try {
            seller.write(port.deal(event.aggregateId), port)
        } catch (e: AgentStoppedException) {
            throw AgentStopped("agent_stopped: the seller's agent: ${e.message}")
        }
        if (outcome is WriteOutcome.CouldNotMeetTest) {
            // An honest seller does not deliver work it knows fails; the deadline will treat it as a no-show.
            contracts.flag(event.aggregateId, "seller_could_not_meet_the_test: ${outcome.attempts} drafts failed the verifier; nothing was delivered. Last: ${outcome.last}")
        }
    }

    override fun onDead(event: OutboxEvent, reason: String) = contracts.flag(event.aggregateId, reason)
}
