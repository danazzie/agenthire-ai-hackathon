package mutualstake.api

import mutualstake.agentic.AgentJobRepository
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
import mutualstake.outbox.OutboxRepository
import mutualstake.outbox.OutboxType
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

/** [agentsHost] is in_process or astropods; with astropods, [runnerSeenAt] says when the runner last asked for work. */
data class StatusView(
    val paypalConfigured: Boolean,
    val webhooksConfigured: Boolean,
    val agentsConfigured: Boolean,
    val parties: List<PartyView>,
    val session: SessionView,
    val agentsHost: String = "in_process",
    val runnerSeenAt: java.time.Instant? = null,
)

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
    /** The jar's caption: the amount and where it is, from PayPal-confirmed state only. */
    val caption: String,
    val customId: String, val paypalOrderId: String?, val approveUrl: String?, val authorizationId: String?,
    val authorizationExpiresAt: Instant?, val captureId: String?, val capturedMinor: Long?, val payoutBatchId: String?,
    val payoutItemId: String?, val needsAttention: Boolean, val attentionReason: String?,
)

/** A step still running (an agent turn, a PayPal call, the verifier), so the screen never looks frozen. */
data class InFlightView(val step: String, val label: String, val attempts: Int, val lastError: String?)

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
    /** The person's own sentence, when the agents run the deal (US-1). */
    val sentence: String?,
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
    val inFlight: List<InFlightView>,
    /** Who got what, in one plain sentence, once PayPal has confirmed both holds' outcomes. */
    val outcome: String?,
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
    private val agentJobs: AgentJobRepository,
    private val outbox: OutboxRepository,
) {
    private fun caption(h: Hold, names: Map<UUID, String>): String {
        val amount = h.amount.toString()
        val paid = (h.captured ?: h.amount).toString()
        val to = h.payoutRecipientId?.let(names::get)
        return when (h.state) {
            HoldState.PENDING, HoldState.ORDER_CREATED, HoldState.APPROVED -> "$amount, not held yet"
            HoldState.ABANDONED -> "never held"
            HoldState.HELD -> "$amount held, not charged"
            HoldState.VOID_REQUESTED -> "$amount held; release requested"
            HoldState.VOIDED -> "Released: $amount never charged"
            HoldState.CAPTURE_REQUESTED -> "$amount held; capture requested"
            HoldState.CAPTURED, HoldState.PAYOUT_REQUESTED -> "Captured $paid; paying out to $to"
            HoldState.PAID_OUT -> if (h.side == HoldSide.BUYER_FEE) "Paid: $paid to $to" else "Compensation: $paid paid to $to"
        }
    }

    /** Who got what, only from final, PayPal-confirmed hold states (US-21, US-32). */
    private fun outcome(holds: List<Hold>, names: Map<UUID, String>): String? {
        val fee = holds.firstOrNull { it.side == HoldSide.BUYER_FEE } ?: return null
        val bond = holds.firstOrNull { it.side == HoldSide.SELLER_BOND } ?: return null
        val buyer = names.getValue(fee.payerId)
        val seller = names.getValue(bond.payerId)
        val untouched = setOf(HoldState.VOIDED, HoldState.ABANDONED)
        return when {
            fee.state == HoldState.PAID_OUT && bond.state == HoldState.VOIDED ->
                "$seller was paid ${fee.captured ?: fee.amount}; $seller's ${bond.amount} bond was released, never charged."
            fee.state == HoldState.VOIDED && bond.state == HoldState.PAID_OUT ->
                "$buyer was never charged; $seller's ${bond.captured ?: bond.amount} bond was paid to $buyer as compensation."
            fee.state in untouched && bond.state in untouched -> "Nothing moved: neither side was charged."
            else -> null
        }
    }

    private fun inFlight(contractId: UUID, holds: List<Hold>, deliveryId: UUID?, names: Map<UUID, String>): List<InFlightView> {
        val sides = holds.associate { it.id to it.side.label }
        val buyer = holds.firstOrNull { it.side == HoldSide.BUYER_FEE }?.payerId?.let(names::get) ?: "The buyer's agent"
        val seller = holds.firstOrNull { it.side == HoldSide.SELLER_BOND }?.payerId?.let(names::get) ?: "The seller's agent"
        return outbox.liveFor(listOfNotNull(contractId, deliveryId) + holds.map { it.id }).map { step ->
            val side = sides[step.aggregateId]
            val label = when (step.type) {
                OutboxType.AGENT_TURN_BUYER -> "$buyer is thinking"
                OutboxType.AGENT_TURN_SELLER -> "$seller is thinking"
                OutboxType.AGENT_DELIVER -> "$seller is writing the delivery and testing it against the verifier"
                OutboxType.CREATE_ORDER -> "Creating the PayPal order for the $side"
                OutboxType.CHECK_ORDER -> "Waiting for the $side to be approved on PayPal"
                OutboxType.AUTHORIZE -> "Placing the $side hold on PayPal"
                OutboxType.RUN_VERIFIER -> "The verifier is running"
                OutboxType.VOID -> "Releasing the $side on PayPal"
                OutboxType.CAPTURE -> "Capturing the $side on PayPal"
                OutboxType.CHECK_CAPTURE -> "Waiting for PayPal to complete the $side capture"
                OutboxType.PAYOUT -> "Sending the $side payout"
                OutboxType.CHECK_PAYOUT -> "Waiting for PayPal to deliver the $side payout"
            }
            InFlightView(step.type.name, label, step.attempts, step.lastError)
        }
    }
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
        val names = mapOf(buyer.id to buyer.displayName, seller.id to seller.displayName)
        val holdRows = holds.forContract(contract.id)
        val delivery = delivery(contract.id)
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
            sentence = agentJobs.find(contract.id)?.sentence,
            feeCapMinor = contract.feeCap.minor,
            feeMinor = contract.fee?.minor,
            bondMinor = contract.bond?.minor,
            currency = contract.currency,
            deadlineAt = contract.deadlineAt,
            verifier = verifier?.let(::VerifierView),
            termsHash = contract.termsHash,
            termsDocument = document,
            holds = holdRows.map { h ->
                HoldView(
                    h.id, h.side, handles.getValue(h.payerId), h.amount.minor, h.state, jarOf(h), caption(h, names), h.customId, h.paypalOrderId,
                    h.approveUrl, h.authorizationId, h.authorizationExpiresAt, h.captureId, h.captured?.minor, h.payoutBatchId,
                    h.payoutItemId, h.needsAttention, h.attentionReason,
                )
            },
            offers = offers.forContract(contract.id).map { o ->
                OfferView(o.id, handles.getValue(o.partyId), o.kind, o.terms.fee.minor, o.terms.bond.minor, o.terms.deadlineAt, o.terms.verifierId, o.note, o.createdAt)
            },
            transitions = contracts.transitions(contract.id).map { TransitionView(it.from.name, it.to.name, it.cause, it.occurredAt) },
            ledger = ledger.forContract(contract.id).map { LedgerView(it.event.name, it.direction.name, it.amount.minor, it.counterparty, it.paypalRef, it.occurredAt) },
            delivery = delivery,
            awaitingConfirmation = contract.state in SettlementService.SETTLING && contract.settlementPlannedAt == null &&
                sessions.lockFree(contract.sessionId).firstCaptureConfirmedAt == null,
            settlementPlannedAt = contract.settlementPlannedAt,
            settlementConfirmedAt = contract.settlementConfirmedAt,
            needsAttention = contract.needsAttention,
            attentionReason = contract.attentionReason,
            inFlight = inFlight(contract.id, holdRows, delivery?.id, names),
            outcome = outcome(holdRows, names),
            createdAt = contract.createdAt,
            updatedAt = contract.updatedAt,
        )
    }
}
