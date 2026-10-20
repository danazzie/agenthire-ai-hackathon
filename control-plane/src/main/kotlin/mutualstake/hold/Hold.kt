package mutualstake.hold

import mutualstake.contract.StaleStateException
import mutualstake.db.instant
import mutualstake.db.instantOrNull
import mutualstake.db.money
import mutualstake.db.moneyOrNull
import mutualstake.db.toTimestamptz
import mutualstake.db.uuid
import mutualstake.db.uuidOrNull
import mutualstake.money.Money
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

enum class HoldSide(val label: String) {
    BUYER_FEE("buyer fee"),
    SELLER_BOND("seller bond"),
}

/** Follows PayPal, never ahead of it. The database holds the same list (hold_transition_rule). */
enum class HoldState {
    PENDING, ORDER_CREATED, APPROVED, HELD, VOID_REQUESTED, VOIDED, CAPTURE_REQUESTED, CAPTURED, PAYOUT_REQUESTED, PAID_OUT, ABANDONED;

    fun canBecome(next: HoldState): Boolean = next in TRANSITIONS[this].orEmpty()

    companion object {
        val TRANSITIONS: Map<HoldState, Set<HoldState>> = mapOf(
            PENDING to setOf(ORDER_CREATED, ABANDONED),
            ORDER_CREATED to setOf(APPROVED, ABANDONED),
            APPROVED to setOf(HELD, VOID_REQUESTED, ABANDONED),
            ABANDONED to setOf(VOID_REQUESTED),
            HELD to setOf(VOID_REQUESTED, CAPTURE_REQUESTED),
            VOID_REQUESTED to setOf(VOIDED),
            CAPTURE_REQUESTED to setOf(CAPTURED),
            CAPTURED to setOf(PAYOUT_REQUESTED),
            PAYOUT_REQUESTED to setOf(PAID_OUT),
        )
    }
}

data class Hold(
    val id: UUID,
    val contractId: UUID,
    val side: HoldSide,
    val payerId: UUID,
    val amount: Money,
    val customId: String,
    val requestIdBase: String,
    val paypalOrderId: String?,
    val approveUrl: String?,
    val authorizationId: String?,
    val authorizationExpiresAt: Instant?,
    val captureId: String?,
    val captured: Money?,
    val payoutRecipientId: UUID?,
    val senderBatchId: String?,
    val payoutBatchId: String?,
    val payoutItemId: String?,
    val state: HoldState,
    val needsAttention: Boolean,
    val attentionReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** The deterministic PayPal-Request-Id for one operation on this hold (US-17). */
    fun requestId(operation: String): String = "$requestIdBase:$operation"
}

@Repository
class HoldRepository(private val jdbc: JdbcClient) {
    private val row = RowMapper { rs, _ ->
        Hold(
            id = rs.uuid("id"),
            contractId = rs.uuid("contract_id"),
            side = HoldSide.valueOf(rs.getString("side")),
            payerId = rs.uuid("payer_id"),
            amount = rs.money("amount_minor"),
            customId = rs.getString("custom_id"),
            requestIdBase = rs.getString("request_id"),
            paypalOrderId = rs.getString("paypal_order_id"),
            approveUrl = rs.getString("approve_url"),
            authorizationId = rs.getString("authorization_id"),
            authorizationExpiresAt = rs.instantOrNull("authorization_expires_at"),
            captureId = rs.getString("capture_id"),
            captured = rs.moneyOrNull("captured_minor"),
            payoutRecipientId = rs.uuidOrNull("payout_recipient_id"),
            senderBatchId = rs.getString("sender_batch_id"),
            payoutBatchId = rs.getString("payout_batch_id"),
            payoutItemId = rs.getString("payout_item_id"),
            state = HoldState.valueOf(rs.getString("state")),
            needsAttention = rs.getBoolean("needs_attention"),
            attentionReason = rs.getString("attention_reason"),
            createdAt = rs.instant("created_at"),
            updatedAt = rs.instant("updated_at"),
        )
    }

    /** A fresh hold with a never-reused request id base (US-17, US-31). The DB checks it matches its contract (MS002). */
    fun insert(contractId: UUID, side: HoldSide, payerId: UUID, amount: Money, customId: String): Hold {
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            insert into hold (id, contract_id, side, payer_id, amount_minor, custom_id, request_id)
            values (:id, :contract, :side, :payer, :amount, :custom, :request)
            """,
        ).param("id", id).param("contract", contractId).param("side", side.name).param("payer", payerId)
            .param("amount", amount.minor).param("custom", customId).param("request", "ms-$id").update()
        return find(id)!!
    }

    fun find(id: UUID): Hold? = jdbc.sql("select * from hold where id = :id").param("id", id).query(row).optional().orElse(null)

    fun lockForUpdate(id: UUID): Hold = jdbc.sql("select * from hold where id = :id for update").param("id", id).query(row).single()

    fun forContract(contractId: UUID): List<Hold> =
        jdbc.sql("select * from hold where contract_id = :c order by side").param("c", contractId).query(row).list()

    fun forContractForUpdate(contractId: UUID): List<Hold> =
        jdbc.sql("select * from hold where contract_id = :c order by side for update").param("c", contractId).query(row).list()

    fun byOrderId(orderId: String): Hold? =
        jdbc.sql("select * from hold where paypal_order_id = :o").param("o", orderId).query(row).optional().orElse(null)

    fun byAuthorizationId(authorizationId: String): Hold? =
        jdbc.sql("select * from hold where authorization_id = :a").param("a", authorizationId).query(row).optional().orElse(null)

    /**
     * Compare-and-set state change, optionally writing PayPal ids in the same statement. Column names
     * come from code, never from input.
     */
    fun transition(id: UUID, from: HoldState, to: HoldState, set: Map<String, Any?> = emptyMap()) {
        require(from == to || from.canBecome(to)) { "illegal hold transition $from -> $to" }
        require(set.keys.all { it in WRITABLE }) { "not a writable hold column: ${set.keys - WRITABLE}" }
        val assignments = (listOf("state = :to", "version = version + 1", "updated_at = now()") + set.keys.map { "$it = :$it" }).joinToString(", ")
        var spec = jdbc.sql("update hold set $assignments where id = :id and state = :from")
            .param("to", to.name).param("id", id).param("from", from.name)
        for ((column, value) in set) spec = spec.param(column, if (value is Instant) value.toTimestamptz() else if (value is Money) value.minor else value)
        if (spec.update() != 1) throw StaleStateException(id, from.name)
    }

    /** Needs-attention is a flag beside the state, so the money stage is never lost. */
    fun flag(id: UUID, reason: String) {
        jdbc.sql("update hold set needs_attention = true, attention_reason = :reason, updated_at = now() where id = :id")
            .param("reason", reason.take(500)).param("id", id).update()
    }

    /** Unapproved orders whose last PayPal check is older than [checkedBefore]: the polling fallback (US-30). */
    fun awaitingApproval(checkedBefore: Instant): List<UUID> =
        jdbc.sql(
            """
            select h.id from hold h join contract c on c.id = h.contract_id
            where h.state = 'ORDER_CREATED' and c.state = 'TERMS_LOCKED' and not h.needs_attention
              and not exists (select 1 from outbox_event e
                              where e.aggregate_id = h.id and e.type = 'CHECK_ORDER'
                                and ((e.published_at is null and e.dead_at is null) or e.published_at > :before))
            """,
        ).param("before", checkedBefore.toTimestamptz()).query(UUID::class.java).list()

    companion object {
        private val WRITABLE = setOf(
            "paypal_order_id", "approve_url", "authorization_id", "authorization_expires_at", "capture_id", "captured_minor",
            "payout_recipient_id", "sender_batch_id", "payout_batch_id", "payout_item_id",
        )
    }
}
