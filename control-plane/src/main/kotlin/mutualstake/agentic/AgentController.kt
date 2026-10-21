package mutualstake.agentic

import mutualstake.api.ContractView
import mutualstake.api.ContractViews
import mutualstake.api.VerifierView
import mutualstake.contract.ContractRepository
import mutualstake.money.Money
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class DraftRequest(val sentence: String)

data class JobDraftView(val sentence: String, val jobText: String, val feeCapMinor: Long, val deadlineAt: Instant, val verifier: VerifierView)

data class StartRequest(val sentence: String, val jobText: String, val feeCapMinor: Long, val deadlineAt: Instant, val verifierId: UUID)

data class DeliverRequest(val mode: DeliveryMode)

/**
 * Where a person hands a job to the agents. There is no confirm, capture, void or pay here: the agents get the
 * same protocol everyone gets, and the first capture of a session still waits for a human (US-16).
 */
@RestController
@RequestMapping("/api/agents")
class AgentController(private val agents: AgentService, private val views: ContractViews, private val contracts: ContractRepository) {

    /** Screen 1: one sentence in; the cap, the deadline and the compiled test out, for the person to confirm (US-1). */
    @PostMapping("/jobs/draft")
    fun draft(@RequestBody request: DraftRequest): JobDraftView {
        val d = agents.draft(request.sentence)
        return JobDraftView(d.sentence, d.jobText, d.feeCapMinor, d.deadlineAt, VerifierView(d.verifier))
    }

    /** Confirmed: open the deal; the agents negotiate it in the background (screen 2). */
    @PostMapping("/jobs")
    fun start(@RequestBody request: StartRequest): ResponseEntity<ContractView> {
        val contract = agents.start(request.sentence, request.jobText, Money(request.feeCapMinor), request.deadlineAt, request.verifierId)
        return ResponseEntity.status(HttpStatus.CREATED).body(views.of(contract))
    }

    @PostMapping("/contracts/{id}/deliver")
    fun deliver(@PathVariable id: UUID, @RequestBody request: DeliverRequest): ResponseEntity<ContractView> {
        agents.deliver(id, request.mode)
        return ResponseEntity.accepted().body(views.of(contracts.find(id)!!))
    }
}
