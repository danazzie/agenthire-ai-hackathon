package mutualstake.api

import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import mutualstake.agents.Claude
import mutualstake.config.MutualStakeProperties
import mutualstake.contract.ContractRepository
import mutualstake.contract.NegotiationService
import mutualstake.contract.OfferKind
import mutualstake.contract.SellerType
import mutualstake.delivery.DeliveryService
import mutualstake.funding.FundingService
import mutualstake.hold.HoldRepository
import mutualstake.money.Money
import mutualstake.outbox.OutboxRepository
import mutualstake.outbox.OutboxType
import mutualstake.party.Party
import mutualstake.party.PartyRepository
import mutualstake.paypal.PayPalCallLog
import mutualstake.paypal.PayPalGateway
import mutualstake.session.SessionService
import mutualstake.settlement.SettlementService
import mutualstake.verifier.VerifierRepository
import mutualstake.verifier.VerifierService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** v1 has two seeded identities and no login: the acting party names itself in this header. */
const val PARTY_HEADER = "X-Party"

/** The plain-English job and the structured test an agent compiled it into: {"checks": [ ... ]}. */
data class CompileRequest(val jobText: String, val test: JsonNode?)

data class TextRequest(val text: String)

data class OpenContractRequest(
    val seller: String,
    val jobText: String,
    val feeCapMinor: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, defaultValue = "AGENT")
    val sellerType: SellerType = SellerType.AGENT,
)

data class OfferRequest(
    val kind: OfferKind,
    val feeMinor: Long,
    val bondMinor: Long,
    val deadlineAt: Instant,
    val verifierId: UUID,
    val note: String? = null,
)

/**
 * The protocol surface agents use. Note what is missing on purpose: there is no endpoint that captures,
 * voids or pays. Money moves only on PayPal's confirmations and, from step 5, the verifier's verdict.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Deals", description = "Compile a test, negotiate terms, fund both holds, deliver, confirm the first capture.")
class ContractController(
    private val negotiation: NegotiationService,
    private val funding: FundingService,
    private val contracts: ContractRepository,
    private val holds: HoldRepository,
    private val parties: PartyRepository,
    private val verifiers: VerifierRepository,
    private val sessions: SessionService,
    private val views: ContractViews,
    private val outbox: OutboxRepository,
    private val paypal: PayPalGateway,
    private val props: MutualStakeProperties,
    private val verifierService: VerifierService,
    private val deliveryService: DeliveryService,
    private val settlement: SettlementService,
    private val callLog: PayPalCallLog,
    private val claude: Claude,
) {
    @GetMapping("/status")
    @Operation(summary = "Read the control plane status and the current session")
    fun status() = StatusView(paypal.configured, props.paypal.webhooksConfigured, claude.available, parties.all().map(::PartyView), SessionView(sessions.current()))

    /** Compiles a structured test into a verifier and returns its source and SHA-256 (US-1, US-9). */
    @PostMapping("/verifiers/compile")
    @Operation(summary = "Compile a structured test into a verifier")
    @ResponseStatus(HttpStatus.CREATED)
    fun compile(@RequestBody request: CompileRequest): VerifierView = VerifierView(verifierService.compile(request.jobText, request.test))

    /** Runs a verifier on any text with nothing at stake: the seller's agent checks its work first. */
    @PostMapping("/verifiers/{id}/dry-run")
    @Operation(summary = "Run a verifier on any text, with nothing at stake")
    fun dryRun(@PathVariable id: UUID, @RequestBody request: TextRequest) = DryRunView(verifierService.dryRun(id, request.text))

    @GetMapping("/verifiers/{id}")
    @Operation(summary = "Get a verifier and its source")
    fun verifier(@PathVariable id: UUID) = VerifierView(verifiers.byId(id) ?: throw ApiException.notFound("verifier $id"))

    @PostMapping("/contracts")
    @Operation(summary = "Open a deal (as the buyer)")
    @ResponseStatus(HttpStatus.CREATED)
    fun open(@RequestHeader(PARTY_HEADER) handle: String, @RequestBody request: OpenContractRequest): ContractView {
        val contract = negotiation.open(party(handle), request.seller, request.jobText, Money(request.feeCapMinor), request.sellerType)
        return views.of(contract)
    }

    @GetMapping("/contracts")
    @Operation(summary = "List this session's deals")
    fun list(): List<ContractView> = contracts.inSession(sessions.current().id).map(views::of)

    @GetMapping("/contracts/{id}")
    @Operation(summary = "Get a deal: terms, both jars, ledger and history")
    fun get(@PathVariable id: UUID): ContractView = views.of(contracts.find(id) ?: throw ApiException.notFound("contract $id"))

    @PostMapping("/contracts/{id}/offers")
    @Operation(summary = "Propose or accept terms")
    fun offer(@RequestHeader(PARTY_HEADER) handle: String, @PathVariable id: UUID, @RequestBody request: OfferRequest): ContractView {
        val contract = negotiation.offer(
            id, party(handle), request.kind, Money(request.feeMinor), Money(request.bondMinor), request.deadlineAt, request.verifierId, request.note,
        )
        return views.of(contract)
    }

    /** The seller delivers. The text is data: the agreed verifier judges it; nothing in it is obeyed (US-20). */
    @PostMapping("/contracts/{id}/deliveries")
    @Operation(summary = "Deliver the work (as the seller)")
    @ResponseStatus(HttpStatus.CREATED)
    fun deliver(@RequestHeader(PARTY_HEADER) handle: String, @PathVariable id: UUID, @RequestBody request: TextRequest): ContractView {
        deliveryService.submit(id, party(handle), request.text)
        return views.of(contracts.find(id)!!)
    }

    /**
     * US-16: a human confirms the first capture of a session. It only releases the settlement the verdict
     * already decided; it cannot capture on its own. Not an agent tool: the agents never get this endpoint.
     */
    @PostMapping("/contracts/{id}/confirm")
    @Operation(summary = "Confirm the session's first capture (US-16)")
    fun confirm(@PathVariable id: UUID): ContractView = views.of(settlement.confirm(id))

    /** The PayPal calls behind a deal, each with its PayPal-Request-Id and debug id. Reads (polling) only on request. */
    @GetMapping("/contracts/{id}/paypal-calls")
    @Operation(summary = "List a deal's PayPal calls")
    fun paypalCalls(@PathVariable id: UUID, @RequestParam(defaultValue = "false") reads: Boolean): List<Map<String, Any?>> =
        callLog.forContract(id, reads).map { (side, c) ->
            mapOf("side" to side, "operation" to c.operation, "method" to c.method, "path" to c.path, "requestId" to c.requestId,
                "httpStatus" to c.httpStatus, "debugId" to c.debugId, "errorName" to c.errorName, "errorIssue" to c.errorIssue)
        }

    @PostMapping("/contracts/{id}/cancel")
    @Operation(summary = "Cancel a deal before both holds are placed")
    fun cancel(@RequestHeader(PARTY_HEADER) handle: String, @PathVariable id: UUID): ContractView {
        val party = party(handle)
        val contract = contracts.find(id) ?: throw ApiException.notFound("contract $id")
        if (party.id != contract.buyerId && party.id != contract.sellerId) {
            throw ApiException(HttpStatus.FORBIDDEN, "not_a_party", "${party.handle} is not a party to this deal")
        }
        funding.cancel(id, "cancelled by ${party.handle} before both holds were placed")
        return views.of(contracts.find(id)!!)
    }

    /** A nudge after the payer returns from PayPal. It only asks PayPal again; it never sets a state itself. */
    @PostMapping("/holds/{id}/check")
    @Operation(summary = "Ask PayPal again about a hold")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun check(@PathVariable id: UUID): Map<String, Any> {
        val hold = holds.find(id) ?: throw ApiException.notFound("hold $id")
        outbox.enqueue(hold.id, OutboxType.CHECK_ORDER)
        return mapOf("hold" to hold.id, "queued" to "CHECK_ORDER")
    }

    private fun party(handle: String): Party =
        parties.byHandle(handle) ?: throw ApiException(HttpStatus.UNAUTHORIZED, "unknown_party", "no party '$handle'; send $PARTY_HEADER with a seeded handle")
}
