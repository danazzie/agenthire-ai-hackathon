package mutualstake.demo

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import mutualstake.api.ApiException
import mutualstake.api.SessionView
import mutualstake.contract.ContractRepository
import mutualstake.contract.ContractState
import mutualstake.funding.FundingService
import mutualstake.session.SessionRepository
import mutualstake.session.SessionService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class ResetResult(val archivedSessionId: UUID, val session: SessionView, val cancelled: List<UUID>)

/**
 * Reset demo (US-31): void what can be voided, archive the session, open a clean one. Nothing is deleted,
 * and every new hold gets a fresh request id, so no PayPal-Request-Id is ever reused.
 *
 * A deal whose holds are both placed is left alone: only its verdict or its deadline moves that money,
 * so Reset refuses until it settles rather than voiding it outside the protocol.
 */
@Service
class DemoReset(
    private val sessions: SessionRepository,
    private val sessionService: SessionService,
    private val contracts: ContractRepository,
    private val funding: FundingService,
) {
    @Transactional
    fun reset(): ResetResult {
        // The session row lock first: a deal locking its terms needs it too, so none can slip in mid-reset.
        val session = sessions.lockForUpdate(sessionService.current().id)
        val deals = contracts.inSession(session.id)
        val inFlight = deals.filter { it.state in IN_FLIGHT }
        if (inFlight.isNotEmpty()) {
            throw ApiException.conflict(
                "deal_in_flight",
                inFlight.joinToString("; ", prefix = "wait for these to settle first (their verdict or deadline decides the money): ") {
                    "${it.id} is ${it.state}"
                },
            )
        }
        val cancelled = deals.filter { it.state == ContractState.TERMS_LOCKED }.map { deal ->
            funding.cancel(deal.id, "reset demo")
            deal.id
        }
        val next = sessionService.startNew()
        return ResetResult(session.id, SessionView(next), cancelled)
    }

    companion object {
        /** Both holds placed, money not yet settled. */
        val IN_FLIGHT = setOf(
            ContractState.BOTH_HELD, ContractState.DELIVERED, ContractState.PASS, ContractState.FAIL,
            ContractState.APPEALED, ContractState.EXPIRED,
        )
    }
}

@RestController
@RequestMapping("/api/demo")
@Tag(name = "Demo", description = "Operator controls for a clean judge run (US-31).")
class DemoController(private val demo: DemoReset) {
    /** US-31. Drafts stay in the archived session, where they can no longer lock terms. */
    @PostMapping("/reset")
    @Operation(summary = "Reset the demo session (US-31)")
    fun reset(): ResetResult = demo.reset()
}
