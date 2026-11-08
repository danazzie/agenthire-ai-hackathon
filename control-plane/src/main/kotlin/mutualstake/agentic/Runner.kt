package mutualstake.agentic

import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import mutualstake.agents.AcceptBody
import mutualstake.agents.Deal
import mutualstake.agents.DeliverBody
import mutualstake.agents.DryRunBody
import mutualstake.agents.DryRunResult
import mutualstake.agents.JobKind
import mutualstake.agents.ProposeBody
import mutualstake.agents.ReportOutcome
import mutualstake.agents.RunnerJob
import mutualstake.agents.RunnerReport
import mutualstake.api.ApiException
import mutualstake.config.MutualStakeProperties
import mutualstake.contract.ContractRepository
import mutualstake.contract.ContractState
import mutualstake.outbox.OutboxPoller
import mutualstake.outbox.OutboxRepository
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Where the agents run. In process (the default), the outbox poller runs their steps. Remote (AGENTS_REMOTE), the
 * poller leaves those steps queued for the agent runner on Astropods, which claims them over HTTP. Switchable at
 * runtime only so tests can exercise both in one Spring context.
 */
@Component
class AgentHosting(props: MutualStakeProperties, private val poller: OutboxPoller) {
    @Volatile
    var remote: Boolean = false
        set(value) {
            field = value
            poller.leftToOthers = if (value) AGENT_TYPES else emptySet()
        }

    /** When the runner last asked for work: the status screen shows whether anyone is there to take it. */
    @Volatile
    var runnerSeenAt: Instant? = null

    init {
        remote = props.agents.remote
    }

    val host: String get() = if (remote) "astropods" else "in_process"
}

/**
 * The control plane's side of the remote agents. A claim is the same leased outbox claim the poller uses, so a
 * runner that dies mid-step simply has its step handed out again. The report is applied by the same code the
 * in-process handlers use (AgentOutcomes, OutboxPoller.fail), so a deal behaves the same wherever its agents ran.
 */
@Service
class RunnerService(
    private val outbox: OutboxRepository,
    private val poller: OutboxPoller,
    private val outcomes: AgentOutcomes,
    private val contracts: ContractRepository,
    private val hosting: AgentHosting,
) {
    /** Hands out the oldest due agent step, waiting up to [wait] for one to appear (a long poll, so turns follow quickly). */
    fun claim(wait: Duration): RunnerJob? {
        val until = System.nanoTime() + wait.toNanos()
        while (true) {
            hosting.runnerSeenAt = Instant.now()
            for (event in outbox.claimDueOf(AGENT_TYPES, 1, LEASE)) {
                val kind = event.type.jobKind ?: continue
                // As in AgentDeliverHandler: a delivery asked for before the holds are in place, or after, does nothing.
                if (kind == JobKind.DELIVER && contracts.find(event.aggregateId)?.state != ContractState.BOTH_HELD) {
                    outbox.markPublished(event.id)
                    continue
                }
                return RunnerJob(event.id, event.aggregateId, kind, event.attempts)
            }
            if (System.nanoTime() >= until) return null
            Thread.sleep(POLL.toMillis())
        }
    }

    fun report(eventId: UUID, report: RunnerReport) {
        val event = outbox.findLive(eventId) ?: throw ApiException.conflict("job_closed", "job $eventId is already done or dead")
        val kind = event.type.jobKind ?: throw ApiException.unprocessable("not_an_agent_job", "job $eventId is a ${event.type}, not an agent step")
        if (!outcomes.fits(kind, report.outcome)) throw ApiException.unprocessable("outcome_mismatch", "a $kind step cannot end ${report.outcome}")
        val handler = requireNotNull(poller.handlerFor(event.type))
        when (report.outcome) {
            ReportOutcome.STOPPED -> poller.fail(handler, event, "agent_stopped: ${report.reason}", retryable = false)
            ReportOutcome.FAILED -> poller.fail(handler, event, "runner: ${report.reason}", retryable = true)
            else -> {
                outcomes.record(event.aggregateId, kind, report)
                outbox.markPublished(event.id)
            }
        }
    }

    companion object {
        /** Longer than the runner's own step limit (90 s, as in process), so a live step is never handed out twice. */
        val LEASE: Duration = Duration.ofSeconds(120)
        val POLL: Duration = Duration.ofMillis(250)
        val MAX_WAIT: Duration = Duration.ofSeconds(25)
    }
}

/**
 * The runner endpoints answer only the runner: a bearer token both sides share (RUNNER_TOKEN), and only while
 * the agents are hosted remotely. Every refusal is the usual problem+json with a stable code.
 */
@Component
class RunnerAuth(private val props: MutualStakeProperties, private val hosting: AgentHosting) : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val token = props.agents.runnerToken
        if (token.isBlank()) throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "runner_off", "RUNNER_TOKEN is not set, so no agent runner can connect")
        val sent = request.getHeader("Authorization")?.removePrefix("Bearer ")?.trim().orEmpty()
        if (!MessageDigest.isEqual(sent.toByteArray(), token.toByteArray())) throw ApiException(HttpStatus.UNAUTHORIZED, "bad_runner_token", "send Authorization: Bearer <RUNNER_TOKEN>")
        if (!hosting.remote) throw ApiException.conflict("agents_in_process", "AGENTS_REMOTE is off: this control plane runs the agents itself")
        return true
    }
}

@Configuration
class RunnerWebConfig(private val auth: RunnerAuth) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(auth).addPathPatterns("/api/runner/**")
    }
}

/**
 * The agent runner's API: claim a step, use the protocol exactly as the in-process agents do (AgentPort, so the
 * semantics cannot differ), report how the step ended. Nothing here captures, voids, pays or confirms.
 */
@Hidden
@RestController
@RequestMapping("/api/runner")
class RunnerController(private val runner: RunnerService, private val port: AgentPort) {

    @PostMapping("/claim")
    fun claim(@RequestParam(defaultValue = "0") waitSeconds: Long): ResponseEntity<RunnerJob> {
        val wait = Duration.ofSeconds(waitSeconds.coerceIn(0, RunnerService.MAX_WAIT.seconds))
        return runner.claim(wait)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()
    }

    @PostMapping("/jobs/{eventId}/report")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun report(@PathVariable eventId: UUID, @RequestBody report: RunnerReport) = runner.report(eventId, report)

    @GetMapping("/deals/{id}")
    fun deal(@PathVariable id: UUID): Deal = port.deal(id)

    @PostMapping("/deals/{id}/propose")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun propose(@PathVariable id: UUID, @RequestBody body: ProposeBody) = port.propose(id, body.party, body.feeCents, body.bondCents, body.note)

    @PostMapping("/deals/{id}/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun accept(@PathVariable id: UUID, @RequestBody body: AcceptBody) = port.accept(id, body.party, body.note)

    @PostMapping("/verifiers/{id}/dry-run")
    fun dryRun(@PathVariable id: UUID, @RequestBody body: DryRunBody): DryRunResult = port.dryRun(id, body.text)

    @PostMapping("/deals/{id}/deliver")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deliver(@PathVariable id: UUID, @RequestBody body: DeliverBody) = port.deliver(id, body.party, body.text)
}
