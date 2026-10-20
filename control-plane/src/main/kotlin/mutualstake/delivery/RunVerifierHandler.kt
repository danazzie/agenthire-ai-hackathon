package mutualstake.delivery

import mutualstake.contract.ContractRepository
import mutualstake.contract.ContractState
import mutualstake.crypto.Sha256
import mutualstake.hold.HoldRepository
import mutualstake.outbox.OutboxEvent
import mutualstake.outbox.OutboxHandler
import mutualstake.outbox.OutboxType
import mutualstake.verifier.RunOutcome
import mutualstake.verifier.VerifierInconclusiveException
import mutualstake.verifier.VerifierRefusedException
import mutualstake.verifier.VerifierRepository
import mutualstake.verifier.VerifierRunner
import org.springframework.stereotype.Component

/**
 * Runs the agreed verifier on the delivered bytes. It refuses to run anything but the program both PayPal
 * orders committed to (US-18). A run with no verdict is retried and then flagged; it is never a FAIL.
 */
@Component
class RunVerifierHandler(
    private val deliveries: DeliveryRepository,
    private val contracts: ContractRepository,
    private val holds: HoldRepository,
    private val verifiers: VerifierRepository,
    private val blobs: BlobStore,
    private val runner: VerifierRunner,
    private val service: DeliveryService,
) : OutboxHandler {
    override val type = OutboxType.RUN_VERIFIER

    override fun handle(event: OutboxEvent) {
        val delivery = deliveries.find(event.aggregateId) ?: return
        if (delivery.verdict != null) return
        val contract = contracts.find(delivery.contractId) ?: return
        if (contract.state != ContractState.DELIVERED) return
        val verifier = verifiers.byId(requireNotNull(contract.verifierId)) ?: throw VerifierRefusedException("verifier ${contract.verifierId} not found")

        val sourceHash = Sha256.hex(verifier.source)
        val committed = holds.forContract(contract.id).map { it.customId }.toSet()
        if (sourceHash != verifier.sha256 || committed != setOf(sourceHash)) {
            throw VerifierRefusedException("hash_mismatch: the verifier hashes to $sourceHash, the PayPal orders carry ${committed.joinToString()}")
        }

        val run = runner.run(verifier.source, blobs.bytes(delivery.payloadSha256).toString(Charsets.UTF_8))
        if (run.sourceSha256 != sourceHash) throw VerifierRefusedException("hash_mismatch: the runtime received ${run.sourceSha256}")
        if (run.outcome == RunOutcome.ERROR) {
            service.recordInconclusive(delivery.id, verifier.id, run)
            throw VerifierInconclusiveException("no_verdict: ${run.error}")
        }
        service.recordVerdict(delivery.id, verifier.id, run)
    }

    override fun onDead(event: OutboxEvent, reason: String) = service.flag(event.aggregateId, reason)
}
