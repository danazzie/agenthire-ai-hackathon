package mutualstake.agents

import java.time.Instant
import java.util.UUID

/**
 * Everything an agent may do. Note what is missing on purpose: nothing here captures, voids, pays out, or
 * confirms a capture. Those happen only on a verdict and PayPal's confirmations, and the first capture of a
 * session waits for a human (US-16), never for an agent.
 */
interface ProtocolPort {
    fun deal(contractId: UUID): Deal

    /** A counter-offer: complete terms. The control plane validates it (cap, bond ratio) and may refuse. */
    fun propose(contractId: UUID, party: String, feeCents: Long, bondCents: Long, note: String)

    /** Accepts the counterparty's latest proposal exactly as it stands: the terms are copied by code, not retyped. */
    fun accept(contractId: UUID, party: String, note: String?)

    /** Runs the deal's own verifier on a text with nothing at stake. */
    fun dryRun(verifierId: UUID, text: String): DryRunResult

    fun deliver(contractId: UUID, party: String, text: String)
}

data class Deal(
    val id: UUID,
    val state: String,
    val jobText: String,
    val buyer: String,
    val seller: String,
    val feeCapCents: Long,
    val deadlineAt: Instant,
    val verifierId: UUID,
    val verifierSha256: String,
    val verifierSource: String,
    /** The structured test the verifier was compiled from, as canonical JSON. */
    val test: String,
    val offers: List<DealOffer>,
    /** Which of the seller's agents does this deal ([SellerSpecialty.id]); null for a deal opened before the roster. */
    val sellerAgent: String? = null,
)

data class DealOffer(val party: String, val kind: String, val feeCents: Long, val bondCents: Long, val note: String?)

data class DryRunResult(val outcome: String, val summary: String?, val failing: List<String>)

/** The control plane refused a move (e.g. over_cap); the agent gets the reason and one more try. */
class MoveRefusedException(val code: String, message: String) : RuntimeException(message)
