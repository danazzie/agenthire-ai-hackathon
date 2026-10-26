package mutualstake.ledger

import mutualstake.money.Money
import java.time.Instant
import java.util.UUID

/** Money direction from the platform's books. Holds and voids move nothing. */
enum class Direction { IN, OUT, NONE }

/** What PayPal confirmed. The event fixes the direction, so the two can never disagree. */
enum class LedgerEvent(val direction: Direction) {
    HOLD_AUTHORIZED(Direction.NONE),
    HOLD_VOIDED(Direction.NONE),
    CAPTURE_COMPLETED(Direction.IN),
    PAYPAL_FEE(Direction.OUT),
    PAYOUT_SUCCEEDED(Direction.OUT),
    PAYOUT_FEE(Direction.OUT),
}

data class LedgerEntry(
    val id: UUID,
    val contractId: UUID,
    val holdId: UUID?,
    val event: LedgerEvent,
    val amount: Money,
    /** A party handle, or "paypal" for PayPal's own fees. */
    val counterparty: String,
    /** The PayPal id that proves it: authorization, capture or payout item. */
    val paypalRef: String,
    /** When PayPal says it happened. */
    val occurredAt: Instant,
) {
    val direction: Direction get() = event.direction
}
