package mutualstake.support

import org.springframework.jdbc.core.simple.JdbcClient

/**
 * Test isolation: dead-letters whatever earlier tests left queued, so one test's drain never runs another's
 * PayPal calls. (outbox_event is a work queue, not history; only tests do this.)
 */
fun quiesceOutbox(jdbc: JdbcClient) {
    jdbc.sql("update outbox_event set dead_at = now(), last_error = 'test isolation' where published_at is null and dead_at is null").update()
}
