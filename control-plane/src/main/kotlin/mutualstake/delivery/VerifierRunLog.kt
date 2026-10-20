package mutualstake.delivery

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mutualstake.db.instant
import mutualstake.db.uuid
import mutualstake.db.uuidOrNull
import mutualstake.verifier.CheckResult
import mutualstake.verifier.RunOutcome
import mutualstake.verifier.VerifierRunResult
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

enum class RunKind { DELIVERY, DRY_RUN, SELF_TEST }

/** A stored run: everything needed to replay the verdict elsewhere and get the same answer. */
data class RunRecord(
    val id: UUID,
    val verifierId: UUID,
    val deliveryId: UUID?,
    val kind: RunKind,
    val sourceSha256: String,
    val harnessSha256: String,
    val runtime: String,
    val inputSha256: String,
    val outcome: RunOutcome,
    val checks: List<CheckResult>,
    val failingCheck: String?,
    val summary: String?,
    val error: String?,
    val injectionFlags: List<String>,
    val durationMs: Int,
    val ranAt: Instant,
)

@Repository
class VerifierRunLog(private val jdbc: JdbcClient, private val mapper: ObjectMapper) {
    private val row = RowMapper { rs, _ ->
        RunRecord(
            id = rs.uuid("id"),
            verifierId = rs.uuid("verifier_id"),
            deliveryId = rs.uuidOrNull("delivery_id"),
            kind = RunKind.valueOf(rs.getString("kind")),
            sourceSha256 = rs.getString("source_sha256"),
            harnessSha256 = rs.getString("harness_sha256"),
            runtime = rs.getString("runtime"),
            inputSha256 = rs.getString("input_sha256"),
            outcome = RunOutcome.valueOf(rs.getString("outcome")),
            checks = mapper.readValue(rs.getString("checks")),
            failingCheck = rs.getString("failing_check"),
            summary = rs.getString("summary"),
            error = rs.getString("error"),
            injectionFlags = (rs.getArray("injection_flags")?.array as Array<*>?)?.map { it.toString() }.orEmpty(),
            durationMs = rs.getInt("duration_ms"),
            ranAt = rs.instant("ran_at"),
        )
    }

    fun record(verifierId: UUID, deliveryId: UUID?, kind: RunKind, inputSha256: String, injectionFlags: List<String>, run: VerifierRunResult): UUID {
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            insert into verifier_run (id, verifier_id, delivery_id, kind, source_sha256, harness_sha256, runtime, input_sha256,
                                      outcome, checks, failing_check, summary, error, injection_flags, duration_ms)
            values (:id, :verifier, :delivery, :kind, :source, :harness, :runtime, :input,
                    :outcome, cast(:checks as jsonb), :failing, :summary, :error, :flags, :ms)
            """,
        ).param("id", id).param("verifier", verifierId).param("delivery", deliveryId).param("kind", kind.name)
            .param("source", run.sourceSha256).param("harness", run.harnessSha256).param("runtime", run.runtime).param("input", inputSha256)
            .param("outcome", run.outcome.name).param("checks", mapper.writeValueAsString(run.checks)).param("failing", run.failingCheck)
            .param("summary", run.summary).param("error", run.error).param("flags", injectionFlags.toTypedArray()).param("ms", run.durationMs)
            .update()
        return id
    }

    fun find(id: UUID): RunRecord? = jdbc.sql("select * from verifier_run where id = :id").param("id", id).query(row).optional().orElse(null)

    fun forDelivery(deliveryId: UUID): List<RunRecord> =
        jdbc.sql("select * from verifier_run where delivery_id = :d order by ran_at, id").param("d", deliveryId).query(row).list()
}
