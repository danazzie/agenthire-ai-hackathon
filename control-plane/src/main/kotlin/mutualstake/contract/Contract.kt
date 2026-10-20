package mutualstake.contract

import mutualstake.crypto.Sha256
import mutualstake.money.CURRENCY
import mutualstake.money.Money
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class Contract(
    val id: UUID,
    val sessionId: UUID,
    val buyerId: UUID,
    val sellerId: UUID,
    val sellerType: SellerType,
    val jobText: String,
    val feeCap: Money,
    val fee: Money?,
    val bond: Money?,
    val currency: String,
    val deadlineAt: Instant?,
    val verifierId: UUID?,
    val termsHash: String?,
    val state: ContractState,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * The four things both sides must ACCEPT identically (US-8). Deadlines are whole UTC seconds, so two
 * agents that mean the same minute always agree, whatever precision they send.
 */
data class Terms(val fee: Money, val bond: Money, val deadlineAt: Instant, val verifierId: UUID) {
    init {
        require(deadlineAt == deadlineAt.truncatedTo(ChronoUnit.SECONDS)) { "deadline must be whole seconds: $deadlineAt" }
    }

    companion object {
        fun normalizeDeadline(deadline: Instant): Instant = deadline.truncatedTo(ChronoUnit.SECONDS)
    }
}

/**
 * The terms as a canonical document: sorted keys, no whitespace, integer cents, ISO-8601 UTC deadline.
 * terms_hash is its SHA-256, so anyone holding the document can recompute it.
 */
object TermsDocument {
    fun canonical(contractId: UUID, buyerId: UUID, sellerId: UUID, fee: Money, bond: Money, deadlineAt: Instant, verifierSha256: String): String =
        """{"bond_minor":${bond.minor},"buyer_id":"$buyerId","contract_id":"$contractId","currency":"$CURRENCY",""" +
            """"deadline_at":"$deadlineAt","fee_minor":${fee.minor},"seller_id":"$sellerId","verifier_sha256":"$verifierSha256"}"""

    fun hash(canonical: String): String = Sha256.hex(canonical)
}
