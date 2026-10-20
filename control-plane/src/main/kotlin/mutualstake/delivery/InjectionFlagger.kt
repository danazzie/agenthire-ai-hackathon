package mutualstake.delivery

/**
 * Flags text that tries to instruct whoever reads it (US-20). Flagging is all it does: the verdict
 * comes from the verifier's code, which reads the text as data, so a flag never changes a verdict.
 * The flags are logged, stored on the delivery, and shown next to it.
 */
object InjectionFlagger {
    private val rules: List<Pair<String, Regex>> = listOf(
        "instruction:mark_as_passed" to
            Regex("""\b(mark|treat|score|grade|rate|set|record)\b[^.\n]{0,40}?\b(as\s+)?(passed|a\s+pass|pass|approved|complete[d]?|successful)\b""", RegexOption.IGNORE_CASE),
        "instruction:ignore_previous" to
            Regex("""\b(ignore|disregard|forget|override)\b[^.\n]{0,30}?\b(previous|prior|above|earlier|all|the|any|your)\b[^.\n]{0,30}?\b(instructions?|rules?|prompts?|tests?|checks?|verifier)\b""", RegexOption.IGNORE_CASE),
        "role_prefix" to
            Regex("""(^|\n|[.!?]\s)\s*(reviewer|verifier|system|assistant|developer|admin|moderator|judge|grader)\s*:""", RegexOption.IGNORE_CASE),
        "addressed_to_ai" to
            Regex("""\b(you are|as) (an? )?(ai|assistant|language model|llm|verifier|reviewer|grader)\b""", RegexOption.IGNORE_CASE),
        "verdict_forgery" to
            Regex("""MS-RESULT:|"pass"\s*:\s*true|\bverdict\s*[:=]\s*"?pass\b|\breturn\s*\{?\s*pass\s*:\s*true""", RegexOption.IGNORE_CASE),
    )

    fun flags(text: String): List<String> = rules.filter { (_, pattern) -> pattern.containsMatchIn(text) }.map { it.first }
}
