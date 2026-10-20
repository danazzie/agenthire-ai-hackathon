package mutualstake.funding

import mutualstake.config.MutualStakeProperties
import mutualstake.hold.HoldRepository
import mutualstake.hold.HoldSide
import mutualstake.hold.HoldState
import mutualstake.outbox.OutboxEvent
import mutualstake.outbox.OutboxHandler
import mutualstake.outbox.OutboxType
import mutualstake.paypal.CreateOrderRequest
import mutualstake.paypal.PayPalException
import mutualstake.paypal.PayPalGateway
import org.springframework.stereotype.Component

/** Creates the AUTHORIZE order for a hold, the verifier hash already on it as custom_id (US-18). */
@Component
class CreateOrderHandler(
    private val holds: HoldRepository,
    private val paypal: PayPalGateway,
    private val funding: FundingService,
    private val props: MutualStakeProperties,
) : OutboxHandler {
    override val type = OutboxType.CREATE_ORDER

    override fun handle(event: OutboxEvent) {
        val hold = holds.find(event.aggregateId) ?: return
        if (hold.state != HoldState.PENDING) return
        val order = paypal.createOrder(
            CreateOrderRequest(
                holdId = hold.id,
                requestId = hold.requestId("create"),
                referenceId = hold.side.name,
                description = when (hold.side) {
                    HoldSide.BUYER_FEE -> "Mutual Stake buyer fee: held, not charged, until the agreed test decides"
                    HoldSide.SELLER_BOND -> "Mutual Stake seller bond: held, not charged, unless the work fails its test"
                },
                amount = hold.amount,
                customId = hold.customId,
                returnUrl = "${props.publicUrl}/paypal/return?hold=${hold.id}",
                cancelUrl = "${props.publicUrl}/paypal/cancel?hold=${hold.id}",
            ),
        )
        funding.recordOrderCreated(hold.id, order)
    }
}

/** Reads the order back from PayPal: the webhook, the poll and the return-page nudge all land here. */
@Component
class CheckOrderHandler(private val holds: HoldRepository, private val paypal: PayPalGateway, private val funding: FundingService) : OutboxHandler {
    override val type = OutboxType.CHECK_ORDER

    override fun handle(event: OutboxEvent) {
        val hold = holds.find(event.aggregateId) ?: return
        val orderId = hold.paypalOrderId ?: return
        if (hold.state != HoldState.ORDER_CREATED && hold.state != HoldState.APPROVED) return
        funding.recordOrderState(hold.id, paypal.getOrder(orderId, hold.id))
    }
}

/** Places the hold. A replay or a second signal that finds it already placed recovers the authorization. */
@Component
class AuthorizeHandler(private val holds: HoldRepository, private val paypal: PayPalGateway, private val funding: FundingService) : OutboxHandler {
    override val type = OutboxType.AUTHORIZE

    override fun handle(event: OutboxEvent) {
        val hold = holds.find(event.aggregateId) ?: return
        if (hold.state != HoldState.APPROVED) return
        if (funding.abandonIfDealMovedOn(hold.id)) return
        val orderId = requireNotNull(hold.paypalOrderId)
        val authorization = try {
            paypal.authorizeOrder(orderId, hold.requestId("authorize"), hold.id).authorization
        } catch (e: PayPalException) {
            if (e.issue != "ORDER_ALREADY_AUTHORIZED") throw e
            paypal.getOrder(orderId, hold.id).authorization
        } ?: throw IllegalStateException("PayPal returned no authorization for order $orderId")
        funding.recordAuthorization(hold.id, authorization)
    }
}

/** Releases a hold. Void is not a refund: nothing was taken. */
@Component
class VoidHandler(private val holds: HoldRepository, private val paypal: PayPalGateway, private val funding: FundingService) : OutboxHandler {
    override val type = OutboxType.VOID

    override fun handle(event: OutboxEvent) {
        val hold = holds.find(event.aggregateId) ?: return
        if (hold.state != HoldState.VOID_REQUESTED) return
        val authorizationId = requireNotNull(hold.authorizationId)
        val authorization = try {
            paypal.voidAuthorization(authorizationId, hold.requestId("void"), hold.id)
        } catch (e: PayPalException) {
            if (e.issue != "AUTHORIZATION_ALREADY_VOIDED") throw e
            paypal.getAuthorization(authorizationId, hold.id)
        }
        check(authorization.status == "VOIDED") { "void of $authorizationId left it ${authorization.status}" }
        funding.recordVoided(hold.id, authorization)
    }
}
