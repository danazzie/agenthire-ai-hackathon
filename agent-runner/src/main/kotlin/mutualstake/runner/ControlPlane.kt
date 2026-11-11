package mutualstake.runner

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import mutualstake.agents.AcceptBody
import mutualstake.agents.Deal
import mutualstake.agents.DeliverBody
import mutualstake.agents.DryRunBody
import mutualstake.agents.DryRunResult
import mutualstake.agents.JobKind
import mutualstake.agents.MoveRefusedException
import mutualstake.agents.ProposeBody
import mutualstake.agents.ProtocolPort
import mutualstake.agents.RunnerJob
import mutualstake.agents.RunnerReport
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/** The control plane answered with a problem it did not mark as a refusal: the step fails and is retried there. */
class ControlPlaneException(val status: Int, val code: String, message: String) : RuntimeException("$status $code: $message")

/**
 * The control plane, as the runner reaches it: the runner endpoints, signed with the shared token. As a
 * [ProtocolPort] it is the same port the in-process agents get, so the agent code runs unchanged; a refusal the
 * control plane marks `refused` comes back as [MoveRefusedException], which the agents treat as feedback.
 */
class ControlPlane(baseUrl: String, private val token: String, private val http: HttpClient = defaultClient()) : ProtocolPort {
    private val base = baseUrl.trimEnd('/') + "/api/runner"

    /** Waits up to [waitSeconds] on the control plane for the next agent step, drafts included; null when none came. */
    fun claim(waitSeconds: Long): RunnerJob? {
        val response = send("POST", "/claim?waitSeconds=$waitSeconds&drafts=true", null, Duration.ofSeconds(waitSeconds + 15))
        return if (response.statusCode() == 204) null else json.readValue(response.body())
    }

    /** A draft goes back to the request waiting for it; a deal's step goes back to its outbox event. */
    fun report(job: RunnerJob, report: RunnerReport) {
        val path = if (job.kind == JobKind.DRAFT) "/drafts/${job.eventId}/report" else "/jobs/${job.eventId}/report"
        send("POST", path, report)
    }

    override fun deal(contractId: UUID): Deal = json.readValue(send("GET", "/deals/$contractId", null).body())

    override fun propose(contractId: UUID, party: String, feeCents: Long, bondCents: Long, note: String) {
        send("POST", "/deals/$contractId/propose", ProposeBody(party, feeCents, bondCents, note))
    }

    override fun accept(contractId: UUID, party: String, note: String?) {
        send("POST", "/deals/$contractId/accept", AcceptBody(party, note))
    }

    override fun dryRun(verifierId: UUID, text: String): DryRunResult = json.readValue(send("POST", "/verifiers/$verifierId/dry-run", DryRunBody(text)).body())

    override fun deliver(contractId: UUID, party: String, text: String) {
        send("POST", "/deals/$contractId/deliver", DeliverBody(party, text))
    }

    private fun send(method: String, path: String, body: Any?, timeout: Duration = CALL_TIMEOUT): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(timeout)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() in 200..299) return response
        val problem = runCatching { json.readTree(response.body()) }.getOrNull()
        val code = problem?.path("code")?.asText()?.takeIf { it.isNotBlank() } ?: "http_${response.statusCode()}"
        val detail = problem?.path("detail")?.asText()?.takeIf { it.isNotBlank() } ?: response.body().take(300)
        if (problem?.path("refused")?.asBoolean() == true) throw MoveRefusedException(code, detail)
        throw ControlPlaneException(response.statusCode(), code, detail)
    }

    companion object {
        val CALL_TIMEOUT: Duration = Duration.ofSeconds(30)

        val json: ObjectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

        fun defaultClient(): HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    }
}
