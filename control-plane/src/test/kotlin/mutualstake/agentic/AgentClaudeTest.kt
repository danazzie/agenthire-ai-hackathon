package mutualstake.agentic

import com.fasterxml.jackson.databind.ObjectMapper
import mutualstake.agents.AnthropicClaude
import mutualstake.agents.BuyerAgent
import mutualstake.agents.Negotiation
import mutualstake.agents.SellerAgent
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
import org.junit.jupiter.api.Test
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

    @Test
    fun `real Claude drafts, negotiates and writes a delivery that passes the US-1 test`() {
        assumeTrue(key.isNotBlank(), "no ANTHROPIC_API_KEY")
        fake.reset()
        clock.reset()
        quiesceOutbox(jdbc)
        sessions.startNew()
        val claude = AnthropicClaude(key)
        val buyer = BuyerAgent(claude, ZoneId.of("Europe/Istanbul"))
        val seller = SellerAgent(claude)

        val job = buyer.draftJob("50-word coffee-shop blurb, max \$2, by 18:00, no lorem ipsum", clock.instant())
        println("draft: cap ${job.feeCapCents}, deadline ${job.deadlineAt}, test ${job.test}")
        assertEquals(200, job.feeCapCents)
        val verifier = verifierService.compile(job.jobText, mapper.readTree(job.test))

        val contract = negotiationService.open(parties.byHandle("alex")!!, "maya", job.jobText, Money(job.feeCapCents))
        jobs.insert(AgentJob(contract.id, "50-word coffee-shop blurb, max \$2, by 18:00, no lorem ipsum", verifier.id, job.deadlineAt))
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
        val outcome = seller.write(port.deal(contract.id), port)
        println("seller: $outcome")
        assertTrue(outcome is WriteOutcome.Delivered, "$outcome")
        poller.drain()
        assertEquals(ContractState.PASS, contracts.find(contract.id)!!.state)
    }
}
