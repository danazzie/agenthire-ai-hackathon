package mutualstake.contract

import java.util.UUID

enum class ContractState {
    DRAFT, TERMS_LOCKED, BOTH_HELD, DELIVERED, PASS, FAIL, APPEALED, SETTLED, EXPIRED, CANCELLED;

    fun canBecome(next: ContractState): Boolean = next in TRANSITIONS[this].orEmpty()

    companion object {
        /**
         * The only legal transitions: the build prompt's list, plus EXPIRED -> SETTLED (a no-show settles
         * like a FAIL) and TERMS_LOCKED -> CANCELLED (void whichever hold exists), agreed 2026-10-20.
         * The database holds the same list (contract_transition_rule); a test keeps them identical.
         */
        val TRANSITIONS: Map<ContractState, Set<ContractState>> = mapOf(
            DRAFT to setOf(TERMS_LOCKED),
            TERMS_LOCKED to setOf(BOTH_HELD, CANCELLED),
            BOTH_HELD to setOf(DELIVERED, EXPIRED),
            DELIVERED to setOf(PASS, FAIL),
            PASS to setOf(SETTLED, APPEALED),
            FAIL to setOf(SETTLED, APPEALED),
            APPEALED to setOf(SETTLED),
            EXPIRED to setOf(SETTLED),
        )
    }
}

enum class SellerType { AGENT, HUMAN }

/** A transition that is not on the list. Thrown before the database would refuse it anyway (MS003). */
class IllegalTransitionException(val contractId: UUID, val from: ContractState, val to: ContractState) :
    RuntimeException("illegal contract transition $from -> $to for contract $contractId")

/** The row was no longer in the state the caller read; someone else moved it first. */
class StaleStateException(val id: UUID, val expected: String) :
    RuntimeException("$id is no longer $expected")
