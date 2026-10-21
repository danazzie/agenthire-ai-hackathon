package mutualstake.agentic

import mutualstake.agents.Deal
import mutualstake.agents.DealOffer
import mutualstake.agents.DryRunResult
import mutualstake.agents.MoveRefusedException
import mutualstake.agents.ProtocolPort
import mutualstake.api.ApiException
import mutualstake.contract.ContractRepository
import mutualstake.contract.NegotiationService
import mutualstake.contract.OfferKind
import mutualstake.contract.OfferRepository
import mutualstake.db.instant
import mutualstake.db.toTimestamptz
import mutualstake.db.uuid
import mutualstake.delivery.DeliveryService
import mutualstake.money.Money
import mutualstake.party.PartyRepository
import mutualstake.verifier.VerifierRepository
import mutualstake.verifier.VerifierService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

data class AgentJob(val contractId: UUID, val sentence: String, val verifierId: UUID, val deadlineAt: Instant)

@Repository
class AgentJobRepository(private val jdbc: JdbcClient) {
    fun insert(job: AgentJob) {
        jdbc.sql("insert into agent_job (contract_id, sentence, verifier_id, deadline_at) values (:c, :s, :v, :d)")
            .param("c", job.contractId).param("s", job.sentence).param("v", job.verifierId).param("d", job.deadlineAt.toTimestamptz()).update()
    }

    fun find(contractId: UUID): AgentJob? =
        jdbc.sql("select * from agent_job where contract_id = :c").param("c", contractId)
            .query { rs, _ -> AgentJob(rs.uuid("contract_id"), rs.getString("sentence"), rs.uuid("verifier_id"), rs.instant("deadline_at")) }
            .optional().orElse(null)
}

/**
 * The protocol as the agents may use it: the same services the HTTP API calls, and no others. A refusal from
 * the control plane (over the cap, bond too large, mandate) goes back to the agent as feedback.
 */
@Component
class AgentPort(
    private val contracts: ContractRepository,
    private val offers: OfferRepository,
    private val parties: PartyRepository,
    private val verifiers: VerifierRepository,
    private val jobs: AgentJobRepository,
    private val negotiation: NegotiationService,
    private val verifierService: VerifierService,
    private val deliveries: DeliveryService,
) : ProtocolPort {

    override fun deal(contractId: UUID): Deal {
        val contract = requireNotNull(contracts.find(contractId)) { "no contract $contractId" }
        val job = requireNotNull(jobs.find(contractId)) { "contract $contractId is not an agent job" }
        val verifier = requireNotNull(verifiers.byId(job.verifierId))
        val handles = listOf(contract.buyerId, contract.sellerId).associateWith { parties.byId(it).handle }
        return Deal(
            id = contract.id,
            state = contract.state.name,
            jobText = contract.jobText,
            buyer = handles.getValue(contract.buyerId),
            seller = handles.getValue(contract.sellerId),
            feeCapCents = contract.feeCap.minor,
            deadlineAt = job.deadlineAt,
            verifierId = verifier.id,
            verifierSha256 = verifier.sha256,
            verifierSource = verifier.source,
            test = verifier.spec.orEmpty(),
            offers = offers.forContract(contractId).map { DealOffer(handles.getValue(it.partyId), it.kind.name, it.terms.fee.minor, it.terms.bond.minor, it.note) },
        )
    }

    override fun propose(contractId: UUID, party: String, feeCents: Long, bondCents: Long, note: String) {
        val job = requireNotNull(jobs.find(contractId))
        refusalsAsFeedback { negotiation.offer(contractId, party(party), OfferKind.PROPOSE, Money(feeCents), Money(bondCents), job.deadlineAt, job.verifierId, note) }
    }

    /** Copies the counterparty's latest terms exactly: the model never retypes an amount it accepts. */
    override fun accept(contractId: UUID, party: String, note: String?) {
        val me = party(party)
        val theirs = offers.forContract(contractId).lastOrNull { it.partyId != me.id }
            ?: throw MoveRefusedException("nothing_to_accept", "the other side has made no offer yet")
        refusalsAsFeedback {
            negotiation.offer(contractId, me, OfferKind.ACCEPT, theirs.terms.fee, theirs.terms.bond, theirs.terms.deadlineAt, theirs.terms.verifierId, note)
        }
    }

    override fun dryRun(verifierId: UUID, text: String): DryRunResult {
        val run = verifierService.dryRun(verifierId, text).run
        return DryRunResult(
            run.outcome.name,
            run.summary ?: run.error,
            run.checks.filterNot { it.passed }.map { "${it.name}: ${it.detail}${it.expected?.let { e -> " (the test needs $e)" }.orEmpty()}" },
        )
    }

    override fun deliver(contractId: UUID, party: String, text: String) {
        deliveries.submit(contractId, party(party), text)
    }

    private fun party(handle: String) = requireNotNull(parties.byHandle(handle)) { "no party $handle" }

    private fun refusalsAsFeedback(block: () -> Unit) {
        try {
            block()
        } catch (e: ApiException) {
            throw MoveRefusedException(e.code, e.message ?: e.code)
        }
    }
}
