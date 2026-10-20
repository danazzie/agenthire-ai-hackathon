package mutualstake.delivery

import mutualstake.api.ApiException
import mutualstake.config.MutualStakeProperties
import mutualstake.contract.ContractRepository
import mutualstake.contract.ContractState
import mutualstake.outbox.OutboxRepository
import mutualstake.outbox.OutboxType
import mutualstake.party.Party
import mutualstake.verifier.RunOutcome
import mutualstake.verifier.VerifierRunResult
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * The seller's delivery, and the verifier's verdict on it. Like funding, every step locks the contract row
 * first: the deadline sweep and a late delivery cannot both win.
 */
@Service
class DeliveryService(
    private val contracts: ContractRepository,
    private val deliveries: DeliveryRepository,
    private val blobs: BlobStore,
    private val runs: VerifierRunLog,
    private val outbox: OutboxRepository,
    private val props: MutualStakeProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun submit(contractId: UUID, party: Party, text: String): Delivery {
        val contract = contracts.lockForUpdate(contractId) ?: throw ApiException.notFound("contract $contractId")
        if (party.id != contract.sellerId) throw ApiException(HttpStatus.FORBIDDEN, "not_the_seller", "only the seller delivers")
        deliveries.forContract(contractId)?.let {
            throw ApiException.conflict("already_delivered", "this deal already has its delivery (${it.submittedAt}); a second one is not accepted")
        }
        if (contract.state != ContractState.BOTH_HELD) {
            throw ApiException.conflict("not_funded", "deliver once both holds are in place; this deal is ${contract.state}")
        }
        if (!clock.instant().isBefore(requireNotNull(contract.deadlineAt))) {
            throw ApiException.conflict("deadline_passed", "the deadline (${contract.deadlineAt}) has passed")
        }
        if (text.isBlank()) throw ApiException.unprocessable("empty_delivery", "the delivery is empty")
        if (text.length > props.verifier.maxDeliveryChars) {
            throw ApiException.unprocessable("delivery_too_large", "a delivery is at most ${props.verifier.maxDeliveryChars} characters")
        }
        // The exact bytes are stored and hashed; these bytes, decoded, are what the verifier judges.
        val bytes = text.toByteArray(Charsets.UTF_8)
        val sha = blobs.put(bytes, "text/plain; charset=utf-8")
        val flags = InjectionFlagger.flags(text)
        if (flags.isNotEmpty()) {
            log.warn("US-20: injection attempt in the delivery for contract {}: {}. It is data; the verdict comes from the verifier's code.", contractId, flags)
        }
        val delivery = deliveries.insert(contractId, sha, flags)
        contracts.transition(contractId, ContractState.BOTH_HELD, ContractState.DELIVERED, "seller delivered ${bytes.size} bytes, sha256 ${sha.take(12)}…")
        outbox.enqueue(delivery.id, OutboxType.RUN_VERIFIER)
        return delivery
    }

    /** Only a PASS or a FAIL is a verdict. Returns false if one was already recorded. Settlement on it is step 5. */
    @Transactional
    fun recordVerdict(deliveryId: UUID, verifierId: UUID, run: VerifierRunResult): Boolean {
        require(run.outcome != RunOutcome.ERROR) { "an ERROR is not a verdict" }
        val delivery = deliveries.find(deliveryId) ?: return false
        val contract = contracts.lockForUpdate(delivery.contractId) ?: return false
        if (contract.state != ContractState.DELIVERED || delivery.verdict != null) return false
        val runId = runs.record(verifierId, deliveryId, RunKind.DELIVERY, delivery.payloadSha256, delivery.injectionFlags, run)
        deliveries.setVerdict(deliveryId, run.outcome.name, run.failingCheck, "verifier_run:$runId")
        val next = if (run.outcome == RunOutcome.PASS) ContractState.PASS else ContractState.FAIL
        contracts.transition(contract.id, ContractState.DELIVERED, next, "verifier: ${run.summary}")
        return true
    }

    /** A run that gave no verdict is still recorded, so the attempt is visible; nothing else changes. */
    @Transactional
    fun recordInconclusive(deliveryId: UUID, verifierId: UUID, run: VerifierRunResult) {
        val delivery = deliveries.find(deliveryId) ?: return
        runs.record(verifierId, deliveryId, RunKind.DELIVERY, delivery.payloadSha256, delivery.injectionFlags, run)
    }

    @Transactional
    fun flag(deliveryId: UUID, reason: String) {
        deliveries.flag(deliveryId, reason)
    }
}
