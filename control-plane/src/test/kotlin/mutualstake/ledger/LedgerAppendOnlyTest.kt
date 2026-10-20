package mutualstake.ledger

import mutualstake.db.APPEND_ONLY
import mutualstake.db.Fixtures
import mutualstake.db.TestPostgres
import mutualstake.db.assertRejected
import mutualstake.money.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant
import java.util.UUID

/**
 * The ledger is append-only, and the database itself is what enforces it. Each refused
 * statement below is otherwise valid (a no-op UPDATE, a DELETE that matches a row), so the
 * trigger's own SQLSTATE is the only way these tests can pass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestPostgres::class)
class LedgerAppendOnlyTest @Autowired constructor(
    private val jdbc: JdbcClient,
    private val ledger: LedgerRepository,
) {
    private lateinit var contractId: UUID

    @BeforeEach
    fun freshContract() {
        contractId = Fixtures(jdbc).lockedContract().contractId
    }

    private fun capture(ref: String = "CAP-${UUID.randomUUID()}") = LedgerEntry(
        id = UUID.randomUUID(),
        contractId = contractId,
        holdId = null,
        event = LedgerEvent.CAPTURE_COMPLETED,
        amount = Money(40),
        counterparty = "seller",
        paypalRef = ref,
        occurredAt = Instant.parse("2026-10-20T20:39:52Z"),
    )

    private fun amountOf(id: UUID): Long =
        jdbc.sql("select amount_minor from ledger_entry where id = :id").param("id", id).query(Long::class.java).single()

    @Test
    fun `insert is allowed and reads back unchanged`() {
        val entry = capture()
        assertTrue(ledger.record(entry))
        assertEquals(listOf(entry), ledger.forContract(contractId))
    }

    @Test
    fun `update is rejected, even one that changes nothing`() {
        val entry = capture().also { ledger.record(it) }
        assertRejected(APPEND_ONLY, "ledger_entry is append-only: UPDATE rejected") {
            jdbc.sql("update ledger_entry set amount_minor = amount_minor where id = :id").param("id", entry.id).update()
        }
        assertRejected(APPEND_ONLY, "UPDATE rejected") {
            jdbc.sql("update ledger_entry set amount_minor = 0 where id = :id").param("id", entry.id).update()
        }
        assertEquals(40, amountOf(entry.id))
    }

    @Test
    fun `delete of an existing row is rejected`() {
        val entry = capture().also { ledger.record(it) }
        assertRejected(APPEND_ONLY, "ledger_entry is append-only: DELETE rejected") {
            jdbc.sql("delete from ledger_entry where id = :id").param("id", entry.id).update()
        }
        assertEquals(40, amountOf(entry.id))
    }

    @Test
    fun `truncate is rejected`() {
        val entry = capture().also { ledger.record(it) }
        assertRejected(APPEND_ONLY, "ledger_entry is append-only: TRUNCATE rejected") {
            jdbc.sql("truncate ledger_entry").update()
        }
        assertEquals(40, amountOf(entry.id))
    }

    @Test
    fun `an upsert with DO UPDATE is an update, so it is rejected too`() {
        val entry = capture().also { ledger.record(it) }
        assertRejected(APPEND_ONLY, "UPDATE rejected") {
            jdbc.sql(
                """
                insert into ledger_entry (id, contract_id, event_type, direction, amount_minor, counterparty, paypal_ref, occurred_at)
                values (:id, :contractId, 'CAPTURE_COMPLETED', 'IN', 99, 'seller', :ref, now())
                on conflict (event_type, paypal_ref) do update set amount_minor = excluded.amount_minor
                """,
            ).param("id", UUID.randomUUID()).param("contractId", contractId).param("ref", entry.paypalRef).update()
        }
        assertEquals(40, amountOf(entry.id))
    }

    @Test
    fun `recording the same PayPal confirmation twice keeps one row and raises nothing`() {
        val first = capture("CAP-DUPLICATE-${UUID.randomUUID()}")
        assertTrue(ledger.record(first))
        assertFalse(ledger.record(first.copy(id = UUID.randomUUID(), amount = Money(99))))
        assertEquals(listOf(first), ledger.forContract(contractId))
    }

    @Test
    fun `the append-only triggers are installed and enabled`() {
        val triggers = jdbc.sql(
            "select tgname from pg_trigger where tgrelid = 'ledger_entry'::regclass and not tgisinternal and tgenabled = 'O' order by tgname",
        ).query(String::class.java).list()
        assertEquals(listOf("ledger_entry_append_only", "ledger_entry_no_truncate"), triggers)
    }
}
