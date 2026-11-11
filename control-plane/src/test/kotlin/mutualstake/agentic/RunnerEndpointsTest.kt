package mutualstake.agentic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import mutualstake.agents.CheckAnswer
import mutualstake.agents.JobDraftAnswer
import mutualstake.contract.ContractRepository
import mutualstake.contract.ContractState
import mutualstake.outbox.OutboxPoller
import mutualstake.outbox.OutboxRepository
import mutualstake.outbox.OutboxType
import mutualstake.session.SessionService
import mutualstake.support.FakeClaude
import mutualstake.support.FakePayPal
import mutualstake.support.IntegrationTest
import mutualstake.support.MutableClock
import mutualstake.support.quiesceOutbox
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** AGENTS_REMOTE: the steps wait for the runner, which uses the protocol over HTTP and reports how each ended. */
@IntegrationTest
class RunnerEndpointsTest @Autowired constructor(
    private val mvc: MockMvc,
    private val mapper: ObjectMapper,
    private val claude: FakeClaude,
    private val fake: FakePayPal,
    private val clock: MutableClock,
    private val sessions: SessionService,
    private val contracts: ContractRepository,
    private val outbox: OutboxRepository,
    private val poller: OutboxPoller,
    private val hosting: AgentHosting,
    private val jdbc: JdbcClient,
) {
    private val token = "test-runner-token"

    @BeforeEach
    fun reset() {
        claude.reset()
        fake.reset()
        clock.reset()
        quiesceOutbox(jdbc)
        sessions.startNew()
        hosting.remote = true
    }

    @AfterEach
    fun local() {
        hosting.remote = false
    }

    private fun json(result: ResultActionsDsl): JsonNode = mapper.readTree(result.andReturn().response.contentAsString)

    private fun runner(path: String, body: Any? = null, bearer: String = token) = mvc.post(path) {
        header("Authorization", "Bearer $bearer")
        contentType = MediaType.APPLICATION_JSON
        content = body?.let(mapper::writeValueAsString) ?: "{}"
    }

    /**
     * A drafted and confirmed job. These tests are about the deal's steps, so the draft is written in process
     * (with remote off for that one call); the draft over the wire has tests of its own below.
     */
    private fun startJob(): UUID {
        claude.answer(
            JobDraftAnswer(
                "A 50-word coffee-shop blurb (${UUID.randomUUID()}).", "2.00", "18:00", null,
                listOf(CheckAnswer("word_count", 50, null, null, null, null)),
            ),
        )
        hosting.remote = false
        val d = json(mvc.post("/api/agents/jobs/draft") { contentType = MediaType.APPLICATION_JSON; content = """{"sentence":"50-word blurb, max ${'$'}2, by 18:00"}""" })
        hosting.remote = true
        claude.available = false
        val body = mapOf(
            "sentence" to d.path("sentence").asText(), "jobText" to d.path("jobText").asText(), "feeCapMinor" to d.path("feeCapMinor").asLong(),
            "deadlineAt" to d.path("deadlineAt").asText(), "verifierId" to d.path("verifier").path("id").asText(),
        )
        val started = mvc.post("/api/agents/jobs") { contentType = MediaType.APPLICATION_JSON; content = mapper.writeValueAsString(body) }
            .andExpect { status { isCreated() } }
        return UUID.fromString(json(started).path("id").asText())
    }

    private fun claim(): JsonNode = json(runner("/api/runner/claim").andExpect { status { isOk() } })

    @Test
    fun `the poller leaves agent steps to the runner, which negotiates over HTTP`() {
        val id = startJob()
        poller.drain()
        assertEquals(listOf(OutboxType.AGENT_TURN_BUYER), outbox.live(id), "queued for the runner, not run here")

        val buyerTurn = claim()
        assertEquals("BUYER_TURN", buyerTurn.path("kind").asText())
        assertEquals(id.toString(), buyerTurn.path("contractId").asText())
        runner("/api/runner/claim").andExpect { status { isNoContent() } }

        val deal = json(mvc.get("/api/runner/deals/$id") { header("Authorization", "Bearer $token") }.andExpect { status { isOk() } })
        assertEquals("alex", deal.path("buyer").asText())
        assertEquals(200, deal.path("feeCapCents").asInt())
        assertTrue(deal.path("verifierSource").asText().contains("function verify(text)"))

        runner("/api/runner/deals/$id/propose", mapOf("party" to "alex", "feeCents" to 150, "bondCents" to 40, "note" to "Opening.")).andExpect { status { isNoContent() } }
        runner("/api/runner/jobs/${buyerTurn.path("eventId").asText()}/report", mapOf("outcome" to "MOVED")).andExpect { status { isNoContent() } }
        assertEquals(listOf(OutboxType.AGENT_TURN_SELLER), outbox.live(id), "the other side's turn is next")

        val sellerTurn = claim()
        runner("/api/runner/deals/$id/accept", mapOf("party" to "maya", "note" to "Fine.")).andExpect { status { isNoContent() } }
        runner("/api/runner/jobs/${sellerTurn.path("eventId").asText()}/report", mapOf("outcome" to "MOVED")).andExpect { status { isNoContent() } }
        claim().also { runner("/api/runner/deals/$id/accept", mapOf("party" to "alex", "note" to "Agreed.")) }
        assertEquals(ContractState.TERMS_LOCKED, contracts.find(id)!!.state)
        assertTrue(claude.calls.size == 1, "only the draft used this process's Claude")
    }

    @Test
    fun `a refused move comes back with its code, marked as a refusal the agent can retry`() {
        val id = startJob()
        runner("/api/runner/deals/$id/accept", mapOf("party" to "alex", "note" to null)).andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("nothing_to_accept") }
            jsonPath("$.refused") { value(true) }
        }
    }

    @Test
    fun `a failure is retried, and a stopped agent ends the step and says so on the deal`() {
        val id = startJob()
        val turn = claim()
        runner("/api/runner/jobs/${turn.path("eventId").asText()}/report", mapOf("outcome" to "FAILED", "reason" to "connection reset")).andExpect { status { isNoContent() } }
        assertEquals(listOf(OutboxType.AGENT_TURN_BUYER), outbox.live(id), "still queued, with backoff")

        jdbc.sql("update outbox_event set next_attempt_at = now() where aggregate_id = :a").param("a", id).update()
        val again = claim()
        assertEquals(2, again.path("attempt").asInt())
        runner("/api/runner/jobs/${again.path("eventId").asText()}/report", mapOf("outcome" to "STOPPED", "reason" to "alex's agent: Claude declined this turn")).andExpect { status { isNoContent() } }
        assertTrue(outbox.live(id).isEmpty())
        assertEquals("agent_stopped: alex's agent: Claude declined this turn", contracts.find(id)!!.attentionReason)
        runner("/api/runner/jobs/${again.path("eventId").asText()}/report", mapOf("outcome" to "MOVED")).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("job_closed") }
        }
    }

    @Test
    fun `a turn cannot end as a delivery`() {
        startJob()
        runner("/api/runner/jobs/${claim().path("eventId").asText()}/report", mapOf("outcome" to "DELIVERED")).andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("outcome_mismatch") }
        }
    }

    @Test
    fun `only the runner, and only while the agents are remote`() {
        runner("/api/runner/claim", bearer = "wrong").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("bad_runner_token") }
        }
        hosting.remote = false
        runner("/api/runner/claim").andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("agents_in_process") }
        }
    }

    @Test
    fun `the status says where the agents run and when the runner last asked for work`() {
        runner("/api/runner/claim").andExpect { status { isNoContent() } }
        val status = json(mvc.get("/api/status"))
        assertEquals("astropods", status.path("agentsHost").asText())
        assertNotNull(status.path("runnerSeenAt").textValue())
    }

    /** POSTs the draft on another thread, since it waits for the runner; the test thread plays the runner. */
    private fun draftInBackground(sentence: String): CompletableFuture<MvcResult> = CompletableFuture.supplyAsync {
        mvc.post("/api/agents/jobs/draft") { contentType = MediaType.APPLICATION_JSON; content = mapper.writeValueAsString(mapOf("sentence" to sentence)) }.andReturn()
    }

    private fun claimDraft(): JsonNode {
        val job = json(runner("/api/runner/claim?drafts=true&waitSeconds=5").andExpect { status { isOk() } })
        assertEquals("DRAFT", job.path("kind").asText())
        return job
    }

    @Test
    fun `with the agents remote, a draft is written by the runner and this process needs no Claude`() {
        claude.available = false
        assertTrue(json(mvc.get("/api/status")).path("agentsConfigured").asBoolean(), "the runner holds the key, so drafting stays on")
        runner("/api/runner/claim?drafts=true").andExpect { status { isNoContent() } }
        val asked = draftInBackground("50-word blurb, max \$2, by 18:00")

        val job = claimDraft()
        assertEquals("50-word blurb, max \$2, by 18:00", job.path("sentence").asText())
        assertTrue(job.path("contractId").isNull, "a draft is not a deal yet")
        assertEquals(clock.instant(), Instant.parse(job.path("now").asText()), "the control plane's clock")
        assertEquals("Europe/Istanbul", job.path("zone").asText(), "the control plane's zone")

        val deadline = clock.instant().plus(Duration.ofHours(3))
        val proposal = mapOf("jobText" to "A 50-word blurb.", "feeCapCents" to 200, "deadlineAt" to deadline.toString(), "test" to """{"checks":[{"type":"word_count","equals":50}]}""")
        runner("/api/runner/drafts/${job.path("eventId").asText()}/report", mapOf("outcome" to "DRAFTED", "proposal" to proposal)).andExpect { status { isNoContent() } }

        val response = asked.get(10, TimeUnit.SECONDS).response
        assertEquals(200, response.status)
        val draft = mapper.readTree(response.contentAsString)
        assertEquals(200, draft.path("feeCapMinor").asInt())
        assertEquals(deadline, Instant.parse(draft.path("deadlineAt").asText()))
        assertTrue(draft.path("verifier").path("source").asText().contains("function verify(text)"), "the test is compiled here, from the runner's answer")
        assertTrue(claude.calls.isEmpty(), "this process's Claude was never called")
        runner("/api/runner/drafts/${job.path("eventId").asText()}/report", mapOf("outcome" to "REJECTED", "reason" to "late")).andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("draft_closed") }
        }
    }

    @Test
    fun `a sentence the runner rejects comes back as job_rejected with its reason, and an old runner is never handed a draft`() {
        runner("/api/runner/claim?drafts=true").andExpect { status { isNoContent() } }
        val asked = draftInBackground("a blurb by 18:00")
        Thread.sleep(300)
        runner("/api/runner/claim").andExpect { status { isNoContent() } }

        val job = claimDraft()
        runner("/api/runner/drafts/${job.path("eventId").asText()}/report", mapOf("outcome" to "DRAFTED")).andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("no_proposal") }
        }
        runner("/api/runner/drafts/${job.path("eventId").asText()}/report", mapOf("outcome" to "MOVED")).andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.code") { value("outcome_mismatch") }
        }
        runner("/api/runner/drafts/${job.path("eventId").asText()}/report", mapOf("outcome" to "REJECTED", "reason" to "no price: say the most you will pay")).andExpect { status { isNoContent() } }
        val response = asked.get(10, TimeUnit.SECONDS).response
        assertEquals(422, response.status)
        assertEquals("job_rejected", mapper.readTree(response.contentAsString).path("code").asText())
        assertEquals("no price: say the most you will pay", mapper.readTree(response.contentAsString).path("detail").asText())
    }

    @Test
    fun `without a connected runner that drafts, a draft is refused at once, not left waiting`() {
        hosting.runnerSeenAt = null
        hosting.draftRunnerSeenAt = null
        mvc.post("/api/agents/jobs/draft") { contentType = MediaType.APPLICATION_JSON; content = """{"sentence":"a blurb"}""" }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.code") { value("runner_not_connected") }
        }
        // A runner built before drafting moved asks for work, but would never take the draft.
        runner("/api/runner/claim").andExpect { status { isNoContent() } }
        mvc.post("/api/agents/jobs/draft") { contentType = MediaType.APPLICATION_JSON; content = """{"sentence":"a blurb"}""" }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.code") { value("runner_cannot_draft") }
        }
        assertTrue(claude.calls.isEmpty())
    }
}
