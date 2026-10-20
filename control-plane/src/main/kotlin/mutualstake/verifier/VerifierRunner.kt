package mutualstake.verifier

import mutualstake.outbox.ClassifiedFailure

enum class RunOutcome { PASS, FAIL, ERROR }

data class CheckResult(val name: String, val passed: Boolean, val detail: String)

/**
 * One run of a verifier over one text. Only a well-formed result is a verdict: PASS when every check
 * passed, FAIL when at least one check failed (and it is cited, US-26). ERROR (a crash, a timeout, a
 * malformed result) is no verdict at all: it decides nothing and moves no money.
 */
data class VerifierRunResult(
    val outcome: RunOutcome,
    val checks: List<CheckResult>,
    val failingCheck: String?,
    val summary: String?,
    val error: String?,
    val trace: List<String>,
    /** SHA-256 of the exact source sent to the runtime. */
    val sourceSha256: String,
    val harnessSha256: String,
    val runtime: String,
    val durationMs: Int,
)

interface VerifierRunner {
    fun run(source: String, text: String): VerifierRunResult
}

/** The runtime could not be started: nothing was judged. Worth retrying; never a FAIL. */
class VerifierUnavailableException(override val reason: String) : RuntimeException(reason), ClassifiedFailure {
    override val retryable = true
}

/** The verifier ran but gave no verdict (see [RunOutcome.ERROR]). Retried, then flagged; never a FAIL. */
class VerifierInconclusiveException(override val reason: String) : RuntimeException(reason), ClassifiedFailure {
    override val retryable = true
}

/** Refusing to run at all, e.g. the source no longer matches the hash on the PayPal orders. Not retried. */
class VerifierRefusedException(override val reason: String) : RuntimeException(reason), ClassifiedFailure {
    override val retryable = false
}
