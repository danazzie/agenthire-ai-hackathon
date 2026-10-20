package mutualstake.api

import mutualstake.config.MutualStakeProperties
import mutualstake.contract.ContractRepository
import mutualstake.contract.NegotiationService
import mutualstake.contract.OfferKind
import mutualstake.contract.SellerType
import mutualstake.funding.FundingService
import mutualstake.hold.HoldRepository
import mutualstake.money.Money
import mutualstake.outbox.OutboxRepository
import mutualstake.outbox.OutboxType
import mutualstake.party.Party
import mutualstake.party.PartyRepository
import mutualstake.paypal.PayPalGateway
import mutualstake.session.SessionService
import mutualstake.verifier.VerifierRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** v1 has two seeded identities and no login: the acting party names itself in this header. */
const val PARTY_HEADER = "X-Party"

data class SaveVerifierRequest(val source: String, val language: String = "javascript")

data class OpenContractRequest(val seller: String, val jobText: String, val feeCapMinor: Long, val sellerType: SellerType = SellerType.AGENT)

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
) {
    @GetMapping("/status")
    fun status() = StatusView(paypal.configured, props.paypal.webhooksConfigured, parties.all().map(::PartyView), SessionView(sessions.current()))

    /** Stores a verifier and returns its SHA-256. Step 4 replaces this with compilation and a sandboxed dry run. */
    @PostMapping("/verifiers")
    fun saveVerifier(@RequestBody request: SaveVerifierRequest): ResponseEntity<VerifierView> {
        if (request.language != "javascript") throw ApiException.unprocessable("unsupported_language", "verifiers are javascript")
        if (request.source.isBlank()) throw ApiException.unprocessable("empty_verifier", "a verifier needs source")
        return ResponseEntity.status(HttpStatus.CREATED).body(VerifierView(verifiers.save(request.source, request.language)))
    }

    @GetMapping("/verifiers/{id}")
    fun verifier(@PathVariable id: UUID) = VerifierView(verifiers.byId(id) ?: throw ApiException.notFound("verifier $id"))

    @PostMapping("/contracts")
    fun open(@RequestHeader(PARTY_HEADER) handle: String, @RequestBody request: OpenContractRequest): ResponseEntity<ContractView> {
        val contract = negotiation.open(party(handle), request.seller, request.jobText, Money(request.feeCapMinor), request.sellerType)
        return ResponseEntity.status(HttpStatus.CREATED).body(views.of(contract))
    }

    @GetMapping("/contracts")
    fun list(): List<ContractView> = contracts.inSession(sessions.current().id).map(views::of)

    @GetMapping("/contracts/{id}")
    fun get(@PathVariable id: UUID): ContractView = views.of(contracts.find(id) ?: throw ApiException.notFound("contract $id"))

    @PostMapping("/contracts/{id}/offers")
    fun offer(@RequestHeader(PARTY_HEADER) handle: String, @PathVariable id: UUID, @RequestBody request: OfferRequest): ContractView {
        val contract = negotiation.offer(
            id, party(handle), request.kind, Money(request.feeMinor), Money(request.bondMinor), request.deadlineAt, request.verifierId, request.note,
        )
        return views.of(contract)
    }

    @PostMapping("/contracts/{id}/cancel")
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
    fun check(@PathVariable id: UUID): ResponseEntity<Map<String, Any>> {
        val hold = holds.find(id) ?: throw ApiException.notFound("hold $id")
        outbox.enqueue(hold.id, OutboxType.CHECK_ORDER)
        return ResponseEntity.accepted().body(mapOf("hold" to hold.id, "queued" to "CHECK_ORDER"))
    }

    private fun party(handle: String): Party =
        parties.byHandle(handle) ?: throw ApiException(HttpStatus.UNAUTHORIZED, "unknown_party", "no party '$handle'; send $PARTY_HEADER with a seeded handle")
}
