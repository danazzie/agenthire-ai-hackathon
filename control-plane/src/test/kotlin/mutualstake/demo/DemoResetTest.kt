package mutualstake.demo

import mutualstake.api.ApiException
import mutualstake.contract.Contract
import mutualstake.contract.ContractRepository
import mutualstake.contract.ContractState
import mutualstake.contract.NegotiationService
import mutualstake.contract.OfferKind.ACCEPT
import mutualstake.contract.OfferKind.PROPOSE
import mutualstake.hold.HoldRepository
import mutualstake.hold.HoldSide
import mutualstake.hold.HoldSide.BUYER_FEE
import mutualstake.hold.HoldSide.SELLER_BOND
import mutualstake.hold.HoldState
import mutualstake.money.Money
import mutualstake.outbox.OutboxPoller
import mutualstake.outbox.OutboxRepository
import mutualstake.outbox.OutboxType
import mutualstake.party.Party
import mutualstake.party.PartyRepository
import mutualstake.session.SessionRepository
import mutualstake.session.SessionService
import mutualstake.support.FakePayPal
import mutualstake.support.IntegrationTest
import mutualstake.support.MutableClock
import mutualstake.support.compiledVerifier
import mutualstake.support.quiesceOutbox
import mutualstake.verifier.Verifier
import mutualstake.verifier.VerifierRepository
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Duration

/** US-31: Reset voids what it can, archives the session, and never reuses a request id. */
@IntegrationTest
class DemoResetTest @Autowired constructor(
    private val demo: DemoReset,
    private val negotiation: NegotiationService,
    private val contracts: ContractRepository,
    private val holds: HoldRepository,
    private val parties: PartyRepository,
    private val verifiers: VerifierRepository,
    private val sessions: SessionService,
    private val sessionRows: SessionRepository,
    private val outbox: OutboxRepository,
    private val poller: OutboxPoller,
    private val fake: FakePayPal,
    private val clock: MutableClock,
    private val jdbc: JdbcClient,
    private val mvc: MockMvc,
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
        verifier = compiledVerifier(verifiers)
    }

    private fun draft(): Contract {
        val c = negotiation.open(alex, "maya", "50-word coffee-shop blurb, max \$2, by 18:00, no lorem ipsum", Money(200))
        negotiation.offer(c.id, alex, PROPOSE, Money(150), Money(40), clock.instant().plus(Duration.ofHours(1)), verifier.id, null)
        return c
    }

    private fun accept(c: Contract, party: Party) =
        negotiation.offer(c.id, party, ACCEPT, Money(150), Money(40), clock.instant().plus(Duration.ofHours(1)), verifier.id, null)

    private fun lockDeal(): Contract = draft().also { accept(it, maya); accept(it, alex); poller.drain() }

    private fun hold(c: Contract, side: HoldSide) = holds.forContract(c.id).single { it.side == side }

    private fun approve(c: Contract, side: HoldSide, payer: Party) {
        val h = hold(c, side)
        fake.approve(h.paypalOrderId!!, payer.paypalEmail)
        outbox.enqueue(h.id, OutboxType.CHECK_ORDER)
        poller.drain()
    }

    @Test
    fun `reset cancels a deal still waiting on its holds, voids what was held, and opens a clean session`() {
        val before = sessions.current()
        val c = lockDeal()
        approve(c, BUYER_FEE, alex)
        assertEquals(HoldState.HELD, hold(c, BUYER_FEE).state)

        val result = demo.reset()

        assertEquals(before.id, result.archivedSessionId)
        assertNotEquals(before.id, result.session.id)
        assertTrue(sessionRows.isArchived(before.id))
        assertEquals(listOf(c.id), result.cancelled)
        assertEquals(ContractState.CANCELLED, contracts.find(c.id)!!.state)
        assertEquals(HoldState.ABANDONED, hold(c, SELLER_BOND).state)
        assertEquals(emptyList<Contract>(), contracts.inSession(result.session.id), "the new session starts with no deals")

        poller.drain()
        assertEquals(HoldState.VOIDED, hold(c, BUYER_FEE).state)
        assertEquals("VOIDED", fake.orders.getValue(hold(c, BUYER_FEE).paypalOrderId!!).authorization!!.status)
    }

    @Test
    fun `reset refuses while a deal has both holds placed, and changes nothing`() {
        val before = sessions.current()
        val c = lockDeal()
        approve(c, BUYER_FEE, alex)
        approve(c, SELLER_BOND, maya)
        assertEquals(ContractState.BOTH_HELD, contracts.find(c.id)!!.state)

        val refused = assertThrows<ApiException> { demo.reset() }
        assertEquals("deal_in_flight", refused.code)
        mvc.post("/api/demo/reset").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("deal_in_flight") }
            jsonPath("$.detail") { value(containsString("${c.id} is BOTH_HELD")) }
        }

        assertEquals(before.id, sessions.current().id)
        assertEquals(ContractState.BOTH_HELD, contracts.find(c.id)!!.state)
        assertTrue(holds.forContract(c.id).all { it.state == HoldState.HELD })
        assertTrue(holds.forContract(c.id).all { outbox.live(it.id).isEmpty() }, "nothing voided behind the verdict's back")
    }

    @Test
    fun `a draft left behind in the archived session can no longer lock its terms`() {
        val c = draft()
        demo.reset()

        accept(c, maya)
        val refused = assertThrows<ApiException> { accept(c, alex) }
        assertEquals("session_archived", refused.code)
        assertEquals(ContractState.DRAFT, contracts.find(c.id)!!.state)
    }

    @Test
    fun `holds opened after a reset never reuse a PayPal request id`() {
        val first = lockDeal()
        demo.reset()
        val second = lockDeal()

        val bases = (holds.forContract(first.id) + holds.forContract(second.id)).map { it.requestIdBase }
        assertEquals(4, bases.toSet().size)
        assertEquals(4, fake.orders.size, "the second deal got new orders, not the first deal's")
        assertEquals(fake.requestIds.size, fake.requestIds.toSet().size, "no PayPal-Request-Id was sent twice")
    }
}
