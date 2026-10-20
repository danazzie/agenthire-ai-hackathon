package mutualstake.db

import mutualstake.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID

/** Rules the database enforces on its own, whatever code path writes the row. */
@IntegrationTest
class SchemaInvariantsTest @Autowired constructor(private val jdbc: JdbcClient) {

    private val fixtures = Fixtures(jdbc)

    @Test
    fun `flyway applied every migration`() {
        val applied = jdbc.sql("select version from flyway_schema_history where success order by installed_rank")
            .query(String::class.java).list()
        assertEquals(listOf("1", "2"), applied)
    }

    // Verifier (US-18) ------------------------------------------------------------------

    @Test
    fun `a verifier's hash must be the SHA-256 of its own source`() {
        assertRejected(CHECK_VIOLATION, "verifier_sha256_matches_source") {
            jdbc.sql("insert into verifier (id, source, language, sha256) values (:id, 'return true', 'javascript', :sha)")
                .param("id", UUID.randomUUID()).param("sha", sha256Hex("a different source")).update()
        }
    }

    @Test
    fun `a verifier cannot be edited or deleted once written`() {
        val (id, _) = fixtures.verifier()
        assertRejected(APPEND_ONLY, "verifier is append-only: UPDATE rejected") {
            jdbc.sql("update verifier set compiled_at = compiled_at where id = :id").param("id", id).update()
        }
        assertRejected(APPEND_ONLY, "verifier is append-only: DELETE rejected") {
            jdbc.sql("delete from verifier where id = :id").param("id", id).update()
        }
    }

    // Contract ----------------------------------------------------------------------------

    @Test
    fun `a bond above half the fee is rejected (US-7)`() {
        assertRejected(CHECK_VIOLATION, "contract_bond_at_most_half_fee") { fixtures.lockedContract(feeMinor = 150, bondMinor = 76) }
        fixtures.lockedContract(feeMinor = 150, bondMinor = 75)
    }

    @Test
    fun `buyer and seller must be different parties`() {
        val party = fixtures.party("both")
        assertRejected(CHECK_VIOLATION, "contract_parties_differ") {
            jdbc.sql("insert into contract (id, session_id, buyer_id, seller_id, seller_type, job_text) values (:id, :s, :p, :p, 'AGENT', 'job')")
                .param("id", UUID.randomUUID()).param("s", fixtures.archivedSession()).param("p", party).update()
        }
    }

    @Test
    fun `terms are required once a contract leaves DRAFT`() {
        val session = fixtures.archivedSession()
        val buyer = fixtures.party("buyer")
        val seller = fixtures.party("seller")
        assertRejected(CHECK_VIOLATION, "contract_terms_set_after_draft") {
            jdbc.sql(
                """
                insert into contract (id, session_id, buyer_id, seller_id, seller_type, job_text, state)
                values (:id, :session, :buyer, :seller, 'AGENT', 'job', 'TERMS_LOCKED')
                """,
            ).param("id", UUID.randomUUID()).param("session", session).param("buyer", buyer).param("seller", seller).update()
        }
    }

    @Test
    fun `contracts and holds are never deleted`() {
        val c = fixtures.lockedContract()
        val hold = fixtures.hold(c, "SELLER_BOND")
        assertRejected(APPEND_ONLY, "hold rows are never deleted") {
            jdbc.sql("delete from hold where id = :id").param("id", hold).update()
        }
        assertRejected(APPEND_ONLY, "contract rows are never deleted") {
            jdbc.sql("delete from contract where id = :id").param("id", c.contractId).update()
        }
    }

    @Test
    fun `no foreign key cascades or nulls on delete`() {
        val actions = jdbc.sql(
            "select conrelid::regclass::text || '.' || conname || '=' || confdeltype::text from pg_constraint where contype = 'f' order by 1",
        ).query(String::class.java).list()
        // a = NO ACTION; c (cascade), n (set null) and d (set default) would let history vanish quietly.
        assertEquals(emptyList<String>(), actions.filterNot { it.endsWith("=a") })
        assertTrue(actions.isNotEmpty())
    }

    // Hold matches its contract (US-18, US-2, US-7) ------------------------------------------

    @Test
    fun `a hold's custom_id must be the contract's verifier hash`() {
        val c = fixtures.lockedContract()
        assertRejected(HOLD_MISMATCH, "custom_id") {
            fixtures.hold(c, "BUYER_FEE", mapOf("custom_id" to sha256Hex("some other verifier")))
        }
    }

    @Test
    fun `the fee hold is the buyer's, for the agreed fee`() {
        val c = fixtures.lockedContract()
        assertRejected(HOLD_MISMATCH, "buyer fee") { fixtures.hold(c, "BUYER_FEE", mapOf("payer_id" to c.sellerId)) }
        assertRejected(HOLD_MISMATCH, "buyer fee") { fixtures.hold(c, "BUYER_FEE", mapOf("amount_minor" to 149L)) }
        fixtures.hold(c, "BUYER_FEE")
    }

    @Test
    fun `the bond hold is the seller's, for the agreed bond`() {
        val c = fixtures.lockedContract()
        assertRejected(HOLD_MISMATCH, "seller bond") { fixtures.hold(c, "SELLER_BOND", mapOf("payer_id" to c.buyerId)) }
        assertRejected(HOLD_MISMATCH, "seller bond") { fixtures.hold(c, "SELLER_BOND", mapOf("amount_minor" to 41L)) }
        fixtures.hold(c, "SELLER_BOND")
    }

    @Test
    fun `holds cannot exist before terms are locked`() {
        val buyer = fixtures.party("buyer")
        val seller = fixtures.party("seller")
        val draft = UUID.randomUUID()
        jdbc.sql("insert into contract (id, session_id, buyer_id, seller_id, seller_type, job_text) values (:id, :session, :b, :s, 'AGENT', 'job')")
            .param("id", draft).param("session", fixtures.archivedSession()).param("b", buyer).param("s", seller).update()
        val c = Fixtures.Locked(draft, buyer, seller, sha256Hex("x"), 150, 40)
        assertRejected(HOLD_MISMATCH, "custom_id") { fixtures.hold(c, "BUYER_FEE") }
    }

    @Test
    fun `one hold per side per contract`() {
        val c = fixtures.lockedContract()
        fixtures.hold(c, "BUYER_FEE")
        assertRejected("23505") { fixtures.hold(c, "BUYER_FEE") }
    }

    // Hold state never claims more than PayPal confirmed (US-30, US-32) ---------------------

    @Test
    fun `HELD needs an authorization id`() {
        val c = fixtures.lockedContract()
        assertRejected(CHECK_VIOLATION, "hold_held_has_authorization") {
            fixtures.hold(c, "SELLER_BOND", mapOf("state" to "HELD", "paypal_order_id" to "O-1"))
        }
        fixtures.hold(c, "SELLER_BOND", mapOf("state" to "HELD", "paypal_order_id" to "O-2", "authorization_id" to "A-2"))
    }

    @Test
    fun `CAPTURED needs a capture id and the amount PayPal reported`() {
        val c = fixtures.lockedContract()
        val held = mapOf("paypal_order_id" to "O-3", "authorization_id" to "A-3")
        assertRejected(CHECK_VIOLATION, "hold_captured_has_capture") { fixtures.hold(c, "SELLER_BOND", held + ("state" to "CAPTURED")) }
        assertRejected(CHECK_VIOLATION, "hold_captured_has_capture") {
            fixtures.hold(c, "SELLER_BOND", held + mapOf("state" to "CAPTURED", "capture_id" to "C-3"))
        }
        assertRejected(CHECK_VIOLATION, "hold_capture_within_amount") {
            fixtures.hold(c, "SELLER_BOND", held + mapOf("state" to "CAPTURED", "capture_id" to "C-3", "captured_minor" to 41L))
        }
        fixtures.hold(c, "SELLER_BOND", held + mapOf("state" to "CAPTURED", "capture_id" to "C-3", "captured_minor" to 40L))
    }

    @Test
    fun `PAID_OUT needs the payout batch and item, and never pays the payer back`() {
        val c = fixtures.lockedContract()
        val captured = mapOf(
            "paypal_order_id" to "O-4", "authorization_id" to "A-4", "capture_id" to "C-4", "captured_minor" to 40L,
            "sender_batch_id" to "SB-4", "payout_recipient_id" to c.buyerId,
        )
        assertRejected(CHECK_VIOLATION, "hold_paid_out_has_payout_ids") { fixtures.hold(c, "SELLER_BOND", captured + ("state" to "PAID_OUT")) }
        assertRejected(CHECK_VIOLATION, "hold_payout_not_to_payer") {
            fixtures.hold(
                c, "SELLER_BOND",
                captured + mapOf("state" to "PAID_OUT", "payout_batch_id" to "B-4", "payout_item_id" to "I-4", "payout_recipient_id" to c.sellerId),
            )
        }
        fixtures.hold(c, "SELLER_BOND", captured + mapOf("state" to "PAID_OUT", "payout_batch_id" to "B-4", "payout_item_id" to "I-4"))
    }

    @Test
    fun `needs_attention always carries a reason`() {
        val c = fixtures.lockedContract()
        assertRejected(CHECK_VIOLATION, "hold_attention_has_reason") { fixtures.hold(c, "BUYER_FEE", mapOf("needs_attention" to true)) }
        fixtures.hold(c, "BUYER_FEE", mapOf("needs_attention" to true, "attention_reason" to "payout batch id unknown"))
    }

    // Ledger ------------------------------------------------------------------------------

    @Test
    fun `a ledger event's direction is fixed by its type`() {
        val c = fixtures.lockedContract()
        assertRejected(CHECK_VIOLATION, "ledger_entry_direction_matches_event") {
            jdbc.sql(
                """
                insert into ledger_entry (id, contract_id, event_type, direction, amount_minor, counterparty, paypal_ref, occurred_at)
                values (:id, :contractId, 'HOLD_AUTHORIZED', 'IN', 150, 'buyer', 'AUTH-1', now())
                """,
            ).param("id", UUID.randomUUID()).param("contractId", c.contractId).update()
        }
    }
}
