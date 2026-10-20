package mutualstake.funding

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
import mutualstake.settlement.SettlementService
import mutualstake.ledger.LedgerRepository
import mutualstake.outbox.OutboxRepository
import mutualstake.outbox.OutboxType
import mutualstake.party.PartyRepository
import mutualstake.paypal.PayPalAuthorization
import mutualstake.paypal.PayPalOrder
import mutualstake.verifier.VerifierRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * The two-order funding flow (US-2, US-7, US-18). Every method that applies a PayPal result locks the
 * contract row first and the hold second, so two results arriving together are applied one at a time
 * and the second one sees the first: that is what lets the contract reach BOTH_HELD exactly once.
 */
@Service
class FundingService(
    private val contracts: ContractRepository,
    private val holds: HoldRepository,
    private val parties: PartyRepository,
    private val verifiers: VerifierRepository,
    private val ledger: LedgerRepository,
    private val outbox: OutboxRepository,
    private val settlement: SettlementService,
    private val clock: Clock,
) {
    /** Runs inside the transaction that locks the terms: both holds, each with its order request queued. */
    fun openHolds(contract: Contract) {
        val hash = verifiers.byId(requireNotNull(contract.verifierId))!!.sha256
        val fee = holds.insert(contract.id, HoldSide.BUYER_FEE, contract.buyerId, requireNotNull(contract.fee), hash)
        val bond = holds.insert(contract.id, HoldSide.SELLER_BOND, contract.sellerId, requireNotNull(contract.bond), hash)
        outbox.enqueue(fee.id, OutboxType.CREATE_ORDER)
        outbox.enqueue(bond.id, OutboxType.CREATE_ORDER)
    }

    @Transactional
    fun recordOrderCreated(holdId: UUID, order: PayPalOrder) {
        val hold = holds.lockForUpdate(holdId)
        when {
            hold.state == HoldState.PENDING && order.customId != hold.customId ->
                holds.flag(holdId, "custom_id_mismatch: order ${order.id} carries ${order.customId}, not the verifier hash")
            hold.state == HoldState.PENDING ->
                holds.transition(holdId, HoldState.PENDING, HoldState.ORDER_CREATED, mapOf("paypal_order_id" to order.id, "approve_url" to order.approveUrl))
            // Created after the deal was cancelled. Nobody will approve it; PayPal lets it expire.
            hold.state == HoldState.ABANDONED && hold.paypalOrderId == null ->
                holds.transition(holdId, HoldState.ABANDONED, HoldState.ABANDONED, mapOf("paypal_order_id" to order.id))
            else -> Unit // a replayed create: already recorded
        }
    }

    /** Reconciles a hold with PayPal's own view of its order: approval, and any authorization we missed. */
    @Transactional
    fun recordOrderState(holdId: UUID, order: PayPalOrder) {
        val contract = lockContractOf(holdId)
        var hold = holds.lockForUpdate(holdId)
        if (hold.state == HoldState.ORDER_CREATED && order.status in APPROVED_STATUSES) {
            val payer = parties.byId(hold.payerId)
            // A bond the buyer paid would be no stake at all (US-7): only the named payer may approve.
            if (!payer.paypalEmail.equals(order.payerEmail, ignoreCase = true)) {
                holds.flag(holdId, "wrong_payer: the ${hold.side.label} must be approved from ${payer.handle}'s PayPal account; another account approved it")
                return
            }
            holds.transition(holdId, HoldState.ORDER_CREATED, HoldState.APPROVED)
            hold = holds.lockForUpdate(holdId)
            if (order.authorization == null) outbox.enqueue(holdId, OutboxType.AUTHORIZE)
        }
        order.authorization?.let { applyAuthorization(contract, hold, it) }
    }

    @Transactional
    fun recordAuthorization(holdId: UUID, authorization: PayPalAuthorization) {
        val contract = lockContractOf(holdId)
        applyAuthorization(contract, holds.lockForUpdate(holdId), authorization)
    }

    /** Checked before calling PayPal: a deal that has moved on never gets a new hold. */
    @Transactional
    fun abandonIfDealMovedOn(holdId: UUID): Boolean {
        val contract = lockContractOf(holdId)
        val hold = holds.lockForUpdate(holdId)
        if (hold.state != HoldState.APPROVED || contract.state == ContractState.TERMS_LOCKED) return false
        holds.transition(holdId, HoldState.APPROVED, HoldState.ABANDONED)
        return true
    }

    /** TERMS_LOCKED -> CANCELLED: whatever is held is voided; whatever was never held is abandoned. */
    @Transactional
    fun cancel(contractId: UUID, cause: String) {
        val contract = contracts.lockForUpdate(contractId) ?: throw ApiException.notFound("contract $contractId")
        if (contract.state != ContractState.TERMS_LOCKED) {
            throw ApiException.conflict("not_cancellable", "only a deal still waiting on its holds can be cancelled; this one is ${contract.state}")
        }
        contracts.transition(contractId, ContractState.TERMS_LOCKED, ContractState.CANCELLED, cause)
        for (hold in holds.forContractForUpdate(contractId)) {
            when (hold.state) {
                HoldState.PENDING, HoldState.ORDER_CREATED, HoldState.APPROVED -> holds.transition(hold.id, hold.state, HoldState.ABANDONED)
                HoldState.HELD -> {
                    holds.transition(hold.id, HoldState.HELD, HoldState.VOID_REQUESTED)
                    outbox.enqueue(hold.id, OutboxType.VOID)
                }
                else -> Unit
            }
        }
    }

    /** BOTH_HELD past the deadline with nothing delivered: a no-show, which settles like a FAIL (US-4). */
    @Transactional
    fun expire(contractId: UUID) {
        val contract = contracts.lockForUpdate(contractId) ?: return
        val deadline = contract.deadlineAt ?: return
        if (contract.state == ContractState.BOTH_HELD && deadline.isBefore(clock.instant())) {
            contracts.transition(contractId, ContractState.BOTH_HELD, ContractState.EXPIRED, "deadline passed with no delivery")
            settlement.plan(contractId)
        }
    }

    @Transactional
    fun repairBothHeld(contractId: UUID) {
        contracts.lockForUpdate(contractId)?.let(::promoteIfBothHeld)
    }

    @Transactional
    fun flag(holdId: UUID, reason: String) {
        holds.flag(holdId, reason)
    }

    private fun applyAuthorization(contract: Contract, hold: Hold, authorization: PayPalAuthorization) {
        if (hold.state != HoldState.APPROVED && hold.state != HoldState.ABANDONED) return // already applied
        if (authorization.status != CREATED) {
            holds.flag(hold.id, "authorization ${authorization.id} is ${authorization.status}, not CREATED")
            return
        }
        // The money is held at PayPal now, whatever we decide next: the ledger says so first.
        ledger.record(
            LedgerEntry(
                UUID.randomUUID(), contract.id, hold.id, LedgerEvent.HOLD_AUTHORIZED, authorization.amount ?: hold.amount,
                parties.byId(hold.payerId).handle, authorization.id, authorization.createdAt ?: clock.instant(),
            ),
        )
        val ids = mapOf("authorization_id" to authorization.id, "authorization_expires_at" to authorization.expiresAt)
        val hashMatches = authorization.customId == null || authorization.customId == hold.customId
        if (!hashMatches) holds.flag(hold.id, "custom_id_mismatch: authorization ${authorization.id} carries ${authorization.customId}")
        if (hold.state == HoldState.APPROVED && contract.state == ContractState.TERMS_LOCKED && hashMatches) {
            holds.transition(hold.id, HoldState.APPROVED, HoldState.HELD, ids)
            promoteIfBothHeld(contract)
        } else {
            // A late hold on a deal that moved on (or carries the wrong hash): release it.
            holds.transition(hold.id, hold.state, HoldState.VOID_REQUESTED, ids)
            outbox.enqueue(hold.id, OutboxType.VOID)
        }
    }

    private fun promoteIfBothHeld(contract: Contract) {
        if (contract.state != ContractState.TERMS_LOCKED) return
        val both = holds.forContract(contract.id)
        if (both.size == 2 && both.all { it.state == HoldState.HELD }) {
            contracts.transition(contract.id, ContractState.TERMS_LOCKED, ContractState.BOTH_HELD, "paypal: both authorizations CREATED")
        }
    }

    private fun lockContractOf(holdId: UUID): Contract {
        val contractId = requireNotNull(holds.find(holdId)) { "no hold $holdId" }.contractId
        return requireNotNull(contracts.lockForUpdate(contractId))
    }

    companion object {
        private const val CREATED = "CREATED"
        private const val VOIDED = "VOIDED"

        /** After authorization PayPal reports the order COMPLETED; either way the payer has approved. */
        private val APPROVED_STATUSES = setOf("APPROVED", "COMPLETED")
    }
}
