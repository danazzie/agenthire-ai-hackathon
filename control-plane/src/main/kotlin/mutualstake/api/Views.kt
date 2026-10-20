package mutualstake.api

import mutualstake.contract.Contract
import mutualstake.contract.ContractRepository
import mutualstake.contract.OfferKind
import mutualstake.contract.OfferRepository
import mutualstake.contract.SellerType
import mutualstake.contract.TermsDocument
import mutualstake.delivery.BlobStore
import mutualstake.delivery.DeliveryRepository
import mutualstake.delivery.RunRecord
import mutualstake.delivery.VerifierRunLog
import mutualstake.hold.Hold
import mutualstake.hold.HoldRepository
import mutualstake.hold.HoldSide
import mutualstake.hold.HoldState
import mutualstake.ledger.LedgerRepository
import mutualstake.party.Party
import mutualstake.party.PartyRepository
import mutualstake.session.Session
import mutualstake.session.SessionRepository
import mutualstake.settlement.SettlementService
import mutualstake.verifier.CheckResult
import mutualstake.verifier.DryRun
import mutualstake.verifier.RunOutcome
import mutualstake.verifier.Verifier
import mutualstake.verifier.VerifierRepository
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * What a jar shows. Derived here, from PayPal-confirmed hold states only, so no client can show
 * PAID or COMPENSATION before PayPal reports the payout (US-32).
 */
enum class Jar { EMPTY, HELD, RELEASED, CAPTURED, PAID, COMPENSATION }

fun jarOf(hold: Hold): Jar = when (hold.state) {
    HoldState.PENDING, HoldState.ORDER_CREATED, HoldState.APPROVED, HoldState.ABANDONED -> Jar.EMPTY
    HoldState.HELD, HoldState.VOID_REQUESTED, HoldState.CAPTURE_REQUESTED -> Jar.HELD
    HoldState.VOIDED -> Jar.RELEASED
    HoldState.CAPTURED, HoldState.PAYOUT_REQUESTED -> Jar.CAPTURED
    HoldState.PAID_OUT -> if (hold.side == HoldSide.BUYER_FEE) Jar.PAID else Jar.COMPENSATION
}

data class PartyView(val handle: String, val displayName: String) {
    constructor(party: Party) : this(party.handle, party.displayName)
}

data class SessionView(val id: UUID, val startedAt: Instant, val capMinor: Long, val maxJobs: Int, val firstCaptureConfirmedAt: Instant?) {
    constructor(s: Session) : this(s.id, s.startedAt, s.cap.minor, s.maxJobs, s.firstCaptureConfirmedAt)
}

data class StatusView(val paypalConfigured: Boolean, val webhooksConfigured: Boolean, val parties: List<PartyView>, val session: SessionView)

data class VerifierView(val id: UUID, val sha256: String, val language: String, val source: String, val compiledAt: Instant, val spec: String?) {
    constructor(v: Verifier) : this(v.id, v.sha256, v.language, v.source, v.compiledAt, v.spec)
}

/** How a verdict was reached, with what anyone needs to replay it: the source, harness and runtime it ran on. */
data class RunView(
    val id: UUID, val outcome: RunOutcome, val runtime: String, val harnessSha256: String, val sourceSha256: String,
    val inputSha256: String, val durationMs: Int, val ranAt: Instant, val error: String?,
) {
    constructor(r: RunRecord) : this(r.id, r.outcome, r.runtime, r.harnessSha256, r.sourceSha256, r.inputSha256, r.durationMs, r.ranAt, r.error)
}

data class DryRunView(
    val outcome: RunOutcome, val checks: List<CheckResult>, val summary: String?, val failingCheck: String?, val error: String?,
    val injectionFlags: List<String>, val runtime: String, val harnessSha256: String, val sourceSha256: String, val durationMs: Int,
) {
    constructor(d: DryRun) : this(
        d.run.outcome, d.run.checks, d.run.summary, d.run.failingCheck, d.run.error, d.injectionFlags,
        d.run.runtime, d.run.harnessSha256, d.run.sourceSha256, d.run.durationMs,
    )
}

data class DeliveryView(
    val id: UUID,
    val submittedAt: Instant,
    val payloadSha256: String,
    /** The exact delivered text: data, shown as data. */
    val text: String,
    val verdict: String?,
    val failingCheck: String?,
    val summary: String?,
    val checks: List<CheckResult>,
    val injectionFlags: List<String>,
    val verifiedAt: Instant?,
    val run: RunView?,
    /** Every attempt, including runs that gave no verdict. */
    val attempts: Int,
    val needsAttention: Boolean,
    val attentionReason: String?,
)

data class OfferView(
    val id: UUID, val party: String, val kind: OfferKind, val feeMinor: Long, val bondMinor: Long,
    val deadlineAt: Instant, val verifierId: UUID, val note: String?, val createdAt: Instant,
)

data class HoldView(
    val id: UUID, val side: HoldSide, val payer: String, val amountMinor: Long, val state: HoldState, val jar: Jar,
    val customId: String, val paypalOrderId: String?, val approveUrl: String?, val authorizationId: String?,
    val authorizationExpiresAt: Instant?, val captureId: String?, val capturedMinor: Long?, val payoutBatchId: String?,
    val payoutItemId: String?, val needsAttention: Boolean, val attentionReason: String?,
)

data class TransitionView(val from: String, val to: String, val cause: String, val occurredAt: Instant)

data class LedgerView(val event: String, val direction: String, val amountMinor: Long, val counterparty: String, val paypalRef: String, val occurredAt: Instant)

data class ContractView(
    val id: UUID,
    val state: String,
    val sessionId: UUID,
    val buyer: PartyView,
    val seller: PartyView,
    val sellerType: SellerType,
    val jobText: String,
    val feeCapMinor: Long,
    val feeMinor: Long?,
    val bondMinor: Long?,
    val currency: String,
    val deadlineAt: Instant?,
    val verifier: VerifierView?,
    val termsHash: String?,
    /** The exact document terms_hash is the SHA-256 of, so anyone can recompute it. */
    val termsDocument: String?,
    val holds: List<HoldView>,
    val offers: List<OfferView>,
    val transitions: List<TransitionView>,
    val ledger: List<LedgerView>,
    val delivery: DeliveryView?,
    /** US-16: the verdict is in, and this session's first capture waits for a human to confirm it. */
    val awaitingConfirmation: Boolean,
    val settlementPlannedAt: Instant?,
    val settlementConfirmedAt: Instant?,
    val needsAttention: Boolean,
    val attentionReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Component
class ContractViews(
    private val contracts: ContractRepository,
    private val holds: HoldRepository,
    private val offers: OfferRepository,
    private val parties: PartyRepository,
    private val verifiers: VerifierRepository,
    private val ledger: LedgerRepository,
    private val deliveries: DeliveryRepository,
    private val blobs: BlobStore,
    private val runs: VerifierRunLog,
    private val sessions: SessionRepository,
) {
    fun delivery(contractId: UUID): DeliveryView? {
        val d = deliveries.forContract(contractId) ?: return null
        val attempts = runs.forDelivery(d.id)
        val decided = d.traceRef?.removePrefix("verifier_run:")?.let { runs.find(UUID.fromString(it)) }
        return DeliveryView(
            d.id, d.submittedAt, d.payloadSha256, blobs.bytes(d.payloadSha256).toString(Charsets.UTF_8), d.verdict, d.failingCheck,
            decided?.summary, decided?.checks.orEmpty(), d.injectionFlags, d.verifiedAt, (decided ?: attempts.lastOrNull())?.let(::RunView),
            attempts.size, d.needsAttention, d.attentionReason,
        )
    }

    fun of(contract: Contract): ContractView {
        val buyer = parties.byId(contract.buyerId)
        val seller = parties.byId(contract.sellerId)
        val handles = mapOf(buyer.id to buyer.handle, seller.id to seller.handle)
        val verifier = contract.verifierId?.let(verifiers::byId)
        val document = if (verifier != null && contract.fee != null && contract.bond != null && contract.deadlineAt != null) {
            TermsDocument.canonical(contract.id, contract.buyerId, contract.sellerId, contract.fee, contract.bond, contract.deadlineAt, verifier.sha256)
        } else {
            null
        }
        return ContractView(
            id = contract.id,
            state = contract.state.name,
            sessionId = contract.sessionId,
            buyer = PartyView(buyer),
            seller = PartyView(seller),
            sellerType = contract.sellerType,
            jobText = contract.jobText,
            feeCapMinor = contract.feeCap.minor,
            feeMinor = contract.fee?.minor,
            bondMinor = contract.bond?.minor,
            currency = contract.currency,
            deadlineAt = contract.deadlineAt,
            verifier = verifier?.let(::VerifierView),
            termsHash = contract.termsHash,
            termsDocument = document,
            holds = holds.forContract(contract.id).map { h ->
                HoldView(
                    h.id, h.side, handles.getValue(h.payerId), h.amount.minor, h.state, jarOf(h), h.customId, h.paypalOrderId,
                    h.approveUrl, h.authorizationId, h.authorizationExpiresAt, h.captureId, h.captured?.minor, h.payoutBatchId,
                    h.payoutItemId, h.needsAttention, h.attentionReason,
                )
            },
            offers = offers.forContract(contract.id).map { o ->
                OfferView(o.id, handles.getValue(o.partyId), o.kind, o.terms.fee.minor, o.terms.bond.minor, o.terms.deadlineAt, o.terms.verifierId, o.note, o.createdAt)
            },
            transitions = contracts.transitions(contract.id).map { TransitionView(it.from.name, it.to.name, it.cause, it.occurredAt) },
            ledger = ledger.forContract(contract.id).map { LedgerView(it.event.name, it.direction.name, it.amount.minor, it.counterparty, it.paypalRef, it.occurredAt) },
            delivery = delivery(contract.id),
            awaitingConfirmation = contract.state in SettlementService.SETTLING && contract.settlementPlannedAt == null &&
                sessions.lockFree(contract.sessionId).firstCaptureConfirmedAt == null,
            settlementPlannedAt = contract.settlementPlannedAt,
            settlementConfirmedAt = contract.settlementConfirmedAt,
            needsAttention = contract.needsAttention,
            attentionReason = contract.attentionReason,
            createdAt = contract.createdAt,
            updatedAt = contract.updatedAt,
        )
    }
}
