package mutualstake.agentic

import com.fasterxml.jackson.databind.ObjectMapper
import mutualstake.agents.AgentStoppedException
import mutualstake.agents.BuyerAgent
import mutualstake.agents.Claude
import mutualstake.agents.JobProposal
import mutualstake.agents.JobRejectedException
import mutualstake.agents.ReportOutcome
import mutualstake.api.ApiException
import mutualstake.config.MutualStakeProperties
import mutualstake.contract.Contract
import mutualstake.contract.ContractRepository
import mutualstake.contract.ContractState
import mutualstake.contract.NegotiationService
import mutualstake.contract.Terms
import mutualstake.delivery.DeliveryService
import mutualstake.money.Money
import mutualstake.outbox.OutboxRepository
import mutualstake.outbox.OutboxType
import mutualstake.party.PartyRepository
import mutualstake.verifier.Verifier
import mutualstake.verifier.VerifierRepository
import mutualstake.verifier.VerifierService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class JobDraft(val sentence: String, val jobText: String, val feeCapMinor: Long, val deadlineAt: Instant, val verifier: Verifier)

enum class DeliveryMode {
    /** The seller's agent writes it and dry-runs it against the verifier until it passes. */
    AGENT,

    /** The demo's bad delivery: PROTOTYPES' lorem ipsum, delivered as is. Labelled as scripted wherever it shows. */
    SCRIPTED_JUNK,
}

/** Screens 1 and 2 (US-1, US-8): a sentence becomes a confirmed test, then the two agents negotiate. */
@Service
class AgentService(
    private val claude: Claude,
    private val buyer: BuyerAgent,
    private val verifierService: VerifierService,
    private val verifiers: VerifierRepository,
    private val negotiation: NegotiationService,
    private val deliveries: DeliveryService,
    private val contracts: ContractRepository,
    private val parties: PartyRepository,
    private val jobs: AgentJobRepository,
    private val outbox: OutboxRepository,
    private val props: MutualStakeProperties,
    private val hosting: AgentHosting,
    private val drafts: DraftRelay,
    private val agentZone: ZoneId,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {
    /**
     * The buyer's agent reads the sentence; the test is compiled here. Nothing touches PayPal until the person
     * confirms. With the agents on Astropods the runner reads it, and this process needs no Claude at all.
     */
    fun draft(sentence: String): JobDraft {
        val proposal = if (hosting.remote) draftOnRunner(sentence) else draftHere(sentence)
        val verifier = verifierService.compile(proposal.jobText, mapper.readTree(proposal.test))
        return JobDraft(sentence.trim(), proposal.jobText, proposal.feeCapCents, proposal.deadlineAt, verifier)
    }

    private fun draftHere(sentence: String): JobProposal {
        requireAgents()
        return try {
            buyer.draftJob(sentence, clock.instant())
        } catch (e: JobRejectedException) {
            throw ApiException.unprocessable("job_rejected", e.message ?: "the sentence cannot become a job")
        } catch (e: AgentStoppedException) {
            throw ApiException(HttpStatus.BAD_GATEWAY, "agent_stopped", e.message ?: "the buyer's agent stopped")
        }
    }

    /**
     * The same answers as [draftHere], from the runner. Nobody is kept waiting for a runner that is not there, or for
     * one built before drafting moved, which asks for work but never takes a draft.
     */
    private fun draftOnRunner(sentence: String): JobProposal {
        val since = Instant.now().minus(RUNNER_GONE)
        fun fresh(at: Instant?) = at != null && at.isAfter(since)
        if (!fresh(hosting.draftRunnerSeenAt)) {
            throw if (fresh(hosting.runnerSeenAt)) {
                ApiException(HttpStatus.SERVICE_UNAVAILABLE, "runner_cannot_draft", "the agent runner on Astropods is an older build that does not draft jobs; redeploy it")
            } else {
                ApiException(HttpStatus.SERVICE_UNAVAILABLE, "runner_not_connected", "the agents run on Astropods and their runner is not connected, so nobody can draft the job")
            }
        }
        val report = drafts.ask(sentence, clock.instant(), agentZone, DRAFT_WAIT)
            ?: throw ApiException(HttpStatus.GATEWAY_TIMEOUT, "runner_timeout", "the agent runner did not draft the job within ${DRAFT_WAIT.seconds} s; try again")
        return when (report.outcome) {
            ReportOutcome.DRAFTED -> requireNotNull(report.proposal)
            ReportOutcome.REJECTED -> throw ApiException.unprocessable("job_rejected", report.reason ?: "the sentence cannot become a job")
            ReportOutcome.STOPPED -> throw ApiException(HttpStatus.BAD_GATEWAY, "agent_stopped", report.reason ?: "the buyer's agent stopped")
            else -> throw ApiException(HttpStatus.BAD_GATEWAY, "runner_failed", report.reason ?: "the agent runner could not draft the job")
        }
    }

    /** The person confirmed the job and its test: open the deal and let the buyer's agent make the first offer. */
    @Transactional
    fun start(sentence: String, jobText: String, feeCap: Money, deadlineAt: Instant, verifierId: UUID): Contract {
        requireNegotiators()
        val verifier = verifiers.byId(verifierId) ?: throw ApiException.unprocessable("unknown_verifier", "no verifier $verifierId")
        if (verifier.spec == null) throw ApiException.unprocessable("verifier_not_compiled", "the job's verifier must be compiled from a structured test")
        val deadline = Terms.normalizeDeadline(deadlineAt)
        if (!deadline.isAfter(clock.instant())) throw ApiException.unprocessable("deadline_passed", "the deadline must be in the future")
        val buyerParty = parties.byHandle(props.parties.buyer.handle) ?: throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "no_credentials", "the buyer is not seeded")
        val contract = negotiation.open(buyerParty, props.parties.seller.handle, jobText, feeCap)
        jobs.insert(AgentJob(contract.id, sentence.trim(), verifier.id, deadline))
        outbox.enqueue(contract.id, OutboxType.AGENT_TURN_BUYER)
        return contract
    }

    fun deliver(contractId: UUID, mode: DeliveryMode) {
        val contract = contracts.find(contractId) ?: throw ApiException.notFound("contract $contractId")
        if (contract.state != ContractState.BOTH_HELD) throw ApiException.conflict("not_funded", "deliver once both holds are in place; this deal is ${contract.state}")
        when (mode) {
            DeliveryMode.AGENT -> {
                requireNegotiators()
                outbox.enqueue(contractId, OutboxType.AGENT_DELIVER)
            }
            DeliveryMode.SCRIPTED_JUNK -> deliveries.submit(contractId, parties.byId(contract.sellerId), SCRIPTED_JUNK)
        }
    }

    /** With the agents hosted remotely, the runner negotiates and delivers: this process needs no Claude for it. */
    private fun requireNegotiators() {
        if (!hosting.remote) requireAgents()
    }

    private fun requireAgents() {
        if (!claude.available) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "no_credentials", "ANTHROPIC_API_KEY is not set, so the Claude agents are off; the scripted negotiation in docs/demo/sandbox-run.mjs still works")
        }
    }

    companion object {
        /** PROTOTYPES' fail-branch delivery, word for word. */
        const val SCRIPTED_JUNK = "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor."

        /** Longer than the runner's own step limit (90 s), so a draft it is still writing is not given up on. */
        val DRAFT_WAIT: Duration = Duration.ofSeconds(100)

        /** A runner that has not asked for work for this long is gone (or all its workers are busy for longer than a step can take). */
        val RUNNER_GONE: Duration = Duration.ofMinutes(2)
    }
}
