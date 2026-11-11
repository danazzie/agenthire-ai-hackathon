package mutualstake.runner

import mutualstake.agents.AgentStoppedException
import mutualstake.agents.BuyerAgent
import mutualstake.agents.Claude
import mutualstake.agents.JobKind
import mutualstake.agents.JobRejectedException
import mutualstake.agents.Negotiation
import mutualstake.agents.ReportOutcome
import mutualstake.agents.RunnerJob
import mutualstake.agents.RunnerReport
import mutualstake.agents.SellerAgent
import mutualstake.agents.toReport
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** One finished step, as the status page lists it. */
data class StepRecord(val at: Instant, val job: RunnerJob, val report: RunnerReport, val took: Duration)

/** What the status page shows: recent steps, counters, and the last time the control plane could not be reached. */
class RunnerState(val startedAt: Instant = Instant.now()) {
    val recent = ConcurrentLinkedDeque<StepRecord>()
    val claimed = AtomicLong()

    @Volatile var lastContact: Instant? = null

    @Volatile var lastError: String? = null

    @Volatile var lastErrorAt: Instant? = null

    fun record(step: StepRecord) {
        recent.addFirst(step)
        while (recent.size > KEEP) recent.pollLast()
    }

    fun error(message: String) {
        lastError = message
        lastErrorAt = Instant.now()
    }

    companion object {
        const val KEEP = 50
    }
}

/**
 * The agents' loop: claim a step, run it with the same agent code the control plane would run in process, report
 * how it ended. Claude declining is STOPPED (no retry); anything else that goes wrong is FAILED, and the control
 * plane retries it with backoff. A step is cut off after [stepTimeout], as the in-process outbox does.
 */
class Runner(
    private val controlPlane: ControlPlane,
    claude: Claude,
    zone: ZoneId,
    maxOffers: Int = 12,
    writingAttempts: Int = 4,
    private val stepTimeout: Duration = Duration.ofSeconds(90),
    val state: RunnerState = RunnerState(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val seller = SellerAgent(claude, writingAttempts)
    private val buyer = BuyerAgent(claude, zone)
    private val negotiation = Negotiation(controlPlane, buyer, seller, maxOffers)
    private val steps = Executors.newCachedThreadPool { r -> Thread(r, "agent-step").apply { isDaemon = true } }

    @Volatile private var running = true

    /** Claims one step (waiting up to [waitSeconds]) and runs it. Returns false when none came. */
    fun runOnce(waitSeconds: Long): Boolean {
        val job = controlPlane.claim(waitSeconds)
        state.lastContact = Instant.now()
        if (job == null) return false
        state.claimed.incrementAndGet()
        val started = System.nanoTime()
        log.info("claimed {} for {} (attempt {})", job.kind, job.contractId ?: "a sentence", job.attempt)
        val report = execute(job)
        reportWithRetries(job, report)
        val took = Duration.ofNanos(System.nanoTime() - started)
        state.record(StepRecord(Instant.now(), job, report, took))
        log.info("{} for {}: {}{} in {} ms", job.kind, job.contractId ?: "a sentence", report.outcome, report.reason?.let { " ($it)" }.orEmpty(), took.toMillis())
        return true
    }

    /**
     * A lost report would leave the step to expire and run again; a turn that already moved would then find it is
     * not its turn and the next turn would never be queued. So a report is retried before giving up on it.
     */
    private fun reportWithRetries(job: RunnerJob, report: RunnerReport) {
        var wait = 1L
        repeat(REPORT_TRIES - 1) {
            try {
                return controlPlane.report(job, report)
            } catch (e: ControlPlaneException) {
                // The person stopped waiting (the control plane gave up, or restarted): nothing to hand the draft to.
                if (job.kind == JobKind.DRAFT && e.status == 409) return log.warn("draft {} came back too late: {}", job.eventId, e.message)
                if (e.status < 500) throw e
                log.warn("report for {} failed: {}; retrying", job.eventId, e.message)
            } catch (e: java.io.IOException) {
                log.warn("report for {} failed: {}; retrying", job.eventId, e.message)
            }
            Thread.sleep(wait * 1000)
            wait *= 2
        }
        controlPlane.report(job, report)
    }

    fun execute(job: RunnerJob): RunnerReport {
        val step = steps.submit<RunnerReport> { step(job) }
        return try {
            step.get(stepTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            step.cancel(true)
            RunnerReport(ReportOutcome.FAILED, "timeout: ${job.kind} took longer than ${stepTimeout.seconds} s")
        } catch (wrapped: ExecutionException) {
            val e = wrapped.cause ?: wrapped
            log.warn("{} for {} failed", job.kind, job.contractId ?: "a sentence", e)
            RunnerReport(ReportOutcome.FAILED, "${e.javaClass.simpleName}: ${e.message}".take(900))
        }
    }

    private fun step(job: RunnerJob): RunnerReport = when (job.kind) {
        JobKind.BUYER_TURN, JobKind.SELLER_TURN -> {
            val deal = controlPlane.deal(job.deal())
            val party = if (job.kind == JobKind.BUYER_TURN) deal.buyer else deal.seller
            try {
                negotiation.turn(job.deal(), party).toReport()
            } catch (e: AgentStoppedException) {
                RunnerReport(ReportOutcome.STOPPED, "$party's agent: ${e.message}")
            }
        }
        JobKind.DELIVER -> try {
            seller.write(controlPlane.deal(job.deal()), controlPlane).toReport()
        } catch (e: AgentStoppedException) {
            RunnerReport(ReportOutcome.STOPPED, "the seller's agent: ${e.message}")
        }
        JobKind.DRAFT -> draft(job)
    }

    /** US-1 on the runner: the control plane's clock and zone, so the deadline is the one it would have computed. */
    private fun draft(job: RunnerJob): RunnerReport {
        val sentence = requireNotNull(job.sentence) { "a draft carries its sentence" }
        val now = job.now ?: Instant.now()
        val zone = job.zone?.let(ZoneId::of)
        return try {
            val proposal = if (zone == null) buyer.draftJob(sentence, now) else buyer.draftJob(sentence, now, zone)
            RunnerReport(ReportOutcome.DRAFTED, proposal = proposal)
        } catch (e: JobRejectedException) {
            RunnerReport(ReportOutcome.REJECTED, e.message)
        } catch (e: AgentStoppedException) {
            RunnerReport(ReportOutcome.STOPPED, "the buyer's agent: ${e.message}")
        }
    }

    /** One worker: long-polls for steps until [stop]. When the control plane cannot be reached, it waits and tries again. */
    fun work(waitSeconds: Long = 20) {
        var backoff = 1L
        while (running) {
            try {
                runOnce(waitSeconds)
                backoff = 1
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                val message = (e as? ControlPlaneException)?.message ?: "${e.javaClass.simpleName}: ${e.message}"
                state.error(message)
                log.warn("control plane: {}; retrying in {} s", message, backoff)
                Thread.sleep(backoff * 1000)
                backoff = minOf(30, backoff * 2)
            }
        }
    }

    fun stop() {
        running = false
    }

    companion object {
        const val REPORT_TRIES = 5
    }
}
