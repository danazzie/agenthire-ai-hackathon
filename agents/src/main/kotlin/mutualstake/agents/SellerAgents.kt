package mutualstake.agents

/**
 * Maya's agents: the seller runs one agent per kind of short writing job, all on the same runner and all paid into
 * Maya's one PayPal account. The buyer's agent picks one when it drafts the job, and the pick stays on the deal, so
 * every turn and the delivery are made by the same agent. Each one's work is short English text, because the
 * verifier's checks (words, characters, phrases, language, placeholders) are what can judge it.
 *
 * [usualCents] and [floorCents] are its asking price and the least it takes; each [example] is a sentence the
 * buyer's agent can turn into a job this agent agrees to (its cap is above the floor).
 */
enum class SellerSpecialty(
    val title: String,
    val does: String,
    val usualCents: Long,
    val floorCents: Long,
    val craft: String,
    val example: String,
) {
    BLURB(
        "Blurb writer",
        "short marketing blurbs: a paragraph about a business, a product line or an event",
        180, 140,
        "Write vivid, concrete marketing copy in plain sentences. Name the business when the job names it.",
        "50-word coffee-shop blurb, max \$2, by 18:00, no lorem ipsum",
    ),
    TAGLINE(
        "Tagline writer",
        "taglines, slogans, headlines and names: one short line",
        90, 60,
        "Write one memorable line, no more. The verifier counts every character, spaces and punctuation included, " +
            "so count before you answer when the test limits characters.",
        "A tagline for Spokes, a bike repair shop, at most 40 characters, must include Spokes, max \$1, by 18:00",
    ),
    SOCIAL(
        "Social post writer",
        "posts for X, LinkedIn or Instagram, with hashtags when asked",
        120, 80,
        "Write one post in a confident, friendly voice, ready to publish. Put the hashtags the job asks for at the end, " +
            "spelled exactly as given. A post for X fits in 280 characters.",
        "A tweet announcing the Friday launch of the Orbit budgeting app, at most 280 characters, must include #OrbitLaunch, max \$1.50, by 20:00",
    ),
    PRODUCT(
        "Product description writer",
        "product descriptions and shop listings",
        220, 160,
        "Write a shop-ready description: what it is, what it is made of, why it is worth buying. No headings or bullets.",
        "An 80 to 120 word product description for a bamboo toothbrush that mentions compostable and never says plastic-free, max \$3, by 18:00 tomorrow",
    ),
    BIO(
        "Bio writer",
        "short professional bios: speaker, author and about-me paragraphs",
        200, 150,
        "Write in the third person unless the job asks for the first. Use only facts the job gives; never invent " +
            "employers, awards or numbers.",
        "A 60 to 90 word speaker bio for Priya Rao, a Kotlin engineer, that mentions Priya Rao and Kotlin, max \$2.50, by 17:00",
    ),
    EMAIL(
        "Email writer",
        "short emails: thank-you notes, invitations, announcements and outreach",
        150, 100,
        "Write the email body only: a greeting, the message, a sign-off. No subject line. Sign off without a name " +
            "unless the job gives one, and never leave a slot like [Name] to fill in.",
        "A 60 to 100 word thank-you email to the hackathon judges that mentions HireMind, no placeholders, max \$2, by 21:00",
    ),
    ;

    /** How the job and the deal name it, e.g. "tagline". */
    val id: String = name.lowercase()

    companion object {
        /** Every agent's deliverable is at most this many words, so a draft, a dry run and a retry fit in one step. */
        const val MAX_WORDS = 150

        /** The agent with this [id], or null if Maya has none by that name. */
        fun of(id: String?): SellerSpecialty? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }

        /** Deals opened before the roster have no agent on record: the blurb writer made them. */
        fun forDeal(id: String?): SellerSpecialty = of(id) ?: BLURB
    }
}
