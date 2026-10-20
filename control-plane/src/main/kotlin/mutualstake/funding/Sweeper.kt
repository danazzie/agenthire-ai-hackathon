package mutualstake.funding

import mutualstake.config.MutualStakeProperties
import mutualstake.contract.ContractRepository
import mutualstake.hold.HoldRepository
import mutualstake.outbox.OutboxRepository
import mutualstake.outbox.OutboxType
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

/** Deadlines, repairs, and the polling fallback for approvals when no webhook arrives (US-30). */
@Component
class Sweeper(
    private val contracts: ContractRepository,
    private val holds: HoldRepository,
    private val funding: FundingService,
    private val outbox: OutboxRepository,
    private val props: MutualStakeProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${mutualstake.scheduling.sweep-ms:3000}")
    fun sweep() {
        val now = clock.instant()
        for (id in contracts.termsLockedPastDeadline(now)) safely("cancel $id") { funding.cancel(id, "deadline passed before both holds were placed") }
        for (id in contracts.heldPastDeadlineWithoutDelivery(now)) safely("expire $id") { funding.expire(id) }
        for (id in contracts.termsLockedWithBothHeld()) safely("repair $id") { funding.repairBothHeld(id) }
        // With webhooks configured the poll is only a backstop, so it starts at the middle tier.
        val first = if (props.paypal.webhooksConfigured) maxOf(15L, props.scheduling.orderPollSeconds) else props.scheduling.orderPollSeconds
        for (id in holds.awaitingApproval(now, first)) outbox.enqueue(id, OutboxType.CHECK_ORDER)
    }

    private fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.warn("sweep: {} failed: {}", what, e.message)
        }
    }
}
