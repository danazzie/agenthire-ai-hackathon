package mutualstake.settlement

import mutualstake.api.ApiException
import mutualstake.contract.Contract
import mutualstake.contract.ContractRepository
import mutualstake.contract.ContractState
import mutualstake.hold.Hold
import mutualstake.hold.HoldRepository
import mutualstake.hold.HoldSide
import mutualstake.hold.HoldState
import mutualstake.ledger.LedgerEntry
import mutualstake.ledger.LedgerEvent
import mutualstake.ledger.LedgerRepository
import mutualstake.money.Money
import mutualstake.outbox.OutboxRepository
import mutualstake.outbox.OutboxType
import mutualstake.party.PartyRepository
import mutualstake.paypal.PayPalAuthorization
import mutualstake.paypal.PayPalCapture
import mutualstake.paypal.PayPalPayoutBatch
import mutualstake.session.Session
import mutualstake.session.SessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Settles a verdict on PayPal (US-3, US-4, US-10). The platform is payee on both orders, so each branch is
 * one capture plus a payout, and one void:
 *
 *   PASS:            capture the fee and pay it out to the seller; void the bond.
 *   FAIL or EXPIRED: void the fee; capture the bond and pay it out to the buyer.
 *
 * Only a verdict (or a no-show) starts this; there is no endpoint that captures. Every result locks the
 * contract row before the hold, so the contract reaches SETTLED exactly once, when PayPal has confirmed
 * both holds are final.
 */
@Service
class SettlementService(
    private val contracts: ContractRepository,
    private val holds: HoldRepository,
    private val sessions: SessionRepository,
    private val parties: PartyRepository,
    private val ledger: LedgerRepository,
    private val outbox: OutboxRepository,
    private val clock: Clock,
) {
    /**
     * Queues the settlement for a verdict. Runs inside the transaction that recorded it. US-16: if this is the
     * session's first capture, nothing is queued until a human confirms, not even the void, so a bad verdict
     * can never half-settle.
     */
    fun plan(contractId: UUID) {
        val contract = requireNotNull(contracts.lockForUpdate(contractId))
        if (contract.state !in SETTLING || contract.settlementPlannedAt != null) return
        val session = sessions.lockForUpdate(contract.sessionId)
        if (session.firstCaptureConfirmedAt == null) return
        execute(contract, session, confirmedByHuman = false)
    }

    /** The sweep's repair path for a verdict whose plan was not queued. */
    @Transactional
    fun planIfReady(contractId: UUID) = plan(contractId)

    /**
     * US-16's human Confirm. It can only release a settlement the verdict already decided: it never captures
     * on its own, and it is idempotent.
     */
    @Transactional
    fun confirm(contractId: UUID): Contract {
        val contract = contracts.lockForUpdate(contractId) ?: throw ApiException.notFound("contract $contractId")
        if (contract.state !in SETTLING) {
            throw ApiException.conflict("not_awaiting_confirmation", "only a deal with a verdict waits for confirmation; this one is ${contract.state}")
        }
        if (contract.settlementPlannedAt != null) return contract
        val session = sessions.lockForUpdate(contract.sessionId)
        sessions.confirmFirstCapture(session.id)
        execute(contract, sessions.lockForUpdate(session.id), confirmedByHuman = true)
        return contracts.find(contractId)!!
    }

    private fun execute(contract: Contract, session: Session, confirmedByHuman: Boolean) {
        val both = holds.forContractForUpdate(contract.id)
        val fee = both.single { it.side == HoldSide.BUYER_FEE }
        val bond = both.single { it.side == HoldSide.SELLER_BOND }
        if (fee.state != HoldState.HELD || bond.state != HoldState.HELD) {
            contracts.flag(contract.id, "settlement_blocked: the fee is ${fee.state} and the bond is ${bond.state}; both must be HELD")
            return
        }
        val pass = contract.state == ContractState.PASS
        // US-25, checked here so a block stops both legs: never a voided bond with an uncaptured fee.
        if (pass) {
            val spent = holds.capturedFeesInSession(session.id)
            if (spent + fee.amount > session.cap) {
                contracts.flag(contract.id, "mandate_exceeded: the session cap is ${session.cap} and $spent is already captured; nothing was queued")
                return
            }
        }
        val (capture, release, recipient) = if (pass) Triple(fee, bond, contract.sellerId) else Triple(bond, fee, contract.buyerId)
        holds.transition(
            capture.id, HoldState.HELD, HoldState.CAPTURE_REQUESTED,
            mapOf("payout_recipient_id" to recipient, "sender_batch_id" to "${capture.requestIdBase}-payout"),
        )
        holds.transition(release.id, HoldState.HELD, HoldState.VOID_REQUESTED)
        outbox.enqueue(capture.id, OutboxType.CAPTURE)
        outbox.enqueue(release.id, OutboxType.VOID)
        contracts.markPlanned(contract.id, confirmedByHuman)
        contracts.clearFlag(contract.id)
    }

    /**
     * A capture PayPal reported. A payout is funded from the platform's balance, so it is only sent once the
     * capture is COMPLETED; a PENDING capture is followed by reading it again, not by paying.
     */
    @Transactional
    fun recordCapture(holdId: UUID, capture: PayPalCapture) {
        val contract = lockContractOf(holdId)
        val hold = holds.lockForUpdate(holdId)
        if (hold.state != HoldState.CAPTURE_REQUESTED) return
        if (capture.status != COMPLETED) {
            holds.transition(holdId, HoldState.CAPTURE_REQUESTED, HoldState.CAPTURE_REQUESTED, mapOf("capture_id" to capture.id))
            outbox.enqueue(holdId, OutboxType.CHECK_CAPTURE)
            return
        }
        // US-17: the amount paid out later is the amount PayPal says it captured, read back, not recomputed.
        val gross = capture.amount ?: hold.amount
        holds.transition(holdId, HoldState.CAPTURE_REQUESTED, HoldState.CAPTURED, mapOf("capture_id" to capture.id, "captured_minor" to gross))
        holds.clearFlag(holdId)
        val at = capture.createdAt ?: clock.instant()
        record(hold, LedgerEvent.CAPTURE_COMPLETED, gross, parties.byId(hold.payerId).handle, capture.id, at)
        capture.paypalFee?.let { record(hold, LedgerEvent.PAYPAL_FEE, it, PAYPAL, capture.id, at) }
        if (contract.state in SETTLING) {
            holds.transition(holdId, HoldState.CAPTURED, HoldState.PAYOUT_REQUESTED)
            outbox.enqueue(holdId, OutboxType.PAYOUT)
        }
    }

    @Transactional
    fun recordPayoutCreated(holdId: UUID, batchId: String) {
        val hold = holds.lockForUpdate(holdId)
        if (hold.state != HoldState.PAYOUT_REQUESTED) return
        if (hold.payoutBatchId == null) holds.transition(holdId, HoldState.PAYOUT_REQUESTED, HoldState.PAYOUT_REQUESTED, mapOf("payout_batch_id" to batchId))
        outbox.enqueue(holdId, OutboxType.CHECK_PAYOUT)
    }

    /** The payout landed: the jar may now say Paid (fee) or Compensation (bond), and not before (US-32). */
    @Transactional
    fun recordPayoutSucceeded(holdId: UUID, batch: PayPalPayoutBatch) {
        val contract = lockContractOf(holdId)
        val hold = holds.lockForUpdate(holdId)
        if (hold.state != HoldState.PAYOUT_REQUESTED) return
        val item = batch.items.single()
        holds.transition(holdId, HoldState.PAYOUT_REQUESTED, HoldState.PAID_OUT, mapOf("payout_batch_id" to batch.batchId, "payout_item_id" to item.payoutItemId))
        holds.clearFlag(holdId)
        val at = item.processedAt ?: clock.instant()
        record(hold, LedgerEvent.PAYOUT_SUCCEEDED, item.amount ?: requireNotNull(hold.captured), parties.byId(requireNotNull(hold.payoutRecipientId)).handle, item.payoutItemId, at)
        item.fee?.let { record(hold, LedgerEvent.PAYOUT_FEE, it, PAYPAL, item.payoutItemId, at) }
        promoteIfSettled(contract)
    }

    /** A void PayPal confirmed, whether the deal was cancelled or settled. Void is not a refund: nothing was taken. */
    @Transactional
    fun recordVoided(holdId: UUID, authorization: PayPalAuthorization) {
        val contract = lockContractOf(holdId)
        val hold = holds.lockForUpdate(holdId)
        if (hold.state != HoldState.VOID_REQUESTED || authorization.status != VOIDED) return
        holds.transition(holdId, HoldState.VOID_REQUESTED, HoldState.VOIDED)
        holds.clearFlag(holdId)
        record(hold, LedgerEvent.HOLD_VOIDED, authorization.amount ?: hold.amount, parties.byId(hold.payerId).handle, authorization.id, authorization.updatedAt ?: clock.instant())
        promoteIfSettled(contract)
    }

    @Transactional
    fun repairSettled(contractId: UUID) {
        contracts.lockForUpdate(contractId)?.let(::promoteIfSettled)
    }

    private fun promoteIfSettled(contract: Contract) {
        if (contract.state !in SETTLING) return
        val both = holds.forContract(contract.id)
        val fee = both.single { it.side == HoldSide.BUYER_FEE }
        val bond = both.single { it.side == HoldSide.SELLER_BOND }
        val seller = parties.byId(contract.sellerId).handle
        val buyer = parties.byId(contract.buyerId).handle
        val (done, cause) = if (contract.state == ContractState.PASS) {
            (fee.state == HoldState.PAID_OUT && bond.state == HoldState.VOIDED) to "paypal: fee captured and paid out to $seller; bond voided"
        } else {
            val why = if (contract.state == ContractState.EXPIRED) "no delivery by the deadline" else "the delivery failed its test"
            (fee.state == HoldState.VOIDED && bond.state == HoldState.PAID_OUT) to "paypal: $why; fee voided; bond captured and paid out to $buyer"
        }
        if (done) contracts.transition(contract.id, contract.state, ContractState.SETTLED, cause)
    }

    private fun record(hold: Hold, event: LedgerEvent, amount: Money, counterparty: String, paypalRef: String, at: Instant) {
        ledger.record(LedgerEntry(UUID.randomUUID(), hold.contractId, hold.id, event, amount, counterparty, paypalRef, at))
    }

    private fun lockContractOf(holdId: UUID): Contract {
        val contractId = requireNotNull(holds.find(holdId)) { "no hold $holdId" }.contractId
        return requireNotNull(contracts.lockForUpdate(contractId))
    }

    companion object {
        val SETTLING = setOf(ContractState.PASS, ContractState.FAIL, ContractState.EXPIRED)
        private const val COMPLETED = "COMPLETED"
        private const val VOIDED = "VOIDED"
        private const val PAYPAL = "paypal"
    }
}
