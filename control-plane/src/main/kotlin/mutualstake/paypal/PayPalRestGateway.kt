package mutualstake.paypal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import mutualstake.config.MutualStakeProperties
import mutualstake.money.CURRENCY
import mutualstake.money.Money
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * PayPal sandbox over REST, shaped by what the US-4 spike observed (docs/spikes/US-4-findings.md):
 * order create answers 200 PAYER_ACTION_REQUIRED with a `payer-action` link; replays with the same
 * PayPal-Request-Id return the original result. Sandbox only: any other host is refused at startup.
 */
@Component
class PayPalRestGateway(
    builder: RestClient.Builder,
    private val props: MutualStakeProperties,
    private val calls: PayPalCallRecorder,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) : PayPalGateway {
    private val log = LoggerFactory.getLogger(javaClass)
    private val paypal = props.paypal
    private val http: RestClient

    init {
        require(URI(paypal.baseUrl).host == SANDBOX_HOST) { "sandbox only: refusing PayPal base URL ${paypal.baseUrl}" }
        http = builder.baseUrl(paypal.baseUrl).build()
    }

    @Volatile
    private var token: Pair<String, Instant>? = null

    override val configured: Boolean get() = paypal.configured

    override fun createOrder(request: CreateOrderRequest): PayPalOrder {
        val body = mapOf(
            "intent" to "AUTHORIZE",
            "purchase_units" to listOf(
                mapOf(
                    "reference_id" to request.referenceId,
                    "description" to request.description.take(127),
                    "custom_id" to request.customId,
                    "amount" to mapOf("currency_code" to CURRENCY, "value" to request.amount.toPayPal()),
                ),
            ),
            "payment_source" to mapOf(
                "paypal" to mapOf(
                    "experience_context" to mapOf(
                        "brand_name" to paypal.brandName,
                        "shipping_preference" to "NO_SHIPPING",
                        "user_action" to "CONTINUE",
                        "landing_page" to "LOGIN",
                        "return_url" to request.returnUrl,
                        "cancel_url" to request.cancelUrl,
                    ),
                ),
            ),
        )
        return order(call("create_order", HttpMethod.POST, "/v2/checkout/orders", request.holdId, request.requestId, body))
    }

    override fun getOrder(orderId: String, holdId: UUID?): PayPalOrder =
        order(call("get_order", HttpMethod.GET, "/v2/checkout/orders/$orderId", holdId, null, null))

    override fun authorizeOrder(orderId: String, requestId: String, holdId: UUID): PayPalOrder =
        order(call("authorize_order", HttpMethod.POST, "/v2/checkout/orders/$orderId/authorize", holdId, requestId, emptyMap<String, Any>()))

    override fun getAuthorization(authorizationId: String, holdId: UUID?): PayPalAuthorization =
        authorization(call("get_authorization", HttpMethod.GET, "/v2/payments/authorizations/$authorizationId", holdId, null, null))

    override fun voidAuthorization(authorizationId: String, requestId: String, holdId: UUID): PayPalAuthorization {
        val node = call("void_authorization", HttpMethod.POST, "/v2/payments/authorizations/$authorizationId/void", holdId, requestId, null)
        // With Prefer: return=representation PayPal answers with the authorization; a bare 204 needs a read.
        return if (node.has("status")) authorization(node) else getAuthorization(authorizationId, holdId)
    }

    override fun captureAuthorization(authorizationId: String, requestId: String, holdId: UUID): PayPalCapture =
        capture(call("capture_authorization", HttpMethod.POST, "/v2/payments/authorizations/$authorizationId/capture", holdId, requestId, mapOf("final_capture" to true)))

    override fun getCapture(captureId: String, holdId: UUID?): PayPalCapture =
        capture(call("get_capture", HttpMethod.GET, "/v2/payments/captures/$captureId", holdId, null, null))

    override fun createPayout(request: PayoutRequest): PayPalPayoutBatch {
        val body = mapOf(
            "sender_batch_header" to mapOf(
                "sender_batch_id" to request.senderBatchId,
                "email_subject" to request.subject,
                "email_message" to request.message,
            ),
            // Payouts v1 says amount.currency, not currency_code (seen in the US-4 spike).
            "items" to listOf(
                mapOf(
                    "recipient_type" to "EMAIL",
                    "receiver" to request.receiverEmail,
                    "amount" to mapOf("value" to request.amount.toPayPal(), "currency" to CURRENCY),
                    "sender_item_id" to "${request.senderBatchId}-1",
                    "note" to request.note,
                ),
            ),
        )
        return batch(call("create_payout", HttpMethod.POST, "/v1/payments/payouts", request.holdId, request.requestId, body))
    }

    override fun getPayoutBatch(batchId: String, holdId: UUID?): PayPalPayoutBatch =
        batch(call("get_payout_batch", HttpMethod.GET, "/v1/payments/payouts/$batchId", holdId, null, null))

    override fun verifyWebhook(headers: Map<String, String>, rawBody: String): Boolean {
        val h = headers.mapKeys { it.key.lowercase() }
        fun q(value: String?) = mapper.writeValueAsString(value)
        // The event goes in byte for byte: re-serialising it can break PayPal's signature check.
        val body = """{"auth_algo":${q(h["paypal-auth-algo"])},"cert_url":${q(h["paypal-cert-url"])},""" +
            """"transmission_id":${q(h["paypal-transmission-id"])},"transmission_sig":${q(h["paypal-transmission-sig"])},""" +
            """"transmission_time":${q(h["paypal-transmission-time"])},"webhook_id":${q(paypal.webhookId)},"webhook_event":$rawBody}"""
        val node = call("verify_webhook", HttpMethod.POST, "/v1/notifications/verify-webhook-signature", null, null, body)
        return node.path("verification_status").asText() == "SUCCESS"
    }

    private fun accessToken(): String {
        token?.let { (value, expires) -> if (clock.instant().isBefore(expires)) return value }
        val basic = Base64.getEncoder().encodeToString("${paypal.clientId}:${paypal.clientSecret}".toByteArray())
        val node = exchange("oauth_token", HttpMethod.POST, "/v1/oauth2/token", null, null) { spec ->
            spec.header(HttpHeaders.AUTHORIZATION, "Basic $basic")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials")
        }
        val value = node.path("access_token").asText()
        token = value to clock.instant().plusSeconds(maxOf(60L, node.path("expires_in").asLong() - 60))
        return value
    }

    private fun call(operation: String, method: HttpMethod, path: String, holdId: UUID?, requestId: String?, body: Any?): JsonNode {
        if (!configured) throw PayPalException.noCredentials(operation)
        val bearer = accessToken()
        return exchange(operation, method, path, holdId, requestId) { spec ->
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer $bearer")
                .header("Prefer", "return=representation")
                .also { s -> requestId?.let { s.header("PayPal-Request-Id", it) } }
                .also { s -> if (body != null) s.contentType(MediaType.APPLICATION_JSON).body(body) }
        }
    }

    private fun exchange(
        operation: String,
        method: HttpMethod,
        path: String,
        holdId: UUID?,
        requestId: String?,
        configure: (RestClient.RequestBodySpec) -> RestClient.RequestBodySpec,
    ): JsonNode {
        val started = System.nanoTime()
        fun elapsed() = ((System.nanoTime() - started) / 1_000_000).toInt()
        val (status, debugId, text) = try {
            requireNotNull(
                configure(http.method(method).uri(path)).exchange { _, res ->
                    Triple(res.statusCode.value(), res.headers.getFirst("paypal-debug-id"), res.body.readAllBytes().toString(Charsets.UTF_8))
                },
            )
        } catch (e: ResourceAccessException) {
            calls.record(holdId, PayPalCall(operation, method.name(), path, requestId, null, null, "paypal_unreachable", null, elapsed()))
            throw PayPalException(operation, null, "paypal_unreachable", null, null, e.message)
        }
        val node = if (text.isBlank()) mapper.createObjectNode() else mapper.readTree(text)
        val name = node.path("name").textOrNull() ?: node.path("error").textOrNull()
        val issue = node.path("details").path(0).path("issue").textOrNull()
        val field = node.path("details").path(0).path("field").textOrNull()
        val links = node.path("details").flatMap { d -> d.path("link").map { it.path("href").asText() } }
        val failed = status >= 400
        calls.record(holdId, PayPalCall(operation, method.name(), path, requestId, status, debugId, if (failed) name else null, if (failed) issue else null, elapsed()))
        if (failed) {
            log.warn("PayPal {} {} {} -> {} {} {} debug_id={}", operation, method, path, status, name, issue, debugId)
            throw PayPalException(operation, status, name, issue, debugId, node.path("message").textOrNull() ?: node.path("error_description").textOrNull(), field, links)
        }
        return node
    }

    private fun order(node: JsonNode): PayPalOrder {
        val unit = node.path("purchase_units").path(0)
        val auth = unit.path("payments").path("authorizations").path(0)
        val capture = unit.path("payments").path("captures").path(0)
        return PayPalOrder(
            id = node.path("id").asText(),
            status = node.path("status").asText(),
            customId = unit.path("custom_id").textOrNull(),
            payerEmail = node.path("payment_source").path("paypal").path("email_address").textOrNull()
                ?: node.path("payer").path("email_address").textOrNull(),
            approveUrl = node.path("links").firstOrNull { it.path("rel").asText() in APPROVE_RELS }?.path("href")?.asText(),
            authorization = if (auth.isMissingNode) null else authorization(auth),
            capture = if (capture.isMissingNode) null else capture(capture),
        )
    }

    private fun capture(node: JsonNode) = PayPalCapture(
        id = node.path("id").asText(),
        status = node.path("status").asText(),
        amount = node.path("amount").path("value").textOrNull()?.let(Money::parsePayPal),
        paypalFee = node.path("seller_receivable_breakdown").path("paypal_fee").path("value").textOrNull()?.let(Money::parsePayPal),
        customId = node.path("custom_id").textOrNull(),
        statusReason = node.path("status_details").path("reason").textOrNull(),
        createdAt = node.path("create_time").textOrNull()?.let(Instant::parse),
        authorizationId = node.path("supplementary_data").path("related_ids").path("authorization_id").textOrNull(),
    )

    private fun batch(node: JsonNode): PayPalPayoutBatch {
        val header = node.path("batch_header")
        return PayPalPayoutBatch(
            batchId = header.path("payout_batch_id").asText(),
            status = header.path("batch_status").asText(),
            senderBatchId = header.path("sender_batch_header").path("sender_batch_id").textOrNull(),
            items = node.path("items").map { item ->
                PayPalPayoutItem(
                    payoutItemId = item.path("payout_item_id").asText(),
                    transactionId = item.path("transaction_id").textOrNull(),
                    status = item.path("transaction_status").asText(),
                    amount = item.path("payout_item").path("amount").path("value").textOrNull()?.let(Money::parsePayPal),
                    fee = item.path("payout_item_fee").path("value").textOrNull()?.let(Money::parsePayPal),
                    processedAt = item.path("time_processed").textOrNull()?.let(Instant::parse),
                )
            },
        )
    }

    private fun authorization(node: JsonNode) = PayPalAuthorization(
        id = node.path("id").asText(),
        status = node.path("status").asText(),
        amount = node.path("amount").path("value").textOrNull()?.let(Money::parsePayPal),
        customId = node.path("custom_id").textOrNull(),
        expiresAt = node.path("expiration_time").textOrNull()?.let(Instant::parse),
        createdAt = node.path("create_time").textOrNull()?.let(Instant::parse),
        updatedAt = node.path("update_time").textOrNull()?.let(Instant::parse),
    )

    private fun JsonNode.textOrNull(): String? = if (isMissingNode || isNull) null else asText()

    companion object {
        const val SANDBOX_HOST = "api-m.sandbox.paypal.com"
        private val APPROVE_RELS = setOf("payer-action", "approve")
    }
}
