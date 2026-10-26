package mutualstake.delivery

import mutualstake.db.instant
import mutualstake.db.instantOrNull
import mutualstake.db.uuid
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class Delivery(
    val id: UUID,
    val contractId: UUID,
    val payloadSha256: String,
    val submittedAt: Instant,
    val verdict: String?,
    val failingCheck: String?,
    val traceRef: String?,
    val verifiedAt: Instant?,
    /** Injection attempts found in the text. Recorded and logged; they never change the verdict (US-20). */
    val injectionFlags: List<String>,
    val needsAttention: Boolean,
    val attentionReason: String?,
)

@Repository
class DeliveryRepository(private val jdbc: JdbcClient) {
    private val row = RowMapper { rs, _ ->
        Delivery(
            id = rs.uuid("id"),
            contractId = rs.uuid("contract_id"),
            payloadSha256 = rs.getString("payload_sha256"),
            submittedAt = rs.instant("submitted_at"),
            verdict = rs.getString("verdict"),
            failingCheck = rs.getString("failing_check"),
            traceRef = rs.getString("trace_ref"),
            verifiedAt = rs.instantOrNull("verified_at"),
            injectionFlags = rs.strings("injection_flags"),
            needsAttention = rs.getBoolean("needs_attention"),
            attentionReason = rs.getString("attention_reason"),
        )
    }

    fun insert(contractId: UUID, payloadSha256: String, injectionFlags: List<String>): Delivery {
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            insert into delivery (id, contract_id, payload_ref, payload_sha256, injection_flags)
            values (:id, :contract, :ref, :sha, :flags)
            """,
        ).param("id", id).param("contract", contractId).param("ref", "blob:$payloadSha256").param("sha", payloadSha256)
            .param("flags", injectionFlags.toTypedArray()).update()
        return find(id)!!
    }

    fun find(id: UUID): Delivery? = jdbc.sql("select * from delivery where id = :id").param("id", id).query(row).optional().orElse(null)

    fun forContract(contractId: UUID): Delivery? =
        jdbc.sql("select * from delivery where contract_id = :c").param("c", contractId).query(row).optional().orElse(null)

    /** Records the verdict. Once set it is final: the database refuses any change (MS004). */
    fun setVerdict(id: UUID, verdict: String, failingCheck: String?, traceRef: String) {
        jdbc.sql("update delivery set verdict = :v, failing_check = :f, trace_ref = :t, verified_at = now() where id = :id and verdict is null")
            .param("v", verdict).param("f", failingCheck).param("t", traceRef).param("id", id).update()
    }

    fun flag(id: UUID, reason: String) {
        jdbc.sql("update delivery set needs_attention = true, attention_reason = :r where id = :id").param("r", reason.take(500)).param("id", id).update()
    }

    private fun ResultSet.strings(column: String): List<String> =
        (getArray(column)?.array as Array<*>?)?.map { it.toString() }.orEmpty()
}
