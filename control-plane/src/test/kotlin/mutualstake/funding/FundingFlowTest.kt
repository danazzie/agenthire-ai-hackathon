package mutualstake.funding

import mutualstake.api.ApiException
import mutualstake.api.Jar
import mutualstake.api.jarOf
import mutualstake.contract.Contract
import mutualstake.contract.ContractRepository
import mutualstake.contract.ContractState
import mutualstake.contract.NegotiationService
import mutualstake.contract.OfferKind.ACCEPT
import mutualstake.contract.OfferKind.PROPOSE
import mutualstake.contract.OfferRepository
import mutualstake.hold.HoldRepository
import mutualstake.hold.HoldSide
import mutualstake.hold.HoldSide.BUYER_FEE
import mutualstake.hold.HoldSide.SELLER_BOND
import mutualstake.hold.HoldState
import mutualstake.ledger.LedgerEvent.HOLD_AUTHORIZED
import mutualstake.ledger.LedgerEvent.HOLD_VOIDED
import mutualstake.ledger.LedgerRepository
import mutualstake.money.Money
import mutualstake.outbox.OutboxPoller
import mutualstake.outbox.OutboxRepository
import mutualstake.outbox.OutboxType
import mutualstake.party.Party
import mutualstake.party.PartyRepository
import mutualstake.session.SessionService
import mutualstake.support.FakePayPal
import mutualstake.support.IntegrationTest
import mutualstake.support.MutableClock
import mutualstake.support.quiesceOutbox
import mutualstake.verifier.Verifier
import mutualstake.verifier.VerifierRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The two-order funding flow end to end, against a fake PayPal that behaves like the sandbox did. */
@IntegrationTest
class FundingFlowTest @Autowired constructor(
    private val negotiation: NegotiationService,
    private val funding: FundingService,
    private val contracts: ContractRepository,
    private val offers: OfferRepository,
    private val holds: HoldRepository,
    private val parties: PartyRepository,
    private val verifiers: VerifierRepository,
    private val sessions: SessionService,
    private val ledger: LedgerRepository,
    private val outbox: OutboxRepository,
    private val poller: OutboxPoller,
    private val sweeper: Sweeper,
    private val fake: FakePayPal,
    private val clock: MutableClock,
    private val jdbc: JdbcClient,
    private val tx: TransactionTemplate,
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
        verifier = verifiers.save("export function verify(text) { return { pass: true, checks: [] }; } // ${UUID.randomUUID()}")
    }

    private fun deadline(): Instant = clock.instant().plus(Duration.ofHours(1))

    /** Alex proposes, Maya accepts, Alex accepts: the PROTOTYPES negotiation, compressed. */
    private fun lockDeal(fee: Long = 150, bond: Long = 40, deadline: Instant = deadline()): Contract {
        val c = negotiation.open(alex, "maya", "50-word coffee-shop blurb, max \$2, by 18:00, no lorem ipsum", Money(200))
        negotiation.offer(c.id, alex, PROPOSE, Money(fee), Money(bond), deadline, verifier.id, "fee and bond as agreed")
        negotiation.offer(c.id, maya, ACCEPT, Money(fee), Money(bond), deadline, verifier.id, null)
        return negotiation.offer(c.id, alex, ACCEPT, Money(fee), Money(bond), deadline, verifier.id, null)
    }

    private fun hold(c: Contract, side: HoldSide) = holds.forContract(c.id).single { it.side == side }

    /** The fake PayPal's order behind this deal's hold, looked up by the hold's own order id. */
    private fun paypalOrder(c: Contract, side: HoldSide) = fake.orders.getValue(hold(c, side).paypalOrderId!!)

    private fun state(c: Contract) = contracts.find(c.id)!!.state

    /** The payer clicks approve in sandbox checkout; the return page then nudges a check. */
    private fun approve(c: Contract, side: HoldSide, payer: Party) {
        val h = hold(c, side)
        fake.approve(h.paypalOrderId!!, payer.paypalEmail)
        outbox.enqueue(h.id, OutboxType.CHECK_ORDER)
        poller.drain()
    }

    @Test
    fun `identical ACCEPTs lock the deal and both orders carry the verifier hash before any hold exists`() {
        val c = lockDeal()
        assertEquals(ContractState.TERMS_LOCKED, c.state)
        assertTrue(holds.forContract(c.id).all { it.state == HoldState.PENDING && it.customId == verifier.sha256 })

        poller.drain()

        val fee = paypalOrder(c, BUYER_FEE)
        val bond = paypalOrder(c, SELLER_BOND)
        assertEquals(verifier.sha256, fee.customId)
        assertEquals(verifier.sha256, bond.customId)
        assertEquals(Money(150), fee.amount)
        assertEquals(Money(40), bond.amount)
        assertNull(fee.authorization)
        assertNull(bond.authorization)
        assertTrue(holds.forContract(c.id).all { it.state == HoldState.ORDER_CREATED && it.approveUrl != null })
    }

    @Test
    fun `the deal is BOTH_HELD only once PayPal has authorized both holds`() {
        val c = lockDeal()
        poller.drain()

        approve(c, BUYER_FEE, alex)
        assertEquals(HoldState.HELD, hold(c, BUYER_FEE).state)
        assertEquals(ContractState.TERMS_LOCKED, state(c))

        approve(c, SELLER_BOND, maya)
        assertEquals(ContractState.BOTH_HELD, state(c))
        assertEquals(listOf("DRAFT->TERMS_LOCKED", "TERMS_LOCKED->BOTH_HELD"), contracts.transitions(c.id).map { "${it.from}->${it.to}" })
        assertEquals(listOf(Jar.HELD, Jar.HELD), holds.forContract(c.id).map(::jarOf))

        val entries = ledger.forContract(c.id)
        assertEquals(listOf(HOLD_AUTHORIZED, HOLD_AUTHORIZED), entries.map { it.event })
        assertEquals(setOf(Money(150) to "alex", Money(40) to "maya"), entries.map { it.amount to it.counterparty }.toSet())
        // US-17: every call used the hold's own deterministic request id.
        for (h in holds.forContract(c.id)) {
            assertTrue(fake.requestIds.containsAll(listOf(h.requestId("create"), h.requestId("authorize"))), "${h.side} request ids")
        }
    }

    @Test
    fun `different terms never lock`() {
        val c = negotiation.open(alex, "maya", "job", Money(200))
        val d = deadline()
        negotiation.offer(c.id, alex, ACCEPT, Money(150), Money(40), d, verifier.id, null)
        val after = negotiation.offer(c.id, maya, ACCEPT, Money(150), Money(30), d, verifier.id, null)
        assertEquals(ContractState.DRAFT, after.state)
        assertTrue(holds.forContract(c.id).isEmpty())
    }

    @Test
    fun `a PROPOSE is not an ACCEPT`() {
        val c = negotiation.open(alex, "maya", "job", Money(200))
        val d = deadline()
        negotiation.offer(c.id, maya, ACCEPT, Money(150), Money(40), d, verifier.id, null)
        assertEquals(ContractState.DRAFT, negotiation.offer(c.id, alex, PROPOSE, Money(150), Money(40), d, verifier.id, null).state)
    }

    @Test
    fun `deadlines that differ below a second are the same deadline`() {
        val c = negotiation.open(alex, "maya", "job", Money(200))
        val d = deadline().truncatedTo(ChronoUnit.SECONDS)
        negotiation.offer(c.id, alex, ACCEPT, Money(150), Money(40), d.plusMillis(123), verifier.id, null)
        val locked = negotiation.offer(c.id, maya, ACCEPT, Money(150), Money(40), d.plusMillis(456), verifier.id, null)
        assertEquals(ContractState.TERMS_LOCKED, locked.state)
        assertEquals(d, locked.deadlineAt)
    }

    @Test
    fun `a bond approved from the buyer's account is no stake, so it is never authorized`() {
        val c = lockDeal()
        poller.drain()
        approve(c, SELLER_BOND, alex)

        val bond = hold(c, SELLER_BOND)
        assertEquals(HoldState.ORDER_CREATED, bond.state)
        assertTrue(bond.needsAttention)
        assertTrue(bond.attentionReason!!.startsWith("wrong_payer"), bond.attentionReason)
        assertNull(paypalOrder(c, SELLER_BOND).authorization)
        assertEquals(ContractState.TERMS_LOCKED, state(c))
    }

    @Test
    fun `cancel voids what is held and abandons what never was`() {
        val c = lockDeal()
        poller.drain()
        approve(c, BUYER_FEE, alex)

        funding.cancel(c.id, "cancelled in test")
        assertEquals(HoldState.VOID_REQUESTED, hold(c, BUYER_FEE).state)
        assertEquals(Jar.HELD, jarOf(hold(c, BUYER_FEE)), "still Held until PayPal confirms the void (US-32)")
        assertEquals(HoldState.ABANDONED, hold(c, SELLER_BOND).state)

        poller.drain()
        assertEquals(HoldState.VOIDED, hold(c, BUYER_FEE).state)
        assertEquals(Jar.RELEASED, jarOf(hold(c, BUYER_FEE)))
        assertEquals("VOIDED", paypalOrder(c, BUYER_FEE).authorization!!.status)
        assertEquals(listOf(HOLD_AUTHORIZED, HOLD_VOIDED), ledger.forContract(c.id).map { it.event })
        assertEquals(ContractState.CANCELLED, state(c))
    }

    @Test
    fun `an authorization that lands after a cancel is voided, not kept`() {
        val c = lockDeal()
        poller.drain()
        val feeOrder = hold(c, BUYER_FEE).paypalOrderId!!
        fake.approve(feeOrder, alex.paypalEmail)
        fake.approve(hold(c, SELLER_BOND).paypalOrderId!!, maya.paypalEmail)
        fake.afterAuthorize = { orderId -> if (orderId == feeOrder) funding.cancel(c.id, "cancelled while PayPal was authorizing") }

        holds.forContract(c.id).forEach { outbox.enqueue(it.id, OutboxType.CHECK_ORDER) }
        poller.drain()

        assertEquals(ContractState.CANCELLED, state(c))
        assertEquals(HoldState.VOIDED, hold(c, BUYER_FEE).state)
        assertEquals("VOIDED", paypalOrder(c, BUYER_FEE).authorization!!.status)
        assertEquals(HoldState.ABANDONED, hold(c, SELLER_BOND).state)
        assertNull(paypalOrder(c, SELLER_BOND).authorization, "the deal had moved on, so the bond was never placed")
    }

    @Test
    fun `two authorizations recorded at the same moment move the deal to BOTH_HELD exactly once`() {
        val c = lockDeal()
        poller.drain()
        val authorizations = holds.forContract(c.id).associate { h ->
            fake.approve(h.paypalOrderId!!, if (h.side == BUYER_FEE) alex.paypalEmail else maya.paypalEmail)
            funding.recordOrderState(h.id, fake.getOrder(h.paypalOrderId!!, h.id))
            h.id to fake.authorizeOrder(h.paypalOrderId!!, h.requestId("authorize"), h.id).authorization!!
        }
        assertTrue(holds.forContract(c.id).all { it.state == HoldState.APPROVED })

        // Each recording waits, inside its own transaction, until the other has recorded too. Without the
        // contract row lock both would read the other hold as not yet HELD, commit, and the deal would stall
        // in TERMS_LOCKED. With it, the second recording blocks until the first commits, then promotes.
        val bothRecorded = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        val runs = authorizations.map { (holdId, authorization) ->
            pool.submit {
                tx.executeWithoutResult {
                    funding.recordAuthorization(holdId, authorization)
                    bothRecorded.countDown()
                    bothRecorded.await(2, TimeUnit.SECONDS)
                }
            }
        }
        runs.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(ContractState.BOTH_HELD, state(c))
        assertEquals(1, contracts.transitions(c.id).count { it.to == ContractState.BOTH_HELD })
    }

    @Test
    fun `an order PayPal already authorized is recovered, not authorized twice`() {
        val c = lockDeal()
        poller.drain()
        val fee = hold(c, BUYER_FEE)
        fake.approve(fee.paypalOrderId!!, alex.paypalEmail)
        funding.recordOrderState(fee.id, fake.getOrder(fee.paypalOrderId!!, fee.id))
        // An earlier attempt placed the hold, but its answer never reached us.
        fake.authorizeOrder(fee.paypalOrderId!!, "attempt-whose-answer-was-lost", fee.id)

        poller.drain()

        val held = hold(c, BUYER_FEE)
        assertEquals(HoldState.HELD, held.state)
        assertEquals(paypalOrder(c, BUYER_FEE).authorization!!.id, held.authorizationId)
        assertEquals(1, ledger.forContract(c.id).count { it.event == HOLD_AUTHORIZED })
    }

    @Test
    fun `a step is queued once, however many signals ask for it`() {
        val c = lockDeal()
        poller.drain()
        val fee = hold(c, BUYER_FEE)
        assertTrue(outbox.enqueue(fee.id, OutboxType.CHECK_ORDER))
        assertFalse(outbox.enqueue(fee.id, OutboxType.CHECK_ORDER))
        assertFalse(outbox.enqueue(fee.id, OutboxType.CHECK_ORDER))
        assertEquals(listOf(OutboxType.CHECK_ORDER), outbox.live(fee.id))
    }

    @Test
    fun `a lock past the session cap is refused and not recorded (US-25)`() {
        sessions.startNew(Money(200), 2)
        lockDeal(fee = 150)
        val second = negotiation.open(alex, "maya", "another job", Money(200))
        val d = deadline()
        negotiation.offer(second.id, maya, ACCEPT, Money(150), Money(40), d, verifier.id, null)

        val refused = assertThrows<ApiException> { negotiation.offer(second.id, alex, ACCEPT, Money(150), Money(40), d, verifier.id, null) }

        assertEquals("mandate_exceeded", refused.code)
        assertEquals(ContractState.DRAFT, contracts.find(second.id)!!.state)
        assertEquals(1, offers.forContract(second.id).size)
    }

    @Test
    fun `a session allows only so many jobs (US-25)`() {
        sessions.startNew(Money(3000), 1)
        lockDeal()
        val second = negotiation.open(alex, "maya", "another job", Money(200))
        val d = deadline()
        negotiation.offer(second.id, maya, ACCEPT, Money(150), Money(40), d, verifier.id, null)
        assertEquals("mandate_exceeded", assertThrows<ApiException> { negotiation.offer(second.id, alex, ACCEPT, Money(150), Money(40), d, verifier.id, null) }.code)
    }

    @Test
    fun `without credentials each hold says no_credentials and nothing is faked (US-32)`() {
        fake.configured = false
        val c = lockDeal()
        poller.drain()

        for (h in holds.forContract(c.id)) {
            assertEquals(HoldState.PENDING, h.state)
            assertTrue(h.needsAttention)
            assertEquals("no_credentials", h.attentionReason)
            assertEquals(emptyList<OutboxType>(), outbox.live(h.id), "a dead letter, not an endless retry")
        }
        assertTrue(fake.orders.isEmpty())
    }

    @Test
    fun `a deal whose holds are not both placed by the deadline is cancelled`() {
        val c = lockDeal()
        poller.drain()
        approve(c, BUYER_FEE, alex)

        clock.advance(Duration.ofHours(2))
        sweeper.sweep()
        poller.drain()

        assertEquals(ContractState.CANCELLED, state(c))
        assertEquals(HoldState.VOIDED, hold(c, BUYER_FEE).state)
        assertEquals(HoldState.ABANDONED, hold(c, SELLER_BOND).state)
    }

    @Test
    fun `a held deal with nothing delivered by the deadline expires`() {
        val c = lockDeal()
        poller.drain()
        approve(c, BUYER_FEE, alex)
        approve(c, SELLER_BOND, maya)

        clock.advance(Duration.ofHours(2))
        sweeper.sweep()

        assertEquals(ContractState.EXPIRED, state(c))
    }

    @Test
    fun `with no webhook and no nudge, the sweep still finds the approval by asking PayPal (US-30)`() {
        val c = lockDeal()
        poller.drain()
        fake.approve(hold(c, BUYER_FEE).paypalOrderId!!, alex.paypalEmail)

        sweeper.sweep()
        poller.drain()

        assertEquals(HoldState.HELD, hold(c, BUYER_FEE).state)
    }
}
