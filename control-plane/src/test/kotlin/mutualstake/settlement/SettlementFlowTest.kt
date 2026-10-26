package mutualstake.settlement

import com.fasterxml.jackson.databind.ObjectMapper
import mutualstake.api.ApiException
import mutualstake.api.ContractViews
import mutualstake.api.Jar
import mutualstake.api.jarOf
import mutualstake.contract.Contract
import mutualstake.contract.ContractRepository
import mutualstake.contract.ContractState
import mutualstake.contract.NegotiationService
import mutualstake.contract.OfferKind.ACCEPT
import mutualstake.delivery.DeliveryService
import mutualstake.funding.Sweeper
import mutualstake.hold.Hold
import mutualstake.hold.HoldRepository
import mutualstake.hold.HoldSide
import mutualstake.hold.HoldSide.BUYER_FEE
import mutualstake.hold.HoldSide.SELLER_BOND
import mutualstake.hold.HoldState
import mutualstake.ledger.LedgerEvent
import mutualstake.ledger.LedgerRepository
import mutualstake.money.Money
import mutualstake.outbox.OutboxPoller
import mutualstake.outbox.OutboxRepository
import mutualstake.outbox.OutboxType
import mutualstake.party.Party
import mutualstake.party.PartyRepository
import mutualstake.paypal.PayoutRequest
import mutualstake.paypal.WebhookPrompter
import mutualstake.session.SessionRepository
import mutualstake.session.SessionService
import mutualstake.support.DemoTexts
import mutualstake.support.FakePayPal
import mutualstake.support.IntegrationTest
import mutualstake.support.MutableClock
import mutualstake.support.US1_TEST
import mutualstake.support.quiesceOutbox
import mutualstake.verifier.Verifier
import mutualstake.verifier.VerifierService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Both branches settled end to end: real verdicts from the Deno sandbox, a fake PayPal shaped like the sandbox. */
@IntegrationTest
class SettlementFlowTest @Autowired constructor(
    private val negotiation: NegotiationService,
    private val deliveries: DeliveryService,
    private val settlement: SettlementService,
    private val contracts: ContractRepository,
    private val holds: HoldRepository,
    private val parties: PartyRepository,
    private val ledger: LedgerRepository,
    private val verifierService: VerifierService,
    private val sessions: SessionService,
    private val sessionRows: SessionRepository,
    private val views: ContractViews,
    private val outbox: OutboxRepository,
    private val poller: OutboxPoller,
    private val sweeper: Sweeper,
    private val prompter: WebhookPrompter,
    private val fake: FakePayPal,
    private val clock: MutableClock,
    private val jdbc: JdbcClient,
    private val tx: TransactionTemplate,
    private val mapper: ObjectMapper,
) {
    private lateinit var alex: Party
    private lateinit var maya: Party
    private lateinit var verifier: Verifier

    @BeforeEach
    fun reset() {
        fake.reset()
        clock.reset()
        quiesceOutbox(jdbc)
        sessions.startNew(Money(3000), 2)
        alex = parties.byHandle("alex")!!
        maya = parties.byHandle("maya")!!
        verifier = verifierService.compile("50-word coffee-shop blurb, max \$2, by 18:00, no lorem ipsum (${UUID.randomUUID()})", mapper.readTree(US1_TEST))
    }

    private fun fundedDeal(): Contract {
        val c = negotiation.open(alex, "maya", "50-word coffee-shop blurb", Money(200))
        val deadline = clock.instant().plus(Duration.ofHours(1))
        negotiation.offer(c.id, maya, ACCEPT, Money(150), Money(40), deadline, verifier.id, null)
        negotiation.offer(c.id, alex, ACCEPT, Money(150), Money(40), deadline, verifier.id, null)
        poller.drain()
        for (h in holds.forContract(c.id)) {
            fake.approve(h.paypalOrderId!!, if (h.side == BUYER_FEE) alex.paypalEmail else maya.paypalEmail)
            outbox.enqueue(h.id, OutboxType.CHECK_ORDER)
        }
        poller.drain()
        assertEquals(ContractState.BOTH_HELD, state(c))
        return c
    }

    private fun delivered(text: String): Contract {
        val c = fundedDeal()
        deliveries.submit(c.id, maya, text)
        poller.drain()
        return contracts.find(c.id)!!
    }

    private fun state(c: Contract) = contracts.find(c.id)!!.state
    private fun hold(c: Contract, side: HoldSide): Hold = holds.forContract(c.id).single { it.side == side }
    private fun events(c: Contract) = ledger.forContract(c.id).map { "${it.event}:${it.amount.minor}:${it.counterparty}" }
    private fun currentSession() = sessions.current()

    /** Makes every retry-scheduled event due now, as if its backoff had passed. */
    private fun retryNow() = jdbc.sql("update outbox_event set next_attempt_at = now() where published_at is null and dead_at is null").update()

    // The two branches, and the no-show ------------------------------------------------------------------

    @Test
    fun `the first FAIL of a session waits for a human, then voids the fee and pays the bond to the buyer`() {
        val c = delivered(DemoTexts.fail)
        assertEquals(ContractState.FAIL, state(c))
        assertTrue(views.of(contracts.find(c.id)!!).awaitingConfirmation, "US-16: the first capture of a session waits")
        assertTrue(fake.captures.isEmpty() && fake.payouts.isEmpty(), "nothing moves before the Confirm")
        assertTrue(holds.forContract(c.id).all { it.state == HoldState.HELD }, "not even the void")

        settlement.confirm(c.id)
        val settling = views.of(contracts.find(c.id)!!)
        assertEquals(setOf("Capturing the seller bond on PayPal", "Releasing the buyer fee on PayPal"), settling.inFlight.map { it.label }.toSet())
        assertNull(settling.outcome, "no who-got-what until PayPal has confirmed both")
        poller.drain()

        assertEquals(ContractState.SETTLED, state(c))
        val settled = views.of(contracts.find(c.id)!!)
        assertEquals("Alex's agent was never charged; Maya's agent's \$0.40 bond was paid to Alex's agent as compensation.", settled.outcome)
        assertEquals(listOf("Released: \$1.50 never charged", "Compensation: \$0.40 paid to Alex's agent"), settled.holds.map { it.caption })
        assertEquals(emptyList<String>(), settled.inFlight.map { it.label })
        assertEquals(HoldState.VOIDED, hold(c, BUYER_FEE).state)
        assertEquals(HoldState.PAID_OUT, hold(c, SELLER_BOND).state)
        assertEquals(listOf(Jar.RELEASED, Jar.COMPENSATION), listOf(jarOf(hold(c, BUYER_FEE)), jarOf(hold(c, SELLER_BOND))))
        assertEquals(
            listOf(
                "HOLD_AUTHORIZED:150:alex", "HOLD_AUTHORIZED:40:maya", "HOLD_VOIDED:150:alex",
                "CAPTURE_COMPLETED:40:maya", "PAYPAL_FEE:40:paypal", "PAYOUT_SUCCEEDED:40:alex", "PAYOUT_FEE:25:paypal",
            ).sorted(),
            events(c).sorted(),
        )
        val payout = fake.payouts.values.single()
        assertEquals(alex.paypalEmail to Money(40), payout.receiver to payout.amount)
        assertTrue(contracts.transitions(c.id).last().cause.contains("bond captured and paid out to alex"))
        assertNotNull(contracts.find(c.id)!!.settlementConfirmedAt)
        assertNotNull(currentSession().firstCaptureConfirmedAt)
    }

    @Test
    fun `once the session's first capture is confirmed, a PASS settles on its own`() {
        sessionRows.confirmFirstCapture(currentSession().id)
        val c = delivered(DemoTexts.pass)
        poller.drain()

        assertEquals(ContractState.SETTLED, state(c))
        assertEquals(HoldState.PAID_OUT, hold(c, BUYER_FEE).state)
        assertEquals(HoldState.VOIDED, hold(c, SELLER_BOND).state)
        assertEquals(listOf(Jar.PAID, Jar.RELEASED), listOf(jarOf(hold(c, BUYER_FEE)), jarOf(hold(c, SELLER_BOND))))
        val settled = views.of(contracts.find(c.id)!!)
        assertEquals("Maya's agent was paid \$1.50; Maya's agent's \$0.40 bond was released, never charged.", settled.outcome)
        assertEquals(listOf("Paid: \$1.50 to Maya's agent", "Released: \$0.40 never charged"), settled.holds.map { it.caption })
        val payout = fake.payouts.values.single()
        assertEquals(maya.paypalEmail to Money(150), payout.receiver to payout.amount)
        assertTrue("PAYPAL_FEE:54:paypal" in events(c), "3.49% + 49 cents on \$1.50")
        assertNull(contracts.find(c.id)!!.settlementConfirmedAt, "no human step after the first")
    }

    @Test
    fun `a no-show settles like a FAIL - the buyer is compensated from the bond`() {
        sessionRows.confirmFirstCapture(currentSession().id)
        val c = fundedDeal()
        clock.advance(Duration.ofHours(2))
        sweeper.sweep()
        poller.drain()

        assertEquals(ContractState.SETTLED, state(c))
        assertEquals(listOf("BOTH_HELD->EXPIRED", "EXPIRED->SETTLED"), contracts.transitions(c.id).takeLast(2).map { "${it.from}->${it.to}" })
        assertEquals(HoldState.VOIDED, hold(c, BUYER_FEE).state)
        assertEquals(HoldState.PAID_OUT, hold(c, SELLER_BOND).state)
        assertEquals(alex.paypalEmail, fake.payouts.values.single().receiver)
        assertTrue(contracts.transitions(c.id).last().cause.contains("no delivery by the deadline"))
    }

    // US-16: the confirm can only release what the verdict decided -----------------------------------------

    @Test
    fun `confirm only releases a verdict's settlement, once`() {
        val funded = fundedDeal()
        assertEquals("not_awaiting_confirmation", assertThrows<ApiException> { settlement.confirm(funded.id) }.code)

        val c = delivered(DemoTexts.fail)
        val first = settlement.confirm(c.id)
        val again = settlement.confirm(c.id)
        assertEquals(first.settlementPlannedAt, again.settlementPlannedAt, "idempotent")
        assertEquals(setOf(OutboxType.CAPTURE), outbox.live(hold(c, SELLER_BOND).id).toSet(), "the bond is captured: that is what FAIL decided")
        assertEquals(setOf(OutboxType.VOID), outbox.live(hold(c, BUYER_FEE).id).toSet())
    }

    // US-17: a retry never pays twice -----------------------------------------------------------------------

    @Test
    fun `a capture whose answer was lost is not captured twice`() {
        sessionRows.confirmFirstCapture(currentSession().id)
        var lose = true
        fake.afterCapture = { if (lose) { lose = false; throw RuntimeException("connection reset after PayPal captured") } }
        val c = delivered(DemoTexts.fail)
        poller.drain()
        assertEquals(HoldState.CAPTURE_REQUESTED, hold(c, SELLER_BOND).state, "the answer never arrived")
        assertEquals(1, fake.captures.size)

        retryNow()
        poller.drain()

        assertEquals(ContractState.SETTLED, state(c))
        assertEquals(1, fake.captures.size, "the retry used the same PayPal-Request-Id and got the original capture")
        assertEquals(1, events(c).count { it.startsWith("CAPTURE_COMPLETED") })
        assertEquals(1, events(c).count { it.startsWith("PAYPAL_FEE") })
    }

    @Test
    fun `a payout whose answer was lost is recovered from PayPal's refusal, not sent again`() {
        sessionRows.confirmFirstCapture(currentSession().id)
        var lose = true
        fake.afterPayout = { if (lose) { lose = false; throw RuntimeException("timeout after PayPal created the batch") } }
        val c = delivered(DemoTexts.fail)
        poller.drain()
        assertNull(hold(c, SELLER_BOND).payoutBatchId)

        retryNow()
        poller.drain()

        assertEquals(ContractState.SETTLED, state(c))
        assertEquals(1, fake.payouts.size, "one batch at PayPal")
        assertEquals(fake.payouts.keys.single(), hold(c, SELLER_BOND).payoutBatchId, "batch id recovered from the 400's link")
        assertEquals(1, events(c).count { it.startsWith("PAYOUT_SUCCEEDED") })
        assertEquals(1, events(c).count { it.startsWith("PAYOUT_FEE") })
    }

    @Test
    fun `a duplicate payout with no link to the batch is flagged, never re-sent under a new id`() {
        sessionRows.confirmFirstCapture(currentSession().id)
        fake.duplicatePayoutLinks = false
        var lose = true
        fake.afterPayout = { if (lose) { lose = false; throw RuntimeException("timeout after PayPal created the batch") } }
        val c = delivered(DemoTexts.fail)
        poller.drain()
        retryNow()
        poller.drain()

        val bond = hold(c, SELLER_BOND)
        assertEquals(HoldState.PAYOUT_REQUESTED, bond.state)
        assertTrue(bond.needsAttention)
        assertTrue(bond.attentionReason!!.startsWith("duplicate_payout"), bond.attentionReason)
        assertEquals(1, fake.payouts.size)
        assertEquals(setOf(bond.senderBatchId), fake.payouts.values.map { it.senderBatchId }.toSet())
        assertEquals(ContractState.FAIL, state(c), "not settled while a payout is unaccounted for")
    }

    // Never ahead of PayPal (US-30, US-32) -------------------------------------------------------------------

    @Test
    fun `a PENDING capture is followed, not flagged, and nothing is paid out until it completes`() {
        sessionRows.confirmFirstCapture(currentSession().id)
        fake.captureStatus = "PENDING"
        val c = delivered(DemoTexts.fail)
        poller.drain()

        val pending = hold(c, SELLER_BOND)
        assertEquals(HoldState.CAPTURE_REQUESTED, pending.state)
        assertNotNull(pending.captureId)
        assertFalse(pending.needsAttention, "still in progress is not a problem")
        assertTrue(fake.payouts.isEmpty(), "a payout is funded from the platform balance: never against a PENDING capture")
        assertEquals(Jar.HELD, jarOf(pending))

        fake.completeCapture(pending.captureId!!)
        retryNow()
        poller.drain()
        assertEquals(HoldState.PAID_OUT, hold(c, SELLER_BOND).state)
        assertEquals(ContractState.SETTLED, state(c))
    }

    @Test
    fun `a payout still processing shows Captured, not Compensation`() {
        sessionRows.confirmFirstCapture(currentSession().id)
        fake.payoutItemStatus = "PENDING"
        val c = delivered(DemoTexts.fail)
        poller.drain()

        assertEquals(HoldState.PAYOUT_REQUESTED, hold(c, SELLER_BOND).state)
        assertEquals(Jar.CAPTURED, jarOf(hold(c, SELLER_BOND)))
        val waiting = views.of(contracts.find(c.id)!!)
        assertEquals("Captured \$0.40; paying out to Alex's agent", waiting.holds.single { it.side == SELLER_BOND }.caption)
        assertTrue(waiting.inFlight.any { it.label == "Waiting for PayPal to deliver the seller bond payout" })
        assertFalse(hold(c, SELLER_BOND).needsAttention)

        fake.payoutItemStatus = "SUCCESS"
        retryNow()
        poller.drain()
        assertEquals(Jar.COMPENSATION, jarOf(hold(c, SELLER_BOND)))
        assertEquals(ContractState.SETTLED, state(c))
    }

    // US-25 at planning time: both legs or neither ------------------------------------------------------------

    @Test
    fun `a PASS that would break the session cap queues neither leg`() {
        sessionRows.confirmFirstCapture(currentSession().id)
        val c = fundedDeal()
        jdbc.sql("update session set cap_minor = 100 where id = :s").param("s", currentSession().id).update()
        deliveries.submit(c.id, maya, DemoTexts.pass)
        poller.drain()

        assertEquals(ContractState.PASS, state(c))
        val after = contracts.find(c.id)!!
        assertTrue(after.needsAttention)
        assertTrue(after.attentionReason!!.startsWith("mandate_exceeded"))
        assertTrue(holds.forContract(c.id).all { it.state == HoldState.HELD }, "no voided bond with an uncaptured fee")
        assertTrue(fake.captures.isEmpty())
    }

    // Webhooks: a prompt to read again, matched by ids already stored ------------------------------------------

    @Test
    fun `capture and payout webhooks prompt a fresh read of the right hold`() {
        val c = delivered(DemoTexts.fail)
        settlement.confirm(c.id)
        quiesceOutbox(jdbc)
        val bond = hold(c, SELLER_BOND)

        prompter.prompt("PAYMENT.CAPTURE.COMPLETED", mapper.readTree("""{"id":"CAP-X","supplementary_data":{"related_ids":{"authorization_id":"${bond.authorizationId}"}}}"""))
        assertEquals(listOf(OutboxType.CAPTURE), outbox.live(bond.id), "no capture id recorded yet, so the capture step itself recovers it")

        quiesceOutbox(jdbc)
        jdbc.sql("update hold set state = 'CAPTURED', capture_id = 'CAP-X', captured_minor = 40 where id = :id").param("id", bond.id).update()
        jdbc.sql("update hold set state = 'PAYOUT_REQUESTED' where id = :id").param("id", bond.id).update()
        prompter.prompt("PAYMENT.PAYOUTS-ITEM.SUCCEEDED", mapper.readTree("""{"payout_batch_id":"UNKNOWN","payout_item":{"sender_item_id":"${bond.senderBatchId}-1"}}"""))
        assertEquals(listOf(OutboxType.PAYOUT), outbox.live(bond.id), "found by sender_batch_id; the batch id comes from the payout step")
    }

    // SETTLED exactly once, even when the last two results land together --------------------------------------

    @Test
    fun `a void and a payout recorded at the same moment settle the deal exactly once`() {
        val c = delivered(DemoTexts.fail)
        settlement.confirm(c.id)
        quiesceOutbox(jdbc)
        val fee = hold(c, BUYER_FEE)
        val bond = hold(c, SELLER_BOND)
        settlement.recordCapture(bond.id, fake.captureAuthorization(bond.authorizationId!!, bond.requestId("capture"), bond.id))
        val batch = fake.createPayout(PayoutRequest(bond.id, bond.requestId("payout"), bond.senderBatchId!!, alex.paypalEmail, Money(40), "s", "m", "n"))
        settlement.recordPayoutCreated(bond.id, batch.batchId)
        val voided = fake.voidAuthorization(fee.authorizationId!!, fee.requestId("void"), fee.id)
        quiesceOutbox(jdbc)

        // Each result waits, inside its transaction, until the other is recorded. Without the contract row lock
        // both would see the other hold as not final, and the deal would stall short of SETTLED.
        val bothRecorded = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        val runs = listOf<() -> Unit>(
            { settlement.recordVoided(fee.id, voided) },
            { settlement.recordPayoutSucceeded(bond.id, fake.getPayoutBatch(batch.batchId, bond.id)) },
        ).map { record ->
            pool.submit {
                tx.executeWithoutResult {
                    record()
                    bothRecorded.countDown()
                    bothRecorded.await(2, TimeUnit.SECONDS)
                }
            }
        }
        runs.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(ContractState.SETTLED, state(c))
        assertEquals(1, contracts.transitions(c.id).count { it.to == ContractState.SETTLED })
    }
}
