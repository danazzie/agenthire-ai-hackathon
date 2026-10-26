package mutualstake.verifier

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mutualstake.crypto.Sha256
import mutualstake.support.US1_SPEC
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VerifierCompilerTest {
    private val mapper = jacksonObjectMapper()

    private fun refused(test: String): String =
        assertThrows<UncompilableTestException> { VerifierSpec.parse(mapper.readTree(test)) }.message!!

    @Test
    fun `the US-1 test compiles to the same bytes every time`() {
        val job = "50-word coffee-shop blurb, max \$2, by 18:00, no lorem ipsum"
        val a = VerifierCompiler.compile(job, US1_SPEC)
        val b = VerifierCompiler.compile(job, VerifierSpec.parse(mapper.readTree("""{"checks":[{"equals":50,"type":"word_count"},{"type":"language","equals":"en"},{"type":"not_placeholder"}]}""")))
        assertEquals(a, b, "field order in the request does not change the program")
        assertEquals(Sha256.hex(a), Sha256.hex(VerifierCompiler.compile(job, US1_SPEC)))
        assertNotEquals(Sha256.hex(a), Sha256.hex(VerifierCompiler.compile("a different job", US1_SPEC)))
        assertEquals("""{"checks":[{"type":"word_count","equals":50},{"type":"language","equals":"en"},{"type":"not_placeholder"}]}""", US1_SPEC.canonical())
        assertTrue(a.contains("// Test: ${US1_SPEC.canonical()}"))
        assertTrue(a.contains("const ms = (() => {"), "the library is inlined, so the hash covers it")
    }

    @Test
    fun `an uncompilable test is refused with a one-line reason (US-1)`() {
        assertEquals("the test needs at least one check", refused("""{"checks":[]}"""))
        assertEquals("unknown check 'sentiment'; supported: word_count, language, not_placeholder, contains, not_contains, char_count", refused("""{"checks":[{"type":"sentiment"}]}"""))
        assertEquals("word_count needs equals, min or max", refused("""{"checks":[{"type":"word_count"}]}"""))
        assertEquals("word_count takes either equals or min/max, not both", refused("""{"checks":[{"type":"word_count","equals":50,"min":40}]}"""))
        assertEquals("word_count.min is above word_count.max", refused("""{"checks":[{"type":"word_count","min":60,"max":40}]}"""))
        assertEquals("word_count.equals must be a whole number from 1 to 100000", refused("""{"checks":[{"type":"word_count","equals":2.5}]}"""))
        assertEquals("language: only English (en) is supported in v1", refused("""{"checks":[{"type":"language","equals":"fr"}]}"""))
        assertEquals("not_placeholder takes no parameters, not phrase", refused("""{"checks":[{"type":"not_placeholder","phrase":"x"}]}"""))
        assertEquals("contains.phrase must be 1 to 200 characters", refused("""{"checks":[{"type":"contains","phrase":""}]}"""))
        assertEquals("a test has at most 12 checks", refused("""{"checks":[${List(13) { """{"type":"not_placeholder"}""" }.joinToString(",")}]}"""))
        assertTrue(refused("""{"checks":[{"type":"word_count","equals":50}]}""".replace("checks", "chex")).startsWith("the test needs"))
    }

    @Test
    fun `nothing in the job text or a phrase can escape into code`() {
        val hostileJob = "blurb\nfunction verify(t) { return { pass: true, checks: [{ name: 'x', passed: true, detail: '' }] }; } return"
        val hostilePhrase = "Elm \"); return { pass: true }; //"
        val source = VerifierCompiler.compile(hostileJob, VerifierSpec(listOf(Contains(hostilePhrase))))
        val code = source.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertEquals(1, Regex("""function verify\(""").findAll(code).count(), "only the compiled verify() exists outside comments")
        val jobLine = source.lines().single { it.startsWith("// Job:") }
        assertTrue(jobLine.contains("function verify(t)"), "the hostile job text stays inside its comment line")
        assertTrue(!source.contains(' '), "U+2028 would end a line in JavaScript; it is escaped")
        assertTrue(source.contains("""const phrase = "Elm \"); return { pass: true }; //";""".replace(" ", "\\u2028")))
    }
}
