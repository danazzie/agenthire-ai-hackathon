package mutualstake.verifier

import com.fasterxml.jackson.databind.JsonNode

/** A test that cannot be compiled, with a one-line reason (US-1). */
class UncompilableTestException(message: String) : RuntimeException(message)

/**
 * One check in a structured test. The buyer's agent chooses checks from this fixed vocabulary; it never
 * writes verifier code, and nothing a delivery says can add a check.
 *
 * Each check reports what it found (detail, worded to read inside the verdict sentence), what the test
 * asks for (expected), and sometimes why (evidence).
 */
sealed interface CheckSpec {
    /** Canonical JSON: fixed key order, so the same test always compiles to the same bytes. */
    fun canonical(): String

    /** The JavaScript block that runs this check against `text` and pushes its result. */
    fun code(): String
}

data class WordCount(val equals: Int?, val min: Int?, val max: Int?) : CheckSpec {
    override fun canonical() = listOfNotNull(
        "\"type\":\"word_count\"", equals?.let { "\"equals\":$it" }, min?.let { "\"min\":$it" }, max?.let { "\"max\":$it" },
    ).joinToString(",", "{", "}")

    override fun code(): String {
        val (passes, expected) = when {
            equals != null -> "n === $equals" to "exactly $equals words"
            min != null && max != null -> "n >= $min && n <= $max" to "$min to $max words"
            min != null -> "n >= $min" to "at least $min words"
            else -> "n <= $max" to "at most $max words"
        }
        return """
        |  {
        |    const n = ms.words(text).length;
        |    checks.push(ms.check("word_count", $passes, ms.plural(n, "word"), ${js(expected)}));
        |  }
        |""".trimMargin()
    }
}

data class Language(val equals: String) : CheckSpec {
    override fun canonical() = """{"type":"language","equals":${js(equals)}}"""

    override fun code() = """
        |  {
        |    const ok = ms.isEnglish(text);
        |    checks.push(ms.check("language", ok, ok ? "English" : "not English", "English"));
        |  }
        |""".trimMargin()
}

data object NotPlaceholder : CheckSpec {
    override fun canonical() = """{"type":"not_placeholder"}"""

    override fun code() = """
        |  {
        |    const found = ms.placeholder(text);
        |    checks.push(ms.check("not_placeholder", found === null, found === null ? "no placeholder text" : "placeholder text detected", "no placeholder text", found));
        |  }
        |""".trimMargin()
}

data class Contains(val phrase: String) : CheckSpec {
    override fun canonical() = """{"type":"contains","phrase":${js(phrase)}}"""

    override fun code() = """
        |  {
        |    const phrase = ${js(phrase)};
        |    const ok = text.toLowerCase().includes(phrase.toLowerCase());
        |    checks.push(ms.check("contains", ok, ok ? `mentions "${'$'}{phrase}"` : `does not mention "${'$'}{phrase}"`, `mentions "${'$'}{phrase}"`));
        |  }
        |""".trimMargin()
}

data class NotContains(val phrase: String) : CheckSpec {
    override fun canonical() = """{"type":"not_contains","phrase":${js(phrase)}}"""

    override fun code() = """
        |  {
        |    const phrase = ${js(phrase)};
        |    const ok = !text.toLowerCase().includes(phrase.toLowerCase());
        |    checks.push(ms.check("not_contains", ok, ok ? `does not mention "${'$'}{phrase}"` : `mentions "${'$'}{phrase}"`, `does not mention "${'$'}{phrase}"`));
        |  }
        |""".trimMargin()
}

data class CharCount(val min: Int?, val max: Int?) : CheckSpec {
    override fun canonical() = listOfNotNull("\"type\":\"char_count\"", min?.let { "\"min\":$it" }, max?.let { "\"max\":$it" }).joinToString(",", "{", "}")

    override fun code(): String {
        val passes = listOfNotNull(min?.let { "n >= $it" }, max?.let { "n <= $it" }).joinToString(" && ")
        val expected = when {
            min != null && max != null -> "$min to $max characters"
            min != null -> "at least $min characters"
            else -> "at most $max characters"
        }
        return """
        |  {
        |    const n = Array.from(text.normalize("NFC")).length;
        |    checks.push(ms.check("char_count", $passes, `${'$'}{n} characters`, ${js(expected)}));
        |  }
        |""".trimMargin()
    }
}

data class VerifierSpec(val checks: List<CheckSpec>) {
    fun canonical(): String = checks.joinToString(",", """{"checks":[""", "]}") { it.canonical() }

    companion object {
        const val MAX_CHECKS = 12
        val TYPES = listOf("word_count", "language", "not_placeholder", "contains", "not_contains", "char_count")

        /** Parses and validates a structured test. Anything unexpected is refused with a one-line reason. */
        fun parse(node: JsonNode?): VerifierSpec {
            val checks = node?.path("checks")
            if (checks == null || !checks.isArray || checks.isEmpty) fail("the test needs at least one check")
            if (checks.size() > MAX_CHECKS) fail("a test has at most $MAX_CHECKS checks")
            return VerifierSpec(checks.map(::check))
        }

        private fun check(c: JsonNode): CheckSpec {
            val type = c.path("type").takeIf { it.isTextual }?.asText() ?: fail("every check needs a type, one of ${TYPES.joinToString()}")
            fun allow(vararg fields: String) {
                val extra = c.fieldNames().asSequence().filter { it != "type" && it !in fields }.toList()
                if (extra.isNotEmpty()) fail("$type takes ${fields.joinToString(" or ").ifEmpty { "no parameters" }}, not ${extra.joinToString()}")
            }
            fun int(field: String, max: Int): Int? {
                val v = c.get(field) ?: return null
                if (!v.isIntegralNumber || v.asLong() < 1 || v.asLong() > max) fail("$type.$field must be a whole number from 1 to $max")
                return v.asInt()
            }
            fun phrase(): String {
                val v = c.get("phrase")
                if (v == null || !v.isTextual || v.asText().isBlank() || v.asText().length > 200) fail("$type.phrase must be 1 to 200 characters")
                return v.asText()
            }
            return when (type) {
                "word_count" -> {
                    allow("equals", "min", "max")
                    val spec = WordCount(int("equals", 100_000), int("min", 100_000), int("max", 100_000))
                    if (spec.equals == null && spec.min == null && spec.max == null) fail("word_count needs equals, min or max")
                    if (spec.equals != null && (spec.min != null || spec.max != null)) fail("word_count takes either equals or min/max, not both")
                    if (spec.min != null && spec.max != null && spec.min > spec.max) fail("word_count.min is above word_count.max")
                    spec
                }
                "language" -> {
                    allow("equals")
                    val lang = c.path("equals").asText("")
                    if (lang != "en") fail("language: only English (en) is supported in v1")
                    Language(lang)
                }
                "not_placeholder" -> {
                    allow()
                    NotPlaceholder
                }
                "contains" -> {
                    allow("phrase")
                    Contains(phrase())
                }
                "not_contains" -> {
                    allow("phrase")
                    NotContains(phrase())
                }
                "char_count" -> {
                    allow("min", "max")
                    val spec = CharCount(int("min", 1_000_000), int("max", 1_000_000))
                    if (spec.min == null && spec.max == null) fail("char_count needs min or max")
                    if (spec.min != null && spec.max != null && spec.min > spec.max) fail("char_count.min is above char_count.max")
                    spec
                }
                else -> fail("unknown check '$type'; supported: ${TYPES.joinToString()}")
            }
        }

        private fun fail(reason: String): Nothing = throw UncompilableTestException(reason)
    }
}

/**
 * A JSON string literal that is also a safe JavaScript string literal and safe inside a // comment:
 * U+2028 and U+2029 end a line in JavaScript, so they are escaped along with control characters.
 */
internal fun js(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch.code < 0x20 || ch == ' ' || ch == ' ' -> append("\\u%04x".format(ch.code))
            else -> append(ch)
        }
    }
    append('"')
}
