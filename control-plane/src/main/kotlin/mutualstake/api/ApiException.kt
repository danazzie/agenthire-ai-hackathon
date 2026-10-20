package mutualstake.api

import org.springframework.http.HttpStatus

/** An honest, machine-readable refusal: [code] is stable (e.g. `no_credentials`, `terms_locked`). */
class ApiException(val status: HttpStatus, val code: String, message: String) : RuntimeException(message) {
    companion object {
        fun notFound(what: String) = ApiException(HttpStatus.NOT_FOUND, "not_found", "$what not found")
        fun unprocessable(code: String, message: String) = ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message)
        fun conflict(code: String, message: String) = ApiException(HttpStatus.CONFLICT, code, message)
    }
}
