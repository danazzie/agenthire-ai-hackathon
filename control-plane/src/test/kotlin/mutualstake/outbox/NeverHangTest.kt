package mutualstake.outbox

import mutualstake.contract.NegotiationService
import mutualstake.contract.OfferKind.ACCEPT
import mutualstake.hold.HoldRepository
import mutualstake.hold.HoldState
import mutualstake.money.Money
import mutualstake.party.PartyRepository
import mutualstake.session.SessionService
import mutualstake.support.FakePayPal
import mutualstake.support.IntegrationTest
import mutualstake.support.MutableClock
import mutualstake.support.compiledVerifier
import mutualstake.support.quiesceOutbox
import mutualstake.verifier.VerifierRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Regression for the night the outbox stopped: the laptop slept mid-request, a PayPal GET never returned, and
 * with no read timeout and one scheduling thread, the poller and the sweeper both stopped for nine hours.
 */
@IntegrationTest
class NeverHangTest @Autowired constructor(
    private val restClients: RestClient.Builder,
    private val poller: OutboxPoller,
    private val negotiation: NegotiationService,
    private val parties: PartyRepository,
    private val verifiers: VerifierRepository,
    private val sessions: SessionService,
    private val holds: HoldRepository,
    private val fake: FakePayPal,
    private val clock: MutableClock,
    private val jdbc: JdbcClient,
) {
    @Test
    fun `an HTTP call to a server that never answers times out`() {
        ServerSocket(0).use { silent ->
            val accepted = thread(isDaemon = true) { runCatching { silent.accept().use { Thread.sleep(60_000) } } }
            val started = System.nanoTime()
            assertThrows<ResourceAccessException> {
                restClients.baseUrl("http://127.0.0.1:${silent.localPort}").build().get().uri("/v2/checkout/orders/X").retrieve().body(String::class.java)
            }
            val seconds = (System.nanoTime() - started) / 1e9
            assertTrue(seconds < 10, "timed out after $seconds s, from spring.http.client.read-timeout")
            accepted.interrupt()
        }
    }

    @Test
    fun `a step that hangs is abandoned and retried, and never stops the queue`() {
        fake.reset()
        clock.reset()
        quiesceOutbox(jdbc)
        sessions.startNew(Money(3000), 2)
        val alex = parties.byHandle("alex")!!
        val maya = parties.byHandle("maya")!!
        val verifier = compiledVerifier(verifiers)
        val c = negotiation.open(alex, "maya", "job", Money(200))
        val deadline = clock.instant().plus(Duration.ofHours(1))
        negotiation.offer(c.id, maya, ACCEPT, Money(150), Money(40), deadline, verifier.id, null)
        negotiation.offer(c.id, alex, ACCEPT, Money(150), Money(40), deadline, verifier.id, null)

        val release = CountDownLatch(1)
        var calls = 0
        fake.beforeCreate = { if (calls++ == 0) release.await(30, TimeUnit.SECONDS) }
        val realTimeout = poller.handlerTimeout
        poller.handlerTimeout = Duration.ofSeconds(1)
        try {
            val started = System.nanoTime()
            poller.drain()
            assertTrue((System.nanoTime() - started) / 1e9 < 10, "the drain came back instead of waiting on the stuck call")
            release.countDown()
            jdbc.sql("update outbox_event set next_attempt_at = now() where published_at is null and dead_at is null").update()
            poller.drain()
        } finally {
            poller.handlerTimeout = realTimeout
            fake.beforeCreate = {}
        }
        assertTrue(holds.forContract(c.id).all { it.state == HoldState.ORDER_CREATED }, "both orders got created in the end")
        assertEquals(2, fake.orders.size, "the stuck create and its retry share a PayPal-Request-Id: one order per hold")
    }
}
