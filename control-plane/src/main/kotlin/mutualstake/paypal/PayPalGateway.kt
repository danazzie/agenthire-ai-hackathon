package mutualstake.paypal

import mutualstake.money.Money
import mutualstake.outbox.ClassifiedFailure
import java.time.Instant
import java.util.UUID

/**
 * The PayPal calls HireMind makes. Every money-moving call takes the hold's deterministic
 * PayPal-Request-Id (US-17). The only implementation outside tests talks to the real sandbox.
 */
interface PayPalGateway {
    val configured: Boolean

    fun createOrder(request: CreateOrderRequest): PayPalOrder

    fun getOrder(orderId: String, holdId: UUID?): PayPalOrder

    /** Places the hold. Returns the order with its authorization. */
    fun authorizeOrder(orderId: String, requestId: String, holdId: UUID): PayPalOrder

    fun getAuthorization(authorizationId: String, holdId: UUID?): PayPalAuthorization

    fun voidAuthorization(authorizationId: String, requestId: String, holdId: UUID): PayPalAuthorization

    /** Captures the whole authorization (final_capture). Lands on the platform, which is the payee. */
    fun captureAuthorization(authorizationId: String, requestId: String, holdId: UUID): PayPalCapture

    fun getCapture(captureId: String, holdId: UUID?): PayPalCapture

    /** Payouts v1. Idempotent by [PayoutRequest.senderBatchId]: PayPal refuses a duplicate rather than paying twice. */
    fun createPayout(request: PayoutRequest): PayPalPayoutBatch

    fun getPayoutBatch(batchId: String, holdId: UUID?): PayPalPayoutBatch

    /** Asks PayPal whether a webhook delivery is genuine (US-30: signature verification). */
    fun verifyWebhook(headers: Map<String, String>, rawBody: String): Boolean
}

data class CreateOrderRequest(
    val holdId: UUID,
    val requestId: String,
    /** BUYER_FEE or SELLER_BOND, as the purchase unit's reference_id. */
    val referenceId: String,
    val description: String,
    val amount: Money,
    /** The verifier SHA-256 (US-18). */
    val customId: String,
    val returnUrl: String,
    val cancelUrl: String,
)

data class PayPalOrder(
    val id: String,
    val status: String,
    val customId: String?,
    /** Who approved, once someone has. */
    val payerEmail: String?,
    val approveUrl: String?,
    val authorization: PayPalAuthorization?,
    val capture: PayPalCapture? = null,
)

data class PayPalAuthorization(
    val id: String,
    val status: String,
    val amount: Money?,
    val customId: String?,
    val expiresAt: Instant?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class PayPalCapture(
    val id: String,
    val status: String,
    val amount: Money?,
    /** PayPal's own fee on this capture, from seller_receivable_breakdown. */
    val paypalFee: Money?,
    val customId: String?,
    val statusReason: String?,
    val createdAt: Instant?,
    val authorizationId: String? = null,
)

data class PayoutRequest(
    val holdId: UUID,
    val requestId: String,
    val senderBatchId: String,
    val receiverEmail: String,
    val amount: Money,
    val subject: String,
    val message: String,
    val note: String,
)

data class PayPalPayoutItem(
    val payoutItemId: String,
    val transactionId: String?,
    val status: String,
    val amount: Money?,
    val fee: Money?,
    val processedAt: Instant?,
)

data class PayPalPayoutBatch(val batchId: String, val status: String, val senderBatchId: String?, val items: List<PayPalPayoutItem>)

/**
 * An honest failure (US-32). [reason] is what lands on a hold that needs attention, debug id included.
 * Only network failures, 5xx and 429 are worth retrying; a 4xx will not fix itself.
 */
class PayPalException(
    val operation: String,
    val httpStatus: Int?,
    val name: String?,
    val issue: String?,
    val debugId: String?,
    detail: String?,
    /** PayPal's error detail field and links, e.g. a duplicate payout's link to the batch that already exists. */
    val field: String? = null,
    val links: List<String> = emptyList(),
) : RuntimeException("PayPal $operation failed: ${httpStatus ?: "no response"} ${name.orEmpty()} ${issue.orEmpty()} ${detail.orEmpty()} (debug_id ${debugId ?: "none"})".trim()),
    ClassifiedFailure {

    override val retryable: Boolean get() = name != NO_CREDENTIALS && (httpStatus == null || httpStatus >= 500 || httpStatus == 429)

    override val reason: String
        get() = when {
            name == NO_CREDENTIALS -> NO_CREDENTIALS
            httpStatus == null -> "paypal_unreachable: $operation"
            else -> "sandbox_rejected: $operation HTTP $httpStatus ${issue ?: name ?: ""} (debug_id ${debugId ?: "none"})"
        }

    companion object {
        const val NO_CREDENTIALS = "no_credentials"

        fun noCredentials(operation: String) =
            PayPalException(operation, null, NO_CREDENTIALS, null, null, "PAYPAL_CLIENT_ID / PAYPAL_CLIENT_SECRET are not set")
    }
}
