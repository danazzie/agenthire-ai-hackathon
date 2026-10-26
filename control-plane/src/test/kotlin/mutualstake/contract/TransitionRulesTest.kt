package mutualstake.contract

import mutualstake.db.APPEND_ONLY
import mutualstake.db.Fixtures
import mutualstake.db.assertRejected
import mutualstake.hold.HoldState
import mutualstake.money.Money
import mutualstake.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** "Reject any transition not on this list, loudly": in code first, and in the database whatever the path. */
@IntegrationTest
class TransitionRulesTest @Autowired constructor(
    private val jdbc: JdbcClient,
    private val contracts: ContractRepository,
    private val offers: OfferRepository,
) {
    private val fixtures = Fixtures(jdbc)

    private fun rules(table: String): Set<String> =
        jdbc.sql("select from_state || '->' || to_state from $table").query(String::class.java).list().toSet()

    /** The build prompt's list, verbatim. */
    private val prompt = setOf(
        "DRAFT->TERMS_LOCKED", "TERMS_LOCKED->BOTH_HELD", "BOTH_HELD->DELIVERED", "DELIVERED->PASS", "PASS->SETTLED",
        "DELIVERED->FAIL", "FAIL->SETTLED", "PASS->APPEALED", "FAIL->APPEALED", "APPEALED->SETTLED", "BOTH_HELD->EXPIRED",
    )

    /** The two additions agreed on 2026-10-20: a no-show settles, and an unfunded deal can be cancelled. */
    private val agreed = setOf("EXPIRED->SETTLED", "TERMS_LOCKED->CANCELLED")

    @Test
    fun `the code's contract transitions are exactly the prompt's list plus the two agreed additions`() {
        val code = ContractState.TRANSITIONS.flatMap { (from, to) -> to.map { "$from->$it" } }.toSet()
        assertEquals(prompt + agreed, code)
    }

    @Test
    fun `the database holds the same contract transitions as the code`() {
        assertEquals(prompt + agreed, rules("contract_transition_rule"))
    }

    @Test
    fun `code and database agree on every hold transition`() {
        val code = HoldState.TRANSITIONS.flatMap { (from, to) -> to.map { "$from->$it" } }.toSet()
        assertEquals(code, rules("hold_transition_rule"))
    }

    @Test
    fun `the code refuses an off-list contract transition before touching the row`() {
        val c = fixtures.lockedContract()
        val e = assertThrows<IllegalTransitionException> { contracts.transition(c.contractId, ContractState.TERMS_LOCKED, ContractState.SETTLED, "skip ahead") }
        assertEquals("illegal contract transition TERMS_LOCKED -> SETTLED for contract ${c.contractId}", e.message)
    }

    @Test
    fun `the database refuses it too, whatever writes the row`() {
        val c = fixtures.lockedContract()
        assertRejected("MS003", "illegal contract transition TERMS_LOCKED -> SETTLED") {
            jdbc.sql("update contract set state = 'SETTLED' where id = :id").param("id", c.contractId).update()
        }
    }

    @Test
    fun `a released hold can never be captured`() {
        val c = fixtures.lockedContract()
        val hold = fixtures.hold(c, "SELLER_BOND", mapOf("state" to "VOIDED", "paypal_order_id" to "O-V", "authorization_id" to "A-V"))
        assertRejected("MS003", "illegal hold transition VOIDED -> CAPTURE_REQUESTED") {
            jdbc.sql("update hold set state = 'CAPTURE_REQUESTED' where id = :id").param("id", hold).update()
        }
    }

    @Test
    fun `locked terms cannot change, and a deal never changes session`() {
        val c = fixtures.lockedContract()
        assertRejected("MS004", "contract terms are locked") {
            jdbc.sql("update contract set fee_minor = 140 where id = :id").param("id", c.contractId).update()
        }
        assertRejected("MS004") {
            jdbc.sql("update contract set session_id = :s where id = :id").param("s", fixtures.archivedSession()).param("id", c.contractId).update()
        }
    }

    @Test
    fun `negotiation and transition logs are append-only`() {
        val c = fixtures.lockedContract()
        val (verifierId, _) = fixtures.verifier()
        val offer = offers.insert(c.contractId, c.buyerId, OfferKind.PROPOSE, Terms(Money(150), Money(40), Instant.now().truncatedTo(ChronoUnit.SECONDS), verifierId), null)
        assertRejected(APPEND_ONLY, "offer is append-only: UPDATE rejected") {
            jdbc.sql("update offer set note = 'rewritten' where id = :id").param("id", offer.id).update()
        }
        jdbc.sql("insert into contract_transition (id, contract_id, from_state, to_state, cause) values (:id, :c, 'DRAFT', 'TERMS_LOCKED', 'test')")
            .param("id", UUID.randomUUID()).param("c", c.contractId).update()
        assertRejected(APPEND_ONLY, "contract_transition is append-only: DELETE rejected") {
            jdbc.sql("delete from contract_transition where contract_id = :c").param("c", c.contractId).update()
        }
    }

    @Test
    fun `the rules themselves cannot be edited`() {
        assertRejected(APPEND_ONLY, "contract_transition_rule is append-only: DELETE rejected") {
            jdbc.sql("delete from contract_transition_rule where from_state = 'DRAFT'").update()
        }
        assertRejected(APPEND_ONLY, "hold_transition_rule is append-only: UPDATE rejected") {
            jdbc.sql("update hold_transition_rule set to_state = 'CAPTURE_REQUESTED' where from_state = 'VOIDED' or from_state = 'VOID_REQUESTED'").update()
        }
    }
}
