package mutualstake.paypal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import mutualstake.config.MutualStakeProperties
import mutualstake.hold.HoldRepository
import mutualstake.hold.HoldState
import mutualstake.outbox.OutboxRepository
import mutualstake.outbox.OutboxType
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * Turns a verified webhook into a read of the same object from PayPal. Holds are found by ids already
 * stored: the order id for approvals, the authorization id for captures (a capture event can arrive before
 * the capture id is recorded), and the sender_batch_id for payouts.
 */
@Component
class WebhookPrompter(private val holds: HoldRepository, private val outbox: OutboxRepository) {
    fun prompt(type: String, resource: JsonNode) {
        val related = resource.path("supplementary_data").path("related_ids")
        when {
            type == "CHECKOUT.ORDER.APPROVED" ->
                holds.byOrderId(resource.path("id").asText())?.let { outbox.enqueue(it.id, OutboxType.CHECK_ORDER) }
            type == "PAYMENT.AUTHORIZATION.CREATED" ->
                holds.byOrderId(related.path("order_id").asText())?.let { outbox.enqueue(it.id, OutboxType.CHECK_ORDER) }
            type == "PAYMENT.AUTHORIZATION.VOIDED" ->
                holds.byAuthorizationId(resource.path("id").asText())?.takeIf { it.state == HoldState.VOID_REQUESTED }
                    ?.let { outbox.enqueue(it.id, OutboxType.VOID) }
            type.startsWith("PAYMENT.CAPTURE.") ->
                holds.byAuthorizationId(related.path("authorization_id").asText())?.takeIf { it.state == HoldState.CAPTURE_REQUESTED }
                    ?.let { outbox.enqueue(it.id, if (it.captureId == null) OutboxType.CAPTURE else OutboxType.CHECK_CAPTURE) }
            type.startsWith("PAYMENT.PAYOUTSBATCH.") || type.startsWith("PAYMENT.PAYOUTS-ITEM.") -> {
                val senderBatchId = resource.path("batch_header").path("sender_batch_header").path("sender_batch_id").asText(null)
                    ?: resource.path("payout_item").path("sender_item_id").asText(null)?.removeSuffix("-1")
                val batchId = resource.path("batch_header").path("payout_batch_id").asText(null) ?: resource.path("payout_batch_id").asText(null)
                val hold = senderBatchId?.let(holds::bySenderBatchId) ?: batchId?.let(holds::byPayoutBatchId)
                hold?.takeIf { it.state == HoldState.PAYOUT_REQUESTED }?.let { outbox.enqueue(it.id, if (it.payoutBatchId == null) OutboxType.PAYOUT else OutboxType.CHECK_PAYOUT) }
            }
        }
    }
}

@Repository
class WebhookEventStore(private val jdbc: JdbcClient) {
    fun processed(id: String): Boolean =
        jdbc.sql("select processed_at is not null from paypal_webhook_event where id = :id").param("id", id)
            .query(Boolean::class.java).optional().orElse(false)

    fun save(id: String, type: String, resourceType: String?, resourceId: String?, verified: Boolean, raw: String) {
        jdbc.sql(
            """
            insert into paypal_webhook_event (id, event_type, resource_type, resource_id, verified, payload)
            values (:id, :type, :rtype, :rid, :verified, cast(:payload as jsonb))
            on conflict (id) do update set verified = paypal_webhook_event.verified or excluded.verified
            """,
        ).param("id", id).param("type", type).param("rtype", resourceType).param("rid", resourceId)
            .param("verified", verified).param("payload", raw).update()
    }

    fun markProcessed(id: String) {
        jdbc.sql("update paypal_webhook_event set processed_at = now() where id = :id").param("id", id).update()
    }
}

/**
 * Signed PayPal webhooks (US-30). An event is only a prompt: it queues a read of the same object from
 * PayPal, and that read is what moves a hold. Unverified events are stored and ignored.
 */
@RestController
class PayPalWebhookController(
    private val paypal: PayPalGateway,
    private val props: MutualStakeProperties,
    private val store: WebhookEventStore,
    private val prompter: WebhookPrompter,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/paypal/webhooks")
    fun receive(@RequestHeader headers: HttpHeaders, @RequestBody raw: String): ResponseEntity<Map<String, Any>> {
        if (!props.paypal.webhooksConfigured) {
            return ResponseEntity.status(503).body(mapOf("code" to "webhooks_not_configured", "detail" to "PAYPAL_WEBHOOK_ID is not set; holds are confirmed by polling"))
        }
        val event = mapper.readTree(raw)
        val id = event.path("id").asText()
        val type = event.path("event_type").asText()
        if (id.isBlank() || type.isBlank()) return ResponseEntity.badRequest().body(mapOf("code" to "invalid_event"))
        if (store.processed(id)) return ResponseEntity.ok(mapOf("duplicate" to true))
        val verified = try {
            paypal.verifyWebhook(headers.toSingleValueMap(), raw)
        } catch (e: PayPalException) {
            log.warn("webhook {} could not be verified: {}", id, e.reason)
            false
        }
        val resource = event.path("resource")
        store.save(id, type, event.path("resource_type").textOrNull(), resource.path("id").textOrNull(), verified, raw)
        if (!verified) return ResponseEntity.badRequest().body(mapOf("code" to "signature_invalid"))
        prompt(type, resource)
        store.markProcessed(id)
        return ResponseEntity.ok(mapOf("ok" to true))
    }

    private fun prompt(type: String, resource: JsonNode) {
        prompter.prompt(type, resource)
    }

    private fun JsonNode.textOrNull(): String? = if (isMissingNode || isNull) null else asText()
}
