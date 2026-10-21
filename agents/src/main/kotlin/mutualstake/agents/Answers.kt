package mutualstake.agents

import com.fasterxml.jackson.annotation.JsonPropertyDescription

/** What the buyer's agent reads out of the person's one sentence (US-1). Code turns it into money and time. */
data class JobDraftAnswer(
    @get:JsonPropertyDescription("The job, restated plainly in one sentence, without price or deadline.")
    val summary: String,
    @get:JsonPropertyDescription("The most the person will pay, in US dollars with two decimals, e.g. \"2.00\". Null if the sentence gives no price.")
    val priceCap: String?,
    @get:JsonPropertyDescription("The deadline's local time of day, 24-hour HH:mm, e.g. \"18:00\". Null if the sentence gives no deadline.")
    val deadlineTime: String?,
    @get:JsonPropertyDescription("0 if the deadline is today, 1 tomorrow, and so on. Null if the sentence names no day.")
    val deadlineDayOffset: Int?,
    @get:JsonPropertyDescription("The checks that decide 'done', chosen only from the allowed vocabulary.")
    val checks: List<CheckAnswer>,
)

data class CheckAnswer(
    @get:JsonPropertyDescription("One of: word_count, language, not_placeholder, contains, not_contains, char_count.")
    val type: String,
    @get:JsonPropertyDescription("word_count: the exact count; language: null (use lang).")
    val equals: Int?,
    @get:JsonPropertyDescription("word_count or char_count: the minimum, if a range.")
    val min: Int?,
    @get:JsonPropertyDescription("word_count or char_count: the maximum, if a range.")
    val max: Int?,
    @get:JsonPropertyDescription("contains or not_contains: the phrase.")
    val phrase: String?,
    @get:JsonPropertyDescription("language: \"en\". Otherwise null.")
    val lang: String?,
)

/** One negotiation move. The model decides; on a counter it names amounts; an accept carries none. */
data class MoveAnswer(
    @get:JsonPropertyDescription("\"accept\" to accept the other side's latest proposal exactly, or \"counter\" to propose terms.")
    val decision: String,
    @get:JsonPropertyDescription("counter only: the fee in US dollars with two decimals, e.g. \"1.50\". Null on accept.")
    val fee: String?,
    @get:JsonPropertyDescription("counter only: the bond in US dollars with two decimals, e.g. \"0.40\". Null on accept.")
    val bond: String?,
    @get:JsonPropertyDescription("One short sentence to the other side, as in a chat. No markdown.")
    val note: String,
)

/** The seller's delivery draft. */
data class DraftAnswer(
    @get:JsonPropertyDescription("The deliverable itself, exactly as it should be delivered. Nothing else.")
    val text: String,
)
