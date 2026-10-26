package mutualstake.support

import mutualstake.money.Money
import mutualstake.paypal.CreateOrderRequest
import mutualstake.paypal.PayPalAuthorization
import mutualstake.paypal.PayPalCapture
import mutualstake.paypal.PayPalException
import mutualstake.paypal.PayPalGateway
import mutualstake.paypal.PayPalOrder
import mutualstake.paypal.PayPalPayoutBatch
import mutualstake.paypal.PayPalPayoutItem
import mutualstake.paypal.PayoutRequest
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

    class Capture(
        val id: String, val authorizationId: String, val orderId: String, @Volatile var status: String, val amount: Money, val fee: Money,
        val customId: String, val requestId: String, val createdAt: Instant,
    )

    class Payout(val batchId: String, val senderBatchId: String, val receiver: String, val amount: Money, val itemId: String, val requestId: String)

    @Volatile override var configured: Boolean = true
    @Volatile var webhookVerdict: Boolean = true
    /** Runs before PayPal answers an order create: block here to play a call that never returns. */
    @Volatile var beforeCreate: () -> Unit = {}
    /** Runs after PayPal has placed a hold but before the caller sees the answer: for racing a cancel. */
    @Volatile var afterAuthorize: (orderId: String) -> Unit = {}
    /** Runs after PayPal has captured but before the caller sees it: throw here to lose the answer (US-17). */
    @Volatile var afterCapture: (captureId: String) -> Unit = {}
    /** Runs after PayPal has created the payout but before the caller sees it: throw here to lose the answer. */
    @Volatile var afterPayout: (batchId: String) -> Unit = {}
    /** The status a new capture starts in. The sandbox answered COMPLETED. */
    @Volatile var captureStatus: String = "COMPLETED"
    /** What a payout item reports when read back. The sandbox reached SUCCESS in about 24 s. */
    @Volatile var payoutItemStatus: String = "SUCCESS"
    /** Whether a duplicate payout's refusal links the batch that exists. PayPal's did. */
    @Volatile var duplicatePayoutLinks: Boolean = true

    val orders = ConcurrentHashMap<String, Order>()
    val captures = ConcurrentHashMap<String, Capture>()
    val payouts = ConcurrentHashMap<String, Payout>()
    val requestIds = CopyOnWriteArrayList<String>()
    private val byRequestId = ConcurrentHashMap<String, String>()
    private val counter = AtomicInteger()

    fun reset() {
        configured = true
        webhookVerdict = true
        beforeCreate = {}
        afterAuthorize = {}
        afterCapture = {}
        afterPayout = {}
        captureStatus = "COMPLETED"
        payoutItemStatus = "SUCCESS"
        duplicatePayoutLinks = true
        orders.clear()
        captures.clear()
        payouts.clear()
        requestIds.clear()
        byRequestId.clear()
    }

    fun completeCapture(captureId: String) {
        captures.getValue(captureId).status = "COMPLETED"
    }

    /** PayPal's US fee, 3.49% + 49 cents, never more than the amount: on a 40-cent bond it is all 40 (seen in the spike). */
    private fun feeOn(amount: Money): Money = Money(minOf(amount.minor, (amount.minor * 349 + 5000) / 10000 + 49))

    /** The payer clicking approve in sandbox checkout. */
    fun approve(orderId: String, payerEmail: String) {
        orders.getValue(orderId).apply { status = "APPROVED"; this.payerEmail = payerEmail }
    }

    override fun createOrder(request: CreateOrderRequest): PayPalOrder {
        if (!configured) throw PayPalException.noCredentials("create_order")
        beforeCreate()
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

    override fun captureAuthorization(authorizationId: String, requestId: String, holdId: UUID): PayPalCapture {
        if (!configured) throw PayPalException.noCredentials("capture_authorization")
        requestIds += requestId
        val order = orderOf(authorizationId)
        val capture = synchronized(order) {
            val existing = captures.values.firstOrNull { it.authorizationId == authorizationId }
            if (existing != null) {
                if (existing.requestId == requestId) return view(existing)
                throw unprocessable("capture_authorization", "AUTHORIZATION_ALREADY_CAPTURED")
            }
            val auth = requireNotNull(order.authorization)
            if (auth.status == "VOIDED") throw unprocessable("capture_authorization", "AUTHORIZATION_VOIDED")
            val amount = requireNotNull(auth.amount)
            val created = Capture(
                "FAKE-CAP-${counter.incrementAndGet()}", authorizationId, order.id, captureStatus, amount, feeOn(amount), order.customId, requestId,
                clock.instant().truncatedTo(ChronoUnit.SECONDS),
            )
            captures[created.id] = created
            order.authorization = auth.copy(status = "CAPTURED")
            created
        }
        afterCapture(capture.id)
        return view(capture)
    }

    override fun getCapture(captureId: String, holdId: UUID?): PayPalCapture =
        view(captures[captureId] ?: throw PayPalException("get_capture", 404, "RESOURCE_NOT_FOUND", "INVALID_RESOURCE_ID", "fake-debug", null))

    override fun createPayout(request: PayoutRequest): PayPalPayoutBatch {
        if (!configured) throw PayPalException.noCredentials("create_payout")
        requestIds += request.requestId
        val payout = synchronized(payouts) {
            payouts.values.firstOrNull { it.senderBatchId == request.senderBatchId }?.let { existing ->
                throw PayPalException(
                    "create_payout", 400, "USER_BUSINESS_ERROR", "Batch with given sender_batch_id already exists", "fake-debug", "User business error.",
                    field = "SENDER_BATCH_ID",
                    links = if (duplicatePayoutLinks) listOf("https://api.sandbox.paypal.com/v1/payments/payouts/${existing.batchId}") else emptyList(),
                )
            }
            val n = counter.incrementAndGet()
            Payout("FAKEBATCH$n", request.senderBatchId, request.receiverEmail, request.amount, "FAKEITEM$n", request.requestId).also { payouts[it.batchId] = it }
        }
        afterPayout(payout.batchId)
        return PayPalPayoutBatch(payout.batchId, "PENDING", payout.senderBatchId, emptyList())
    }

    override fun getPayoutBatch(batchId: String, holdId: UUID?): PayPalPayoutBatch {
        val payout = payouts[batchId] ?: throw PayPalException("get_payout_batch", 404, "RESOURCE_NOT_FOUND", null, "fake-debug", null)
        val status = payoutItemStatus
        val item = PayPalPayoutItem(payout.itemId, "FAKE-TXN-${payout.itemId}", status, payout.amount, Money(25), clock.instant().truncatedTo(ChronoUnit.SECONDS))
        return PayPalPayoutBatch(payout.batchId, if (status == "SUCCESS") "SUCCESS" else "PROCESSING", payout.senderBatchId, listOf(item))
    }

    override fun verifyWebhook(headers: Map<String, String>, rawBody: String): Boolean = webhookVerdict

    private fun orderOf(authorizationId: String): Order = orders.values.single { it.authorization?.id == authorizationId }

    private fun unprocessable(operation: String, issue: String) = PayPalException(operation, 422, "UNPROCESSABLE_ENTITY", issue, "fake-debug", null)

    private fun view(o: Order) = PayPalOrder(
        o.id, o.status, o.customId, o.payerEmail, "https://www.sandbox.paypal.com/checkoutnow?token=${o.id}", o.authorization,
        captures.values.firstOrNull { it.orderId == o.id }?.let(::view),
    )

    private fun view(c: Capture) = PayPalCapture(c.id, c.status, c.amount, c.fee, c.customId, if (c.status == "PENDING") "OTHER" else null, c.createdAt, c.authorizationId)
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
