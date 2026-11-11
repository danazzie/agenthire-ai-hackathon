package mutualstake.agents

import java.time.Instant
import java.util.UUID

/*
 * The wire between the control plane and an agent runner hosted elsewhere (on Astropods). Both sides compile
 * against these types, so the contract cannot drift. The runner gets the same ProtocolPort the in-process agents
 * get, over HTTP, and still nothing on it captures, voids, pays or confirms.
 */

/**
 * Which agent step a job asks for: one negotiation turn per side, the seller's delivery, or turning the person's
 * sentence into a job (DRAFT). A draft is not a deal yet, so it has no contract and is not in the outbox.
 */
enum class JobKind { BUYER_TURN, SELLER_TURN, DELIVER, DRAFT }

/**
 * A step the runner has claimed. For a deal's step the claim is a lease: if no report arrives in time, the step is
 * handed out again. A DRAFT has no [contractId]; it carries the sentence, and the control plane's clock and zone,
 * so "by 18:00" reads the same wherever the agent runs. Nobody waits on a lost draft: the person asks again.
 */
data class RunnerJob(
    val eventId: UUID,
    val contractId: UUID?,
    val kind: JobKind,
    val attempt: Int,
    val sentence: String? = null,
    val now: Instant? = null,
    val zone: String? = null,
) {
    /** The deal this step is about; every kind but DRAFT has one. (A function, so it is not sent as JSON.) */
    fun deal(): UUID = requireNotNull(contractId) { "a $kind step has no deal" }
}

enum class ReportOutcome {
    /** A turn was taken; the other side's turn is next. */
    MOVED,

    /** The deal is no longer in negotiation: nothing to do. */
    LOCKED,

    /** A duplicate or stale turn: the offer log says it is the other side's move. */
    NOT_MY_TURN,

    /** The negotiation ended without terms ([RunnerReport.reason] says why). */
    NO_DEAL,

    /** The seller's agent delivered a draft that passed its own dry-run. */
    DELIVERED,

    /** Every draft failed the verifier, so nothing was delivered. */
    COULD_NOT_MEET_TEST,

    /** The buyer's agent turned the sentence into a job: [RunnerReport.proposal]. */
    DRAFTED,

    /** The sentence cannot become a job ([RunnerReport.reason] is the one line to show the person). */
    REJECTED,

    /** Claude declined or was cut off: retrying the same step will not help. */
    STOPPED,

    /** Anything else (network, timeout, a crash): the control plane retries with backoff. */
    FAILED,
}

/** What happened to a claimed job. [attempts] and [reason] carry the details of COULD_NOT_MEET_TEST, NO_DEAL, REJECTED, STOPPED and FAILED. */
data class RunnerReport(val outcome: ReportOutcome, val reason: String? = null, val attempts: Int? = null, val proposal: JobProposal? = null)

data class ProposeBody(val party: String, val feeCents: Long, val bondCents: Long, val note: String)

data class AcceptBody(val party: String, val note: String?)

data class DryRunBody(val text: String)

data class DeliverBody(val party: String, val text: String)

fun Negotiation.Outcome.toReport(): RunnerReport = when (this) {
    is Negotiation.Outcome.Moved -> RunnerReport(ReportOutcome.MOVED)
    is Negotiation.Outcome.NoDeal -> RunnerReport(ReportOutcome.NO_DEAL, reason)
    Negotiation.Outcome.Locked -> RunnerReport(ReportOutcome.LOCKED)
    Negotiation.Outcome.NotMyTurn -> RunnerReport(ReportOutcome.NOT_MY_TURN)
}

fun WriteOutcome.toReport(): RunnerReport = when (this) {
    is WriteOutcome.Delivered -> RunnerReport(ReportOutcome.DELIVERED, attempts = attempts)
    is WriteOutcome.CouldNotMeetTest -> RunnerReport(ReportOutcome.COULD_NOT_MEET_TEST, last, attempts)
}
