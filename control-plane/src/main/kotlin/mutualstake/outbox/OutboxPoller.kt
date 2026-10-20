package mutualstake.outbox

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration

interface OutboxHandler {
    val type: OutboxType

    /** Must be idempotent: a crash after PayPal answered means the same event, same request id, runs again. */
    fun handle(event: OutboxEvent)

    /** Called once retries are exhausted or the failure is permanent: say so on the row the event is about. */
    fun onDead(event: OutboxEvent, reason: String)
}

/**
 * Executes outbox events. A claim is a lease, so a crashed run is simply picked up again; retries reuse
 * the hold's PayPal-Request-Id, so PayPal answers with the original result instead of acting twice.
 */
@Component
class OutboxPoller(private val outbox: OutboxRepository, handlers: List<OutboxHandler>) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val byType = handlers.associateBy { it.type }

    @Scheduled(fixedDelayString = "\${mutualstake.scheduling.outbox-poll-ms:500}")
    fun tick() {
        poll()
    }

    /** Runs one batch of due events and returns how many were claimed. */
    fun poll(): Int {
        val due = outbox.claimDue(BATCH, LEASE)
        due.forEach(::run)
        return due.size
    }

    /** Runs until nothing is due. Tests drive the flow with this instead of the schedule. */
    fun drain(maxRounds: Int = 100): Int {
        var total = 0
        repeat(maxRounds) {
            val claimed = poll()
            if (claimed == 0) return total
            total += claimed
        }
        return total
    }

    private fun run(event: OutboxEvent) {
        val handler = byType[event.type] ?: return outbox.markDead(event.id, "no handler for ${event.type}")
        try {
            handler.handle(event)
            outbox.markPublished(event.id)
        } catch (e: Exception) {
            val classified = e as? ClassifiedFailure
            if (classified == null) log.warn("outbox {} for {} failed (attempt {})", event.type, event.aggregateId, event.attempts, e)
            fail(handler, event, classified?.reason ?: "${e.javaClass.simpleName}: ${e.message}", classified?.retryable ?: true, classified)
        }
    }

    private fun fail(handler: OutboxHandler, event: OutboxEvent, reason: String, retryable: Boolean, classified: ClassifiedFailure?) {
        if (retryable && event.attempts < (classified?.maxAttempts ?: MAX_ATTEMPTS)) {
            outbox.retryLater(event.id, classified?.retryAfter ?: backoff(event.attempts), reason)
        } else {
            // Nothing more will happen on its own: say so where people look, rather than retry forever.
            outbox.markDead(event.id, reason)
            handler.onDead(event, reason)
        }
    }

    companion object {
        const val BATCH = 10
        const val MAX_ATTEMPTS = 8
        val LEASE: Duration = Duration.ofSeconds(60)

        fun backoff(attempts: Int): Duration = Duration.ofSeconds(minOf(60L, 1L shl (attempts - 1).coerceIn(0, 6)))
    }
}
