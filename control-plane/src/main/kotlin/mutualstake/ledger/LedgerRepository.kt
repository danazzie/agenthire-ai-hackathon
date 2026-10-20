package mutualstake.ledger

import mutualstake.db.instant
import mutualstake.db.toTimestamptz
import mutualstake.money.Money
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * The ledger only ever grows. There is no update or delete here, and the database rejects
 * both (trigger `ledger_entry_append_only`), so a correction is a new entry, never an edit.
 */
@Repository
class LedgerRepository(private val jdbc: JdbcClient) {

    /**
     * Records a PayPal-confirmed money event. Returns false when the same confirmation
     * (event and PayPal id) is already recorded, e.g. a webhook arriving after a poll.
     * This must stay DO NOTHING: DO UPDATE would be an UPDATE, and the trigger rejects it.
     */
    fun record(entry: LedgerEntry): Boolean =
        jdbc.sql(
            """
            insert into ledger_entry
                (id, contract_id, hold_id, event_type, direction, amount_minor, counterparty, paypal_ref, occurred_at)
            values
                (:id, :contractId, :holdId, :event, :direction, :amountMinor, :counterparty, :paypalRef, :occurredAt)
            on conflict (event_type, paypal_ref) do nothing
            """,
        )
            .param("id", entry.id)
            .param("contractId", entry.contractId)
            .param("holdId", entry.holdId)
            .param("event", entry.event.name)
            .param("direction", entry.direction.name)
            .param("amountMinor", entry.amount.minor)
            .param("counterparty", entry.counterparty)
            .param("paypalRef", entry.paypalRef)
            .param("occurredAt", entry.occurredAt.toTimestamptz())
            .update() == 1

    fun forContract(contractId: UUID): List<LedgerEntry> =
        jdbc.sql("select * from ledger_entry where contract_id = :contractId order by occurred_at, recorded_at, id")
            .param("contractId", contractId)
            .query { rs, _ ->
                LedgerEntry(
                    id = rs.getObject("id", UUID::class.java),
                    contractId = rs.getObject("contract_id", UUID::class.java),
                    holdId = rs.getObject("hold_id", UUID::class.java),
                    event = LedgerEvent.valueOf(rs.getString("event_type")),
                    amount = Money(rs.getLong("amount_minor")),
                    counterparty = rs.getString("counterparty"),
                    paypalRef = rs.getString("paypal_ref"),
                    occurredAt = rs.instant("occurred_at"),
                )
            }
            .list()
}
