package mutualstake.outbox

import mutualstake.db.instant
import mutualstake.db.uuid
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Work done outside the request: PayPal calls and verifier runs. Written in the same transaction as the state change that needs it. */
enum class OutboxType { CREATE_ORDER, CHECK_ORDER, AUTHORIZE, VOID, RUN_VERIFIER, CAPTURE, CHECK_CAPTURE, PAYOUT, CHECK_PAYOUT }

/** A failure that knows what it is: [reason] lands on the row that needs attention; [retryable] says whether waiting can help. */
interface ClassifiedFailure {
    val reason: String
    val retryable: Boolean

    /** A fixed wait instead of exponential backoff, e.g. polling something PayPal is still processing. */
    val retryAfter: Duration? get() = null

    /** How long to keep trying before giving up and flagging. */
    val maxAttempts: Int? get() = null
}

/**
 * PayPal has not finished yet (a PENDING capture, a payout still processing). Not a failure: it is polled
 * every few seconds, and only flagged if it is still pending after [maxAttempts] reads.
 */
class StillPendingException(override val reason: String) : RuntimeException(reason), ClassifiedFailure {
    override val retryable = true
    override val retryAfter: Duration = Duration.ofSeconds(5)
    override val maxAttempts = 120
}

data class OutboxEvent(val id: UUID, val aggregateId: UUID, val type: OutboxType, val attempts: Int, val createdAt: Instant = Instant.EPOCH)

@Repository
class OutboxRepository(private val jdbc: JdbcClient) {
    private val row = RowMapper { rs, _ ->
        OutboxEvent(rs.uuid("id"), rs.uuid("aggregate_id"), OutboxType.valueOf(rs.getString("type")), rs.getInt("attempts"), rs.instant("created_at"))
    }

    /**
     * Queues [type] for [aggregateId] unless one is already live. The webhook, the poll and the return-page
     * nudge can all ask for the same step; it still runs once. Returns false when one was already queued.
     */
    fun enqueue(aggregateId: UUID, type: OutboxType): Boolean =
        jdbc.sql(
            """
            insert into outbox_event (id, aggregate_id, type, payload) values (:id, :aggregate, :type, '{}'::jsonb)
            on conflict (aggregate_id, type) where published_at is null and dead_at is null do nothing
            """,
        ).param("id", UUID.randomUUID()).param("aggregate", aggregateId).param("type", type.name).update() == 1

    /**
     * Claims due events under a lease, so a crashed worker's claim simply expires and another picks it up.
     * RETURNING does not keep the subquery's order, so the claimed batch is sorted oldest first here.
     */
    fun claimDue(limit: Int, lease: Duration): List<OutboxEvent> =
        jdbc.sql(
            """
            update outbox_event set locked_until = now() + make_interval(secs => :lease), attempts = attempts + 1
            where id in (select id from outbox_event
                         where published_at is null and dead_at is null and next_attempt_at <= now()
                           and (locked_until is null or locked_until < now())
                         order by created_at
                         limit :limit
                         for update skip locked)
            returning *
            """,
        ).param("lease", lease.seconds).param("limit", limit).query(row).list().sortedWith(compareBy({ it.createdAt }, { it.id }))

    fun markPublished(id: UUID) {
        jdbc.sql("update outbox_event set published_at = now(), locked_until = null, last_error = null where id = :id").param("id", id).update()
    }

    fun retryLater(id: UUID, delay: Duration, error: String) {
        jdbc.sql("update outbox_event set next_attempt_at = now() + make_interval(secs => :delay), locked_until = null, last_error = :error where id = :id")
            .param("delay", delay.seconds).param("error", error.take(1000)).param("id", id).update()
    }

    fun markDead(id: UUID, error: String) {
        jdbc.sql("update outbox_event set dead_at = now(), locked_until = null, last_error = :error where id = :id")
            .param("error", error.take(1000)).param("id", id).update()
    }

    fun live(aggregateId: UUID): List<OutboxType> =
        jdbc.sql("select type from outbox_event where aggregate_id = :a and published_at is null and dead_at is null order by created_at")
            .param("a", aggregateId).query(String::class.java).list().map(OutboxType::valueOf)
}
