package mutualstake.agentic

import mutualstake.agents.AgentStoppedException
import mutualstake.agents.JobKind
import mutualstake.agents.Negotiation
import mutualstake.agents.ReportOutcome
import mutualstake.agents.RunnerReport
import mutualstake.agents.SellerAgent
import mutualstake.agents.toReport
import mutualstake.contract.ContractRepository
import mutualstake.contract.ContractState
import mutualstake.outbox.ClassifiedFailure
import mutualstake.outbox.OutboxEvent
import mutualstake.outbox.OutboxHandler
import mutualstake.outbox.OutboxRepository
import mutualstake.outbox.OutboxType
import org.springframework.stereotype.Component
import java.util.UUID

/** Claude declined or was cut off: retrying the same turn will not help. The contract says so. */
class AgentStopped(override val reason: String) : RuntimeException(reason), ClassifiedFailure {
    override val retryable = false
}

/** The outbox event behind each kind of agent step. A draft has none: it waits in [DraftRelay], not the outbox. */
val JobKind.outboxType: OutboxType?
    get() = when (this) {
        JobKind.BUYER_TURN -> OutboxType.AGENT_TURN_BUYER
        JobKind.SELLER_TURN -> OutboxType.AGENT_TURN_SELLER
        JobKind.DELIVER -> OutboxType.AGENT_DELIVER
        JobKind.DRAFT -> null
    }

val OutboxType.jobKind: JobKind?
    get() = JobKind.entries.firstOrNull { it.outboxType == this }

/** Every outbox event an agent runs, wherever the agents are hosted. */
val AGENT_TYPES: Set<OutboxType> = JobKind.entries.mapNotNull { it.outboxType }.toSet()

/**
 * What the control plane does once an agent step has finished, whether the agents ran in this process or on a
 * remote runner: queue the next turn, or say on the deal why nothing more will happen. Outbox writes stay here.
 */
@Component
class AgentOutcomes(private val outbox: OutboxRepository, private val contracts: ContractRepository) {
    fun record(contractId: UUID, kind: JobKind, report: RunnerReport) {
        when (report.outcome) {
            ReportOutcome.MOVED -> outbox.enqueue(contractId, if (kind == JobKind.BUYER_TURN) OutboxType.AGENT_TURN_SELLER else OutboxType.AGENT_TURN_BUYER)
            ReportOutcome.NO_DEAL -> contracts.flag(contractId, "no_deal: ${report.reason}")
            // An honest seller does not deliver work it knows fails; the deadline will treat it as a no-show.
            ReportOutcome.COULD_NOT_MEET_TEST -> contracts.flag(
                contractId,
                "seller_could_not_meet_the_test: ${report.attempts} drafts failed the verifier; nothing was delivered. Last: ${report.reason}",
            )
            ReportOutcome.LOCKED, ReportOutcome.NOT_MY_TURN, ReportOutcome.DELIVERED -> Unit
            ReportOutcome.STOPPED, ReportOutcome.FAILED -> throw IllegalArgumentException("${report.outcome} is a failure, not a finished step")
            ReportOutcome.DRAFTED, ReportOutcome.REJECTED -> throw IllegalArgumentException("${report.outcome} ends a draft, not a deal's step")
        }
    }

    /** Which outcomes a step of [kind] can finish with. */
    fun fits(kind: JobKind, outcome: ReportOutcome): Boolean = when (outcome) {
        ReportOutcome.STOPPED, ReportOutcome.FAILED -> true
        ReportOutcome.DELIVERED, ReportOutcome.COULD_NOT_MEET_TEST -> kind == JobKind.DELIVER
        ReportOutcome.MOVED, ReportOutcome.LOCKED, ReportOutcome.NOT_MY_TURN, ReportOutcome.NO_DEAL -> kind == JobKind.BUYER_TURN || kind == JobKind.SELLER_TURN
        ReportOutcome.DRAFTED, ReportOutcome.REJECTED -> kind == JobKind.DRAFT
    }

    fun flag(event: OutboxEvent, reason: String) = contracts.flag(event.aggregateId, reason)
}

/**
 * One negotiation turn per outbox event (US-8). The buyer's and the seller's turns are different event types,
 * so the next turn can be queued while this one is still live. Whose turn it really is comes from the offer
 * log, so a retried or duplicated event does nothing. With AGENTS_REMOTE on, the runner takes these instead.
 */
abstract class AgentTurnHandler(
    private val negotiation: Negotiation,
    private val port: AgentPort,
    private val outcomes: AgentOutcomes,
    private val kind: JobKind,
) : OutboxHandler {
    override val type = requireNotNull(kind.outboxType)

    override fun handle(event: OutboxEvent) {
        val deal = port.deal(event.aggregateId)
        val party = if (kind == JobKind.BUYER_TURN) deal.buyer else deal.seller
        val outcome = try {
            negotiation.turn(event.aggregateId, party)
        } catch (e: AgentStoppedException) {
            throw AgentStopped("agent_stopped: $party's agent: ${e.message}")
        }
        outcomes.record(event.aggregateId, kind, outcome.toReport())
    }

    override fun onDead(event: OutboxEvent, reason: String) = outcomes.flag(event, reason)
}

@Component
class BuyerTurnHandler(negotiation: Negotiation, port: AgentPort, outcomes: AgentOutcomes) : AgentTurnHandler(negotiation, port, outcomes, JobKind.BUYER_TURN)

@Component
class SellerTurnHandler(negotiation: Negotiation, port: AgentPort, outcomes: AgentOutcomes) : AgentTurnHandler(negotiation, port, outcomes, JobKind.SELLER_TURN)

/** The seller's agent writes the delivery, dry-running the deal's own verifier until a draft passes (US-9). */
@Component
class AgentDeliverHandler(
    private val seller: SellerAgent,
    private val port: AgentPort,
    private val contracts: ContractRepository,
    private val outcomes: AgentOutcomes,
) : OutboxHandler {
    override val type = OutboxType.AGENT_DELIVER

    override fun handle(event: OutboxEvent) {
        if (contracts.find(event.aggregateId)?.state != ContractState.BOTH_HELD) return
        val outcome = try {
            seller.write(port.deal(event.aggregateId), port)
        } catch (e: AgentStoppedException) {
            throw AgentStopped("agent_stopped: the seller's agent: ${e.message}")
        }
        outcomes.record(event.aggregateId, JobKind.DELIVER, outcome.toReport())
    }

    override fun onDead(event: OutboxEvent, reason: String) = outcomes.flag(event, reason)
}
