package mutualstake.verifier

import com.fasterxml.jackson.databind.JsonNode
import mutualstake.api.ApiException
import mutualstake.crypto.Sha256
import mutualstake.delivery.InjectionFlagger
import mutualstake.delivery.RunKind
import mutualstake.delivery.VerifierRunLog
import org.springframework.stereotype.Service
import java.util.UUID

data class DryRun(val run: VerifierRunResult, val injectionFlags: List<String>)

@Service
class VerifierService(private val verifiers: VerifierRepository, private val runner: VerifierRunner, private val runs: VerifierRunLog) {

    /** Compiles a structured test, proves the result runs, and stores it. Refuses with a one-line reason (US-1). */
    fun compile(jobText: String, test: JsonNode?): Verifier {
        if (jobText.isBlank()) throw ApiException.unprocessable("invalid_job", "describe the job in one sentence")
        val spec = try {
            VerifierSpec.parse(test)
        } catch (e: UncompilableTestException) {
            throw ApiException.unprocessable("uncompilable_test", e.message ?: "the test cannot be compiled")
        }
        val source = VerifierCompiler.compile(jobText, spec)
        // Any compiled verifier must give a well-formed verdict on any text; an empty one is enough to prove it runs.
        val selfTest = runner.run(source, "")
        if (selfTest.outcome == RunOutcome.ERROR) {
            throw ApiException.unprocessable("uncompilable_test", "the compiled verifier did not run: ${selfTest.error}")
        }
        val verifier = verifiers.save(source, spec.canonical())
        runs.record(verifier.id, null, RunKind.SELF_TEST, Sha256.hex(""), emptyList(), selfTest)
        return verifier
    }

    /** The exact verifier a deal will use, run on any text, with nothing at stake (US-9). */
    fun dryRun(verifierId: UUID, text: String): DryRun {
        val verifier = verifiers.byId(verifierId) ?: throw ApiException.notFound("verifier $verifierId")
        val run = runner.run(verifier.source, text)
        val flags = InjectionFlagger.flags(text)
        runs.record(verifier.id, null, RunKind.DRY_RUN, Sha256.hex(text.toByteArray(Charsets.UTF_8)), flags, run)
        return DryRun(run, flags)
    }
}
