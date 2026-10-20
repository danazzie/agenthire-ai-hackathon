package mutualstake.contract

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

data class Transition(val from: ContractState, val to: ContractState, val cause: String, val occurredAt: Instant)

@Repository
class ContractRepository(private val jdbc: JdbcClient) {
    private val row = RowMapper { rs, _ ->
        Contract(
            id = rs.uuid("id"),
            sessionId = rs.uuid("session_id"),
            buyerId = rs.uuid("buyer_id"),
            sellerId = rs.uuid("seller_id"),
            sellerType = SellerType.valueOf(rs.getString("seller_type")),
            jobText = rs.getString("job_text"),
            feeCap = rs.money("fee_cap_minor"),
            fee = rs.moneyOrNull("fee_minor"),
            bond = rs.moneyOrNull("bond_minor"),
            currency = rs.getString("currency"),
            deadlineAt = rs.instantOrNull("deadline_at"),
            verifierId = rs.uuidOrNull("verifier_id"),
            termsHash = rs.getString("terms_hash"),
            state = ContractState.valueOf(rs.getString("state")),
            version = rs.getInt("version"),
            createdAt = rs.instant("created_at"),
            updatedAt = rs.instant("updated_at"),
            settlementPlannedAt = rs.instantOrNull("settlement_planned_at"),
            settlementConfirmedAt = rs.instantOrNull("settlement_confirmed_at"),
            needsAttention = rs.getBoolean("needs_attention"),
            attentionReason = rs.getString("attention_reason"),
        )
    }

    fun insertDraft(sessionId: UUID, buyerId: UUID, sellerId: UUID, sellerType: SellerType, jobText: String, feeCap: Money): Contract {
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            insert into contract (id, session_id, buyer_id, seller_id, seller_type, job_text, fee_cap_minor)
            values (:id, :session, :buyer, :seller, :type, :job, :cap)
            """,
        ).param("id", id).param("session", sessionId).param("buyer", buyerId).param("seller", sellerId)
            .param("type", sellerType.name).param("job", jobText).param("cap", feeCap.minor).update()
        return find(id)!!
    }

    fun find(id: UUID): Contract? = jdbc.sql("select * from contract where id = :id").param("id", id).query(row).optional().orElse(null)

    /** Locks the row for the rest of the transaction. Always lock the contract before its holds. */
    fun lockForUpdate(id: UUID): Contract? =
        jdbc.sql("select * from contract where id = :id for update").param("id", id).query(row).optional().orElse(null)

    fun inSession(sessionId: UUID): List<Contract> =
        jdbc.sql("select * from contract where session_id = :s order by created_at desc").param("s", sessionId).query(row).list()

    /**
     * Compare-and-set: moves [id] from [from] to [to] only if it is still in [from], and logs the transition.
     * Off-list transitions throw here, before the database trigger would refuse them (MS003).
     */
    fun transition(id: UUID, from: ContractState, to: ContractState, cause: String) {
        if (!from.canBecome(to)) throw IllegalTransitionException(id, from, to)
        val moved = jdbc.sql("update contract set state = :to, version = version + 1, updated_at = now() where id = :id and state = :from")
            .param("to", to.name).param("id", id).param("from", from.name).update()
        if (moved != 1) throw StaleStateException(id, from.name)
        log(id, from, to, cause)
    }

    /** DRAFT -> TERMS_LOCKED, writing the agreed terms in the same statement. They cannot change afterwards (MS004). */
    fun lockTerms(id: UUID, terms: Terms, termsHash: String, cause: String) {
        val moved = jdbc.sql(
            """
            update contract set state = 'TERMS_LOCKED', fee_minor = :fee, bond_minor = :bond, deadline_at = :deadline,
                                verifier_id = :verifier, terms_hash = :hash, version = version + 1, updated_at = now()
            where id = :id and state = 'DRAFT'
            """,
        ).param("fee", terms.fee.minor).param("bond", terms.bond.minor).param("deadline", terms.deadlineAt.toTimestamptz())
            .param("verifier", terms.verifierId).param("hash", termsHash).param("id", id).update()
        if (moved != 1) throw StaleStateException(id, ContractState.DRAFT.name)
        log(id, ContractState.DRAFT, ContractState.TERMS_LOCKED, cause)
    }

    fun transitions(id: UUID): List<Transition> =
        jdbc.sql("select * from contract_transition where contract_id = :id order by occurred_at, id").param("id", id)
            .query { rs, _ ->
                Transition(ContractState.valueOf(rs.getString("from_state")), ContractState.valueOf(rs.getString("to_state")),
                    rs.getString("cause"), rs.instant("occurred_at"))
            }.list()

    fun termsLockedPastDeadline(now: Instant): List<UUID> =
        jdbc.sql("select id from contract where state = 'TERMS_LOCKED' and deadline_at < :now")
            .param("now", now.toTimestamptz()).query(UUID::class.java).list()

    fun heldPastDeadlineWithoutDelivery(now: Instant): List<UUID> =
        jdbc.sql(
            """
            select c.id from contract c
            where c.state = 'BOTH_HELD' and c.deadline_at < :now and not exists (select 1 from delivery d where d.contract_id = c.id)
            """,
        ).param("now", now.toTimestamptz()).query(UUID::class.java).list()

    /** Repair sweep for the race the row lock prevents: both holds HELD but the contract not yet moved on. */
    fun termsLockedWithBothHeld(): List<UUID> =
        jdbc.sql(
            """
            select c.id from contract c
            where c.state = 'TERMS_LOCKED'
              and (select count(*) from hold h where h.contract_id = c.id and h.state = 'HELD') = 2
            """,
        ).query(UUID::class.java).list()

    fun markPlanned(id: UUID, confirmedByHuman: Boolean) {
        jdbc.sql(
            """
            update contract set settlement_planned_at = now(),
                                settlement_confirmed_at = case when :human then now() else settlement_confirmed_at end
            where id = :id and settlement_planned_at is null
            """,
        ).param("human", confirmedByHuman).param("id", id).update()
    }

    fun flag(id: UUID, reason: String) {
        jdbc.sql("update contract set needs_attention = true, attention_reason = :r where id = :id").param("r", reason.take(500)).param("id", id).update()
    }

    fun clearFlag(id: UUID) {
        jdbc.sql("update contract set needs_attention = false, attention_reason = null where id = :id and needs_attention").param("id", id).update()
    }

    /** Verdicts whose settlement is not queued yet although the session's first capture is confirmed (repair). */
    fun awaitingSettlementPlan(): List<UUID> =
        jdbc.sql(
            """
            select c.id from contract c join session s on s.id = c.session_id
            where c.state in ('PASS', 'FAIL', 'EXPIRED') and c.settlement_planned_at is null
              and s.first_capture_confirmed_at is not null and not c.needs_attention
            """,
        ).query(UUID::class.java).list()

    /** Settling contracts whose holds have both reached their end (repair for the race the row lock prevents). */
    fun settlingWithFinalHolds(): List<UUID> =
        jdbc.sql(
            """
            select c.id from contract c
            where c.state in ('PASS', 'FAIL', 'EXPIRED')
              and (select count(*) from hold h where h.contract_id = c.id and h.state in ('PAID_OUT', 'VOIDED')) = 2
            """,
        ).query(UUID::class.java).list()

    private fun log(id: UUID, from: ContractState, to: ContractState, cause: String) {
        jdbc.sql("insert into contract_transition (id, contract_id, from_state, to_state, cause) values (:id, :c, :from, :to, :cause)")
            .param("id", UUID.randomUUID()).param("c", id).param("from", from.name).param("to", to.name).param("cause", cause).update()
    }
}
