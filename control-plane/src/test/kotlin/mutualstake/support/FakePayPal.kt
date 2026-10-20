package mutualstake.support

import mutualstake.money.Money
import mutualstake.paypal.CreateOrderRequest
import mutualstake.paypal.PayPalAuthorization
import mutualstake.paypal.PayPalException
import mutualstake.paypal.PayPalGateway
import mutualstake.paypal.PayPalOrder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class MutableClock(@Volatile private var now: Instant = Instant.now()) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
    fun advance(by: Duration) { now = now.plus(by) }
    fun reset() { now = Instant.now() }
}

/**
 * PayPal as the US-4 spike observed it, not as the docs describe it: create answers PAYER_ACTION_REQUIRED
 * with a payer-action link; a replay with the same PayPal-Request-Id returns the original result; a second
 * authorize with a different request id is refused 422 ORDER_ALREADY_AUTHORIZED; voiding twice with a new
 * request id is refused 422 AUTHORIZATION_ALREADY_VOIDED. Test-only: production never falls back to this.
 */
class FakePayPal(private val clock: Clock) : PayPalGateway {
    class Order(
        val id: String, val referenceId: String, val amount: Money, val customId: String, val createRequestId: String,
        @Volatile var status: String = "PAYER_ACTION_REQUIRED", @Volatile var payerEmail: String? = null,
        @Volatile var authorization: PayPalAuthorization? = null, @Volatile var authorizeRequestId: String? = null,
        @Volatile var voidRequestId: String? = null,
    )

    @Volatile override var configured: Boolean = true
    @Volatile var webhookVerdict: Boolean = true
    /** Runs after PayPal has placed a hold but before the caller sees the answer: for racing a cancel. */
    @Volatile var afterAuthorize: (orderId: String) -> Unit = {}

    val orders = ConcurrentHashMap<String, Order>()
    val requestIds = CopyOnWriteArrayList<String>()
    private val byRequestId = ConcurrentHashMap<String, String>()
    private val counter = AtomicInteger()

    fun reset() {
        configured = true
        webhookVerdict = true
        afterAuthorize = {}
        orders.clear()
        requestIds.clear()
        byRequestId.clear()
    }

    /** The payer clicking approve in sandbox checkout. */
    fun approve(orderId: String, payerEmail: String) {
        orders.getValue(orderId).apply { status = "APPROVED"; this.payerEmail = payerEmail }
    }

    override fun createOrder(request: CreateOrderRequest): PayPalOrder {
        if (!configured) throw PayPalException.noCredentials("create_order")
        requestIds += request.requestId
        val id = byRequestId.computeIfAbsent(request.requestId) {
            val orderId = "FAKE-ORDER-${counter.incrementAndGet()}"
            orders[orderId] = Order(orderId, request.referenceId, request.amount, request.customId, request.requestId)
            orderId
        }
        return view(orders.getValue(id))
    }

    override fun getOrder(orderId: String, holdId: UUID?): PayPalOrder {
        if (!configured) throw PayPalException.noCredentials("get_order")
        return view(orders[orderId] ?: throw PayPalException("get_order", 404, "RESOURCE_NOT_FOUND", "INVALID_RESOURCE_ID", "fake-debug", null))
    }

    override fun authorizeOrder(orderId: String, requestId: String, holdId: UUID): PayPalOrder {
        if (!configured) throw PayPalException.noCredentials("authorize_order")
        requestIds += requestId
        val order = orders.getValue(orderId)
        synchronized(order) {
            if (order.authorizeRequestId == requestId) return view(order)
            if (order.authorization != null) throw unprocessable("authorize_order", "ORDER_ALREADY_AUTHORIZED")
            if (order.status != "APPROVED") throw unprocessable("authorize_order", "ORDER_NOT_APPROVED")
            val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
            order.authorization = PayPalAuthorization("FAKE-AUTH-$orderId", "CREATED", order.amount, order.customId, now.plus(Duration.ofDays(29)), now, now)
            order.authorizeRequestId = requestId
            order.status = "COMPLETED"
        }
        afterAuthorize(orderId)
        return view(order)
    }

    override fun getAuthorization(authorizationId: String, holdId: UUID?): PayPalAuthorization =
        requireNotNull(orderOf(authorizationId).authorization)

    override fun voidAuthorization(authorizationId: String, requestId: String, holdId: UUID): PayPalAuthorization {
        if (!configured) throw PayPalException.noCredentials("void_authorization")
        requestIds += requestId
        val order = orderOf(authorizationId)
        synchronized(order) {
            val auth = requireNotNull(order.authorization)
            if (auth.status == "VOIDED") {
                if (order.voidRequestId == requestId) return auth
                throw unprocessable("void_authorization", "AUTHORIZATION_ALREADY_VOIDED")
            }
            if (auth.status != "CREATED") throw unprocessable("void_authorization", "AUTHORIZATION_${auth.status}")
            order.authorization = auth.copy(status = "VOIDED", updatedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS))
            order.voidRequestId = requestId
            return order.authorization!!
        }
    }

    override fun verifyWebhook(headers: Map<String, String>, rawBody: String): Boolean = webhookVerdict

    private fun orderOf(authorizationId: String): Order = orders.values.single { it.authorization?.id == authorizationId }

    private fun unprocessable(operation: String, issue: String) = PayPalException(operation, 422, "UNPROCESSABLE_ENTITY", issue, "fake-debug", null)

    private fun view(o: Order) = PayPalOrder(o.id, o.status, o.customId, o.payerEmail, "https://www.sandbox.paypal.com/checkoutnow?token=${o.id}", o.authorization)
}

@TestConfiguration(proxyBeanMethods = false)
class FakePayPalConfig {
    @Bean
    @Primary
    fun testClock(): MutableClock = MutableClock()

    @Bean
    @Primary
    fun fakePayPal(clock: MutableClock): FakePayPal = FakePayPal(clock)
}
