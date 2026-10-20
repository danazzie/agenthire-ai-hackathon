package mutualstake.contract

import mutualstake.api.ApiException
import mutualstake.funding.FundingService
import mutualstake.money.Money
import mutualstake.party.Party
import mutualstake.party.PartyRepository
import mutualstake.session.SessionRepository
import mutualstake.session.SessionService
import mutualstake.verifier.VerifierRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Post a job (US-1) and negotiate it (US-8). Terms lock only when both parties' latest messages are
 * ACCEPTs of identical {fee, bond, deadline, verifier}; locking opens both holds in the same transaction.
 */
@Service
class NegotiationService(
    private val contracts: ContractRepository,
    private val offers: OfferRepository,
    private val parties: PartyRepository,
    private val verifiers: VerifierRepository,
    private val sessions: SessionRepository,
    private val sessionService: SessionService,
    private val funding: FundingService,
    private val clock: Clock,
) {
    @Transactional
    fun open(buyer: Party, sellerHandle: String, jobText: String, feeCap: Money, sellerType: SellerType = SellerType.AGENT): Contract {
        if (sellerType != SellerType.AGENT) throw ApiException.unprocessable("out_of_scope", "v1 is agent to agent; hiring a person on camera is backlog")
        val seller = parties.byHandle(sellerHandle) ?: throw ApiException.unprocessable("unknown_party", "no party '$sellerHandle'")
        if (seller.id == buyer.id) throw ApiException.unprocessable("same_party", "buyer and seller must be different parties")
        if (jobText.isBlank()) throw ApiException.unprocessable("invalid_job", "describe the job in one sentence")
        if (feeCap.minor <= 0) throw ApiException.unprocessable("invalid_amount", "the price cap must be positive")
        return contracts.insertDraft(sessionService.current().id, buyer.id, seller.id, sellerType, jobText.trim(), feeCap)
    }

    @Transactional
    fun offer(contractId: UUID, party: Party, kind: OfferKind, fee: Money, bond: Money, deadline: Instant, verifierId: UUID, note: String?): Contract {
        val contract = contracts.lockForUpdate(contractId) ?: throw ApiException.notFound("contract $contractId")
        if (contract.state != ContractState.DRAFT) {
            throw ApiException.conflict("terms_locked", "terms are locked; changing them needs a void and a new deal (US-9)")
        }
        val counterparty = when (party.id) {
            contract.buyerId -> contract.sellerId
            contract.sellerId -> contract.buyerId
            else -> throw ApiException(HttpStatus.FORBIDDEN, "not_a_party", "${party.handle} is not a party to this deal")
        }
        if (fee.minor <= 0 || bond.minor <= 0) throw ApiException.unprocessable("invalid_amount", "fee and bond must both be positive")
        if (bond.minor * 2 > fee.minor) throw ApiException.unprocessable("bond_too_large", "the bond is never above 50% of the fee (US-7)")
        verifiers.byId(verifierId) ?: throw ApiException.unprocessable("unknown_verifier", "no verifier $verifierId")
        val terms = Terms(fee, bond, Terms.normalizeDeadline(deadline), verifierId)
        if (!terms.deadlineAt.isAfter(clock.instant())) throw ApiException.unprocessable("deadline_passed", "the deadline must be in the future")
        if (kind == OfferKind.ACCEPT && fee > contract.feeCap) {
            throw ApiException.unprocessable("over_cap", "the buyer's cap is ${contract.feeCap}; a fee of $fee cannot be accepted")
        }
        val completes = kind == OfferKind.ACCEPT &&
            offers.latestBy(contractId, counterparty)?.let { it.kind == OfferKind.ACCEPT && it.terms == terms } == true
        if (completes) checkMandate(contract, fee)
        offers.insert(contractId, party.id, kind, terms, note?.take(500))
        if (completes) lock(contract, terms)
        return contracts.find(contractId)!!
    }

    /** US-25: over-cap is refused here, loudly, not queued. The session row lock serialises concurrent locks. */
    private fun checkMandate(contract: Contract, fee: Money) {
        val session = sessions.lockForUpdate(contract.sessionId)
        if (sessions.isArchived(session.id)) throw ApiException.conflict("session_archived", "this deal belongs to an archived session")
        val usage = sessions.usage(session.id)
        if (usage.jobs + 1 > session.maxJobs) {
            throw ApiException.unprocessable("mandate_exceeded", "this session allows ${session.maxJobs} job(s) and ${usage.jobs} already locked (US-25)")
        }
        if (usage.committed + fee > session.cap) {
            throw ApiException.unprocessable("mandate_exceeded", "this session's cap is ${session.cap} with ${usage.committed} committed; $fee more would exceed it (US-25)")
        }
    }

    private fun lock(contract: Contract, terms: Terms) {
        val hash = verifiers.byId(terms.verifierId)!!.sha256
        val canonical = TermsDocument.canonical(contract.id, contract.buyerId, contract.sellerId, terms.fee, terms.bond, terms.deadlineAt, hash)
        contracts.lockTerms(contract.id, terms, TermsDocument.hash(canonical), "both parties accepted identical terms")
        funding.openHolds(contracts.find(contract.id)!!)
    }
}
