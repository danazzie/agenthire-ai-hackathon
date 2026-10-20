package mutualstake.paypal

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

data class PayPalCall(
    val operation: String,
    val method: String,
    val path: String,
    val requestId: String?,
    val httpStatus: Int?,
    val debugId: String?,
    val errorName: String?,
    val errorIssue: String?,
    val durationMs: Int,
)

/** Where every PayPal call is written down. An interface so the client can be tested without a database. */
interface PayPalCallRecorder {
    fun record(holdId: UUID?, call: PayPalCall)
}

/** Append-only record of every PayPal call: the evidence a judge can match against the sandbox (US-22). */
@Repository
class PayPalCallLog(private val jdbc: JdbcClient) : PayPalCallRecorder {
    override fun record(holdId: UUID?, call: PayPalCall) {
        jdbc.sql(
            """
            insert into paypal_call (id, hold_id, operation, method, path, request_id, http_status, debug_id, error_name, error_issue, duration_ms)
            values (:id, :hold, :op, :method, :path, :request, :status, :debug, :name, :issue, :ms)
            """,
        ).param("id", UUID.randomUUID()).param("hold", holdId).param("op", call.operation).param("method", call.method)
            .param("path", call.path).param("request", call.requestId).param("status", call.httpStatus).param("debug", call.debugId)
            .param("name", call.errorName).param("issue", call.errorIssue).param("ms", call.durationMs).update()
    }

    fun forHold(holdId: UUID): List<PayPalCall> =
        jdbc.sql("select * from paypal_call where hold_id = :h order by called_at, id").param("h", holdId).query { rs, _ ->
            PayPalCall(
                rs.getString("operation"), rs.getString("method"), rs.getString("path"), rs.getString("request_id"),
                rs.getObject("http_status") as Int?, rs.getString("debug_id"), rs.getString("error_name"), rs.getString("error_issue"),
                rs.getInt("duration_ms"),
            )
        }.list()
}
