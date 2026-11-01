package mutualstake.settlement

import mutualstake.contract.ContractRepository
import mutualstake.funding.FundingService
import mutualstake.hold.Hold
import mutualstake.hold.HoldRepository
import mutualstake.hold.HoldSide
import mutualstake.hold.HoldState
import mutualstake.outbox.ClassifiedFailure
import mutualstake.outbox.OutboxEvent
import mutualstake.outbox.OutboxHandler
import mutualstake.outbox.OutboxRepository
import mutualstake.outbox.OutboxType
import mutualstake.outbox.StillPendingException
import mutualstake.party.PartyRepository
import mutualstake.paypal.PayPalException
import mutualstake.paypal.PayPalGateway
import mutualstake.paypal.PayoutRequest
import org.springframework.stereotype.Component

/** A settlement step that waiting will not fix. Flagged on the hold; never silently retried with new ids. */
class SettlementException(override val reason: String) : RuntimeException(reason), ClassifiedFailure {
    override val retryable = false
}

/** Each handler re-checks that the deal is still settling before calling PayPal, so a later appeal can freeze it. */
private fun settling(contracts: ContractRepository, hold: Hold) = contracts.find(hold.contractId)?.state in SettlementService.SETTLING

@Component
class CaptureHandler(
    private val holds: HoldRepository,
    private val contracts: ContractRepository,
    private val outbox: OutboxRepository,
    private val paypal: PayPalGateway,
    private val settlement: SettlementService,
    private val funding: FundingService,
) : OutboxHandler {
    override val type = OutboxType.CAPTURE

    override fun handle(event: OutboxEvent) {
        val hold = holds.find(event.aggregateId) ?: return
        if (hold.state != HoldState.CAPTURE_REQUESTED) return
        if (hold.captureId != null) {
            outbox.enqueue(hold.id, OutboxType.CHECK_CAPTURE)
            return
        }
        if (!settling(contracts, hold)) return
        val capture = try {
            paypal.captureAuthorization(requireNotNull(hold.authorizationId), hold.requestId("capture"), hold.id)
        } catch (e: PayPalException) {
            // An earlier attempt captured but its answer was lost: read the capture off the order.
            if (e.issue != "AUTHORIZATION_ALREADY_CAPTURED") throw e
            paypal.getOrder(requireNotNull(hold.paypalOrderId), hold.id).capture ?: throw e
        }
        settlement.recordCapture(hold.id, capture)
    }

    override fun onDead(event: OutboxEvent, reason: String) = funding.flag(event.aggregateId, reason)
}

/** Follows a capture PayPal has not finished. Polled, not flagged, until it completes or PayPal declines it. */
@Component
class CheckCaptureHandler(
    private val holds: HoldRepository,
    private val paypal: PayPalGateway,
    private val settlement: SettlementService,
    private val funding: FundingService,
) : OutboxHandler {
    override val type = OutboxType.CHECK_CAPTURE

    override fun handle(event: OutboxEvent) {
        val hold = holds.find(event.aggregateId) ?: return
        val captureId = hold.captureId ?: return
        if (hold.state != HoldState.CAPTURE_REQUESTED) return
        val capture = paypal.getCapture(captureId, hold.id)
        when (capture.status) {
            "COMPLETED" -> settlement.recordCapture(hold.id, capture)
            "PENDING" -> throw StillPendingException("capture $captureId is PENDING${capture.statusReason?.let { " ($it)" }.orEmpty()}; no payout until it completes")
            else -> throw SettlementException("capture $captureId is ${capture.status}; nothing was paid out")
        }
    }

    override fun onDead(event: OutboxEvent, reason: String) = funding.flag(event.aggregateId, reason)
}

/** Pays the captured amount out (US-4, US-10): the fee to the seller, or the bond to the buyer. */
@Component
class PayoutHandler(
    private val holds: HoldRepository,
    private val contracts: ContractRepository,
    private val parties: PartyRepository,
    private val paypal: PayPalGateway,
    private val settlement: SettlementService,
    private val funding: FundingService,
) : OutboxHandler {
    override val type = OutboxType.PAYOUT

    override fun handle(event: OutboxEvent) {
        val hold = holds.find(event.aggregateId) ?: return
        if (hold.state != HoldState.PAYOUT_REQUESTED) return
        hold.payoutBatchId?.let { return settlement.recordPayoutCreated(hold.id, it) }
        if (!settling(contracts, hold)) return
        val senderBatchId = requireNotNull(hold.senderBatchId)
        val recipient = parties.byId(requireNotNull(hold.payoutRecipientId))
        val fee = hold.side == HoldSide.BUYER_FEE
        val request = PayoutRequest(
            holdId = hold.id,
            requestId = hold.requestId("payout"),
            senderBatchId = senderBatchId,
            receiverEmail = recipient.paypalEmail,
            // US-17: exactly what PayPal captured, read back from the capture.
            amount = requireNotNull(hold.captured) { "no captured amount on ${hold.id}" },
            subject = if (fee) "HireMind: you have been paid" else "HireMind: the seller bond is yours",
            message = if (fee) {
                "Your delivery passed its agreed test, so the buyer's fee was captured and paid to you. Sandbox only."
            } else {
                "The delivery failed its agreed test or never came, so the seller's bond was captured and paid to you. Sandbox only."
            },
            note = if (fee) "Fee for work that passed its agreed test (HireMind sandbox)." else "Seller bond, paid to you as compensation (HireMind sandbox).",
        )
        val batchId = try {
            paypal.createPayout(request).batchId
        } catch (e: PayPalException) {
            // A retry after a lost answer: PayPal refuses the duplicate sender_batch_id and links the batch it already has.
            if (e.name != "USER_BUSINESS_ERROR" || e.field != "SENDER_BATCH_ID") throw e
            e.links.firstNotNullOfOrNull { BATCH_LINK.find(it)?.groupValues?.get(1) }
                ?: throw SettlementException("duplicate_payout: a batch with sender_batch_id $senderBatchId exists but PayPal did not link it; check the sandbox dashboard before doing anything (debug_id ${e.debugId})")
        }
        settlement.recordPayoutCreated(hold.id, batchId)
    }

    override fun onDead(event: OutboxEvent, reason: String) = funding.flag(event.aggregateId, reason)

    companion object {
        private val BATCH_LINK = Regex("/v1/payments/payouts/([A-Z0-9]+)")
    }
}

/** Follows a payout until PayPal reports the item paid. Polled every few seconds; flagged only if it never lands. */
@Component
class CheckPayoutHandler(
    private val holds: HoldRepository,
    private val paypal: PayPalGateway,
    private val settlement: SettlementService,
    private val funding: FundingService,
) : OutboxHandler {
    override val type = OutboxType.CHECK_PAYOUT

    override fun handle(event: OutboxEvent) {
        val hold = holds.find(event.aggregateId) ?: return
        val batchId = hold.payoutBatchId ?: return
        if (hold.state != HoldState.PAYOUT_REQUESTED) return
        val batch = paypal.getPayoutBatch(batchId, hold.id)
        val item = batch.items.singleOrNull() ?: throw StillPendingException("payout batch $batchId is ${batch.status}")
        when (item.status) {
            "SUCCESS" -> settlement.recordPayoutSucceeded(hold.id, batch)
            in STILL_MOVING -> throw StillPendingException("payout ${item.payoutItemId} is ${item.status}")
            else -> throw SettlementException("payout ${item.payoutItemId} is ${item.status}; the recipient was not paid")
        }
    }

    override fun onDead(event: OutboxEvent, reason: String) = funding.flag(event.aggregateId, reason)

    companion object {
        private val STILL_MOVING = setOf("PENDING", "NEW", "PROCESSING", "ONHOLD", "UNCLAIMED")
    }
}
