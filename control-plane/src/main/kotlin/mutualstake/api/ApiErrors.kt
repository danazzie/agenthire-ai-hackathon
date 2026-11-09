package mutualstake.api

import mutualstake.agents.MoveRefusedException
import mutualstake.contract.IllegalTransitionException
import mutualstake.contract.StaleStateException
import mutualstake.verifier.VerifierUnavailableException
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.sql.SQLException

/** Every refusal is a problem+json with a stable `code`, so agents can act on it. */
@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException) = problem(e.status, e.code, e.message)

    /** A move the control plane refused: the agent gets the reason and one more try, wherever it runs. */
    @ExceptionHandler(MoveRefusedException::class)
    fun refused(e: MoveRefusedException) = problem(HttpStatus.UNPROCESSABLE_ENTITY, e.code, e.message, refused = true)

    @ExceptionHandler(IllegalTransitionException::class)
    fun illegal(e: IllegalTransitionException) = problem(HttpStatus.CONFLICT, "illegal_transition", e.message)

    @ExceptionHandler(StaleStateException::class)
    fun stale(e: StaleStateException) = problem(HttpStatus.CONFLICT, "stale_state", e.message)

    @ExceptionHandler(VerifierUnavailableException::class)
    fun verifierUnavailable(e: VerifierUnavailableException) = problem(HttpStatus.SERVICE_UNAVAILABLE, "verifier_unavailable", e.reason)

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun missingHeader(e: MissingRequestHeaderException) =
        problem(HttpStatus.UNAUTHORIZED, "unknown_party", "send $PARTY_HEADER: <handle>; v1 has two seeded identities and no login")

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    fun unreadable(e: Exception) = problem(HttpStatus.BAD_REQUEST, "invalid_request", e.message?.substringBefore(":") ?: "malformed request")

    /** The database's own refusals (triggers and constraints) surface with their meaning, not as a 500. */
    @ExceptionHandler(DataAccessException::class)
    fun database(e: DataAccessException): ResponseEntity<ProblemDetail> {
        val sql = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<SQLException>().firstOrNull() ?: throw e
        return when (sql.sqlState) {
            "MS001" -> problem(HttpStatus.CONFLICT, "append_only", sql.message)
            "MS002" -> problem(HttpStatus.UNPROCESSABLE_ENTITY, "hold_mismatch", sql.message)
            "MS003" -> problem(HttpStatus.CONFLICT, "illegal_transition", sql.message)
            "MS004" -> problem(HttpStatus.CONFLICT, "terms_locked", sql.message)
            "23514", "23505", "23503" -> problem(HttpStatus.UNPROCESSABLE_ENTITY, "constraint_violation", sql.message)
            else -> throw e
        }
    }

    private fun problem(status: HttpStatus, code: String, detail: String?, refused: Boolean = false): ResponseEntity<ProblemDetail> {
        val body = ProblemDetail.forStatusAndDetail(status, detail?.lineSequence()?.first() ?: code)
        body.title = code
        body.setProperty("code", code)
        if (refused) body.setProperty("refused", true)
        return ResponseEntity.status(status).body(body)
    }
}
