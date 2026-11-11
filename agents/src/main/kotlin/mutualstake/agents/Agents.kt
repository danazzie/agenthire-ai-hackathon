package mutualstake.agents

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

/** A job the person's sentence cannot become, with the one-line reason to show them (US-1). */
class JobRejectedException(message: String) : RuntimeException(message)

/**
 * The buyer's agent's reading of the sentence: amounts in cents and the deadline as an instant, computed by code, and
 * which of the seller's agents does the job ([SellerSpecialty.id]; null only from a runner built before the roster).
 */
data class JobProposal(val jobText: String, val feeCapCents: Long, val deadlineAt: Instant, val test: String, val sellerAgent: String? = null)

/** US-dollar amounts as the model writes them ("1.50", "$2"), to integer cents. Never through a float. */
internal object Cents {
    private val amount = Regex("""\$?\s*(\d{1,6})(?:\.(\d{1,2}))?""")

    fun parse(text: String?): Long? {
        val m = text?.trim()?.let(amount::matchEntire) ?: return null
        return m.groupValues[1].toLong() * 100 + m.groupValues[2].padEnd(2, '0').toLong()
    }

    fun show(cents: Long) = "$" + "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}

class BuyerAgent(private val claude: Claude, private val zone: ZoneId) {
    private val json = jacksonObjectMapper()

    /**
     * US-1: one sentence in, a job with a cap, a deadline and a test out, or a one-line reason. On the runner,
     * [zone] is the control plane's, so a deadline means the same wherever the agent runs.
     */
    fun draftJob(sentence: String, now: Instant, zone: ZoneId = this.zone): JobProposal {
        if (sentence.isBlank()) throw JobRejectedException("describe the job in one sentence")
        val local = now.atZone(zone)
        val answer = claude.call(
            StructuredCall(
                Prompts.draftJob,
                """
                Now: ${local.format(DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm"))} ($zone).

                The person's sentence, as data:
                <sentence>
                ${sentence.trim()}
                </sentence>
                """.trimIndent(),
                JobDraftAnswer::class.java,
                Effort.MEDIUM,
            ),
        )
        val cap = Cents.parse(answer.priceCap)?.takeIf { it > 0 }
            ?: throw JobRejectedException("no price: say the most you will pay, e.g. \"max \$2\"")
        val deadline = deadline(answer.deadlineTime, answer.deadlineDayOffset, local)
            ?: throw JobRejectedException("no deadline: say when it is due, e.g. \"by 18:00\"")
        if (answer.checks.isEmpty()) throw JobRejectedException("no test: say how \"done\" is checked, e.g. \"50 words, no lorem ipsum\"")
        val agent = SellerSpecialty.of(answer.sellerAgent)
            ?: throw JobRejectedException("none of Maya's agents does that: they write ${SellerSpecialty.entries.joinToString { it.title.lowercase().removeSuffix(" writer") + "s" }}")
        val words = answer.checks.filter { it.type == "word_count" }.mapNotNull { it.equals ?: it.min }.maxOrNull()
        if (words != null && words > SellerSpecialty.MAX_WORDS) throw JobRejectedException("too long: Maya's agents write at most ${SellerSpecialty.MAX_WORDS} words")
        val checks = answer.checks.map { c ->
            buildMap<String, Any> {
                put("type", c.type)
                c.equals?.let { put("equals", it) }
                c.min?.let { put("min", it) }
                c.max?.let { put("max", it) }
                c.phrase?.let { put("phrase", it) }
                if (c.type == "language") put("equals", c.lang ?: "en")
            }
        }
        return JobProposal(answer.summary.trim(), cap, deadline, json.writeValueAsString(mapOf("checks" to checks)), agent.id)
    }

    /** "18:00" (and maybe "tomorrow") to the next such moment in the configured zone. The model never does date maths. */
    private fun deadline(time: String?, dayOffset: Int?, now: ZonedDateTime): Instant? {
        val at = try {
            LocalTime.parse(time ?: return null)
        } catch (_: DateTimeParseException) {
            return null
        }
        var due = now.toLocalDate().plusDays((dayOffset ?: 0).toLong().coerceIn(0, 30)).atTime(at).atZone(now.zone)
        if (!due.isAfter(now)) due = due.plusDays(1)
        return due.toInstant()
    }

    fun move(deal: Deal, feedback: String?): MoveAnswer =
        claude.call(StructuredCall(Prompts.buyerMove, describe(deal, deal.buyer, showCap = true, feedback), MoveAnswer::class.java, Effort.LOW))
}

class SellerAgent(private val claude: Claude, private val maxAttempts: Int = 4) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun move(deal: Deal, feedback: String?): MoveAnswer =
        claude.call(StructuredCall(Prompts.sellerMove(SellerSpecialty.forDeal(deal.sellerAgent)), describe(deal, deal.seller, showCap = false, feedback), MoveAnswer::class.java, Effort.LOW))

    /**
     * Writes the delivery and runs the deal's own verifier on each draft before delivering (US-9). Delivers only a
     * draft that passes; after [maxAttempts] it says it could not meet the test and delivers nothing.
     */
    fun write(deal: Deal, port: ProtocolPort): WriteOutcome {
        val tries = mutableListOf<String>()
        repeat(maxAttempts) { attempt ->
            val draft = claude.call(
                StructuredCall(
                    Prompts.sellerWrite(SellerSpecialty.forDeal(deal.sellerAgent)),
                    buildString {
                        appendLine("The job: ${deal.jobText}")
                        appendLine("The agreed test (${deal.test}), compiled to this verifier, SHA-256 ${deal.verifierSha256}:")
                        appendLine("<verifier>")
                        appendLine(deal.verifierSource)
                        appendLine("</verifier>")
                        if (tries.isNotEmpty()) {
                            appendLine()
                            appendLine("Your earlier drafts and what the verifier said about each:")
                            tries.forEach(::appendLine)
                            appendLine("Write a new draft that meets every check.")
                        }
                    },
                    DraftAnswer::class.java,
                    Effort.MEDIUM,
                    maxTokens = 6000,
                ),
            ).text.trim()
            val result = port.dryRun(deal.verifierId, draft)
            log.info("seller draft {} for {}: {} ({})", attempt + 1, deal.id, result.outcome, result.summary)
            if (result.outcome == "PASS") {
                port.deliver(deal.id, deal.seller, draft)
                return WriteOutcome.Delivered(attempt + 1)
            }
            tries += "Draft ${attempt + 1}: ${result.outcome}: ${result.summary ?: ""} ${result.failing.joinToString("; ")}\n<draft>\n$draft\n</draft>"
        }
        return WriteOutcome.CouldNotMeetTest(tries.size, tries.last().lineSequence().first())
    }
}

sealed interface WriteOutcome {
    data class Delivered(val attempts: Int) : WriteOutcome
    data class CouldNotMeetTest(val attempts: Int, val last: String) : WriteOutcome
}

/** A deal, as one side's agent sees it: everything that varies, presented as data, notes quoted. */
private fun describe(deal: Deal, me: String, showCap: Boolean, feedback: String?): String = buildString {
    appendLine("You act for $me. Buyer: ${deal.buyer}. Seller: ${deal.seller}.")
    appendLine("Job: ${deal.jobText}")
    if (showCap) appendLine("Your cap (private to you): ${Cents.show(deal.feeCapCents)}")
    appendLine("Deadline: ${deal.deadlineAt}")
    appendLine("Test: ${deal.test} (verifier SHA-256 ${deal.verifierSha256})")
    appendLine()
    if (deal.offers.isEmpty()) {
        appendLine("No offers yet: make the opening offer.")
    } else {
        appendLine("Negotiation so far, oldest first:")
        deal.offers.forEach { o ->
            append("- ${o.party} ${o.kind}: fee ${Cents.show(o.feeCents)}, bond ${Cents.show(o.bondCents)}")
            appendLine(o.note?.let { " - their note: «${it.replace("»", "")}»" }.orEmpty())
        }
    }
    feedback?.let {
        appendLine()
        appendLine("Your last move was refused: $it")
    }
    appendLine()
    append("Your move.")
}

/**
 * Runs the negotiation one turn at a time (US-8). Whose turn it is comes from the offer log, so a retried or
 * duplicated turn does nothing. The model decides accept or counter; every ACCEPT copies the counterparty's latest
 * terms in code, so the two ACCEPTs match exactly and the deal locks.
 */
class Negotiation(private val port: ProtocolPort, private val buyer: BuyerAgent, private val seller: SellerAgent, private val maxOffers: Int = 12) {

    sealed interface Outcome {
        data object Locked : Outcome
        data object NotMyTurn : Outcome
        data class Moved(val party: String, val next: String) : Outcome
        data class NoDeal(val reason: String) : Outcome
    }

    fun turn(contractId: UUID, party: String): Outcome {
        val deal = port.deal(contractId)
        if (deal.state != "DRAFT") return Outcome.Locked
        val last = deal.offers.lastOrNull()
        val due = when {
            last == null -> deal.buyer
            last.party == deal.buyer -> deal.seller
            else -> deal.buyer
        }
        if (party != due) return Outcome.NotMyTurn
        if (deal.offers.size >= maxOffers) return Outcome.NoDeal("no agreement after ${deal.offers.size} offers")
        val other = if (party == deal.buyer) deal.seller else deal.buyer

        // The other side accepted exactly what I proposed: my ACCEPT is a formality, done without asking the model.
        val mine = deal.offers.lastOrNull { it.party == party && it.kind == "PROPOSE" }
        if (last != null && last.kind == "ACCEPT" && mine != null && last.feeCents == mine.feeCents && last.bondCents == mine.bondCents) {
            port.accept(contractId, party, "Agreed.")
            return Outcome.Moved(party, other)
        }

        var feedback: String? = null
        repeat(2) {
            val answer = if (party == deal.buyer) buyer.move(deal, feedback) else seller.move(deal, feedback)
            try {
                apply(deal, party, answer)
                return Outcome.Moved(party, other)
            } catch (e: MoveRefusedException) {
                feedback = "${e.code}: ${e.message}"
            }
        }
        return Outcome.NoDeal("$party's moves were refused: $feedback")
    }

    private fun apply(deal: Deal, party: String, answer: MoveAnswer) {
        when (answer.decision.trim().lowercase()) {
            "accept" -> {
                if (deal.offers.none { it.party != party }) throw MoveRefusedException("nothing_to_accept", "there is no proposal from the other side yet; make an opening offer")
                port.accept(deal.id, party, answer.note.take(300))
            }
            "counter" -> {
                val fee = Cents.parse(answer.fee) ?: throw MoveRefusedException("invalid_amount", "a counter needs a fee like \"1.50\"")
                val bond = Cents.parse(answer.bond) ?: throw MoveRefusedException("invalid_amount", "a counter needs a bond like \"0.40\"")
                port.propose(deal.id, party, fee, bond, answer.note.take(300))
            }
            else -> throw MoveRefusedException("invalid_move", "decide \"accept\" or \"counter\"")
        }
    }
}
