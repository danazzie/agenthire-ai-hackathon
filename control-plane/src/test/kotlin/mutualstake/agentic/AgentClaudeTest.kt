package mutualstake.agentic

import com.fasterxml.jackson.databind.ObjectMapper
import mutualstake.agents.AnthropicClaude
import mutualstake.agents.BuyerAgent
import mutualstake.agents.Negotiation
import mutualstake.agents.ProtocolPort
import mutualstake.agents.SellerAgent
import mutualstake.agents.SellerSpecialty
import mutualstake.agents.WriteOutcome
import mutualstake.contract.ContractRepository
import mutualstake.contract.ContractState
import mutualstake.hold.HoldRepository
import mutualstake.money.Money
import mutualstake.outbox.OutboxPoller
import mutualstake.outbox.OutboxRepository
import mutualstake.outbox.OutboxType
import mutualstake.party.PartyRepository
import mutualstake.session.SessionService
import mutualstake.support.FakePayPal
import mutualstake.support.IntegrationTest
import mutualstake.support.MutableClock
import mutualstake.support.quiesceOutbox
import mutualstake.verifier.VerifierService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.io.File
import java.time.ZoneId

/**
 * Opt-in, against the real Claude API: `./gradlew :control-plane:claudeTest` with ANTHROPIC_API_KEY in the
 * repo-root .env. The whole agent path on the US-1 sentence: draft, negotiate to a lock, then the seller writes
 * and dry-runs against the real Deno verifier until it passes. PayPal is the fake; nothing real is charged.
 */
@Tag("claude")
@IntegrationTest
class AgentClaudeTest @Autowired constructor(
    private val port: AgentPort,
    private val verifierService: VerifierService,
    private val jobs: AgentJobRepository,
    private val negotiationService: mutualstake.contract.NegotiationService,
    private val contracts: ContractRepository,
    private val holds: HoldRepository,
    private val parties: PartyRepository,
    private val sessions: SessionService,
    private val outbox: OutboxRepository,
    private val poller: OutboxPoller,
    private val fake: FakePayPal,
    private val clock: MutableClock,
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
) {
    private val key: String by lazy {
        System.getenv("ANTHROPIC_API_KEY").orEmpty().ifBlank {
            listOf(File("../.env"), File(".env")).firstOrNull(File::isFile)?.readLines()
                ?.firstOrNull { it.startsWith("ANTHROPIC_API_KEY=") }?.substringAfter("=")?.trim()?.removeSurrounding("\"").orEmpty()
        }
    }

    /** Each of Maya's agents, on the sentence the judges see for it; the blurb's is the US-1 sentence. */
    @ParameterizedTest
    @EnumSource(SellerSpecialty::class)
    fun `real Claude drafts, negotiates and writes a delivery that passes the test`(specialty: SellerSpecialty) {
        assumeTrue(key.isNotBlank(), "no ANTHROPIC_API_KEY")
        fake.reset()
        clock.reset()
        quiesceOutbox(jdbc)
        sessions.startNew()
        val claude = AnthropicClaude(key)
        val buyer = BuyerAgent(claude, ZoneId.of("Europe/Istanbul"))
        val seller = SellerAgent(claude)

        val job = buyer.draftJob(specialty.example, clock.instant())
        println("${specialty.id} draft: cap ${job.feeCapCents}, deadline ${job.deadlineAt}, agent ${job.sellerAgent}, test ${job.test}")
        assertEquals(specialty.id, job.sellerAgent, "the buyer's agent picked the right one of Maya's agents")
        if (specialty == SellerSpecialty.BLURB) assertEquals(200, job.feeCapCents)
        val verifier = verifierService.compile(job.jobText, mapper.readTree(job.test))

        val contract = negotiationService.open(parties.byHandle("alex")!!, "maya", job.jobText, Money(job.feeCapCents))
        jobs.insert(AgentJob(contract.id, specialty.example, verifier.id, job.deadlineAt, job.sellerAgent))
        val negotiation = Negotiation(port, buyer, seller)
        var party = "alex"
        repeat(12) {
            when (val outcome = negotiation.turn(contract.id, party)) {
                is Negotiation.Outcome.Moved -> party = outcome.next
                else -> return@repeat
            }
        }
        port.deal(contract.id).offers.forEach { println("  ${it.party} ${it.kind} ${it.feeCents}/${it.bondCents}: ${it.note}") }
        assertEquals(ContractState.TERMS_LOCKED, contracts.find(contract.id)!!.state)

        poller.drain()
        for (h in holds.forContract(contract.id)) {
            fake.approve(h.paypalOrderId!!, parties.byId(h.payerId).paypalEmail)
            outbox.enqueue(h.id, OutboxType.CHECK_ORDER)
        }
        poller.drain()
        // The same port, saying out loud what the agent delivered, so a person can read the work, not just the verdict.
        val showing = object : ProtocolPort by port {
            override fun deliver(contractId: java.util.UUID, party: String, text: String) {
                println("${specialty.id} delivered: $text")
                port.deliver(contractId, party, text)
            }
        }
        val outcome = seller.write(port.deal(contract.id), showing)
        println("${specialty.id} seller: $outcome")
        assertTrue(outcome is WriteOutcome.Delivered, "$outcome")
        poller.drain()
        assertEquals(ContractState.PASS, contracts.find(contract.id)!!.state)
    }
}
