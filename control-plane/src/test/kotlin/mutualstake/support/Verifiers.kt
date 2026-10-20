package mutualstake.support

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mutualstake.verifier.Verifier
import mutualstake.verifier.VerifierCompiler
import mutualstake.verifier.VerifierRepository
import mutualstake.verifier.VerifierSpec
import java.io.File
import java.util.UUID

/** The US-1 test: words == 50 AND language == en AND not placeholder(text). */
const val US1_TEST = """{"checks":[{"type":"word_count","equals":50},{"type":"language","equals":"en"},{"type":"not_placeholder"}]}"""

val US1_SPEC: VerifierSpec = VerifierSpec.parse(jacksonObjectMapper().readTree(US1_TEST))

/** A compiled US-1 verifier stored without running it, unique per call so every test gets its own hash. */
fun compiledVerifier(verifiers: VerifierRepository, jobText: String = "50-word coffee-shop blurb, max \$2, by 18:00, no lorem ipsum (${UUID.randomUUID()})"): Verifier =
    verifiers.save(VerifierCompiler.compile(jobText, US1_SPEC), US1_SPEC.canonical())

/** The demo texts, read from PROTOTYPES.md itself so the doc's own copy is what the tests pin. */
object DemoTexts {
    private val prototypes: String by lazy {
        listOf(File("../PROTOTYPES.md"), File("PROTOTYPES.md")).first(File::isFile).readText()
    }

    val pass: String by lazy { Regex("""\| Pass \| \*(.+?)\* \(50 words\) \|""").find(prototypes)!!.groupValues[1] }
    val fail: String by lazy { Regex("""\| Fail \| \*(.+?)\* \(12 words\) \|""").find(prototypes)!!.groupValues[1] }
}

/** The pinned Deno that Gradle installs and passes to every test task. Missing Deno fails tests; it never skips them. */
fun denoPath(): String = System.getProperty("mutualstake.verifier.deno-path")
    ?: error("mutualstake.verifier.deno-path is not set: run the tests through Gradle, which installs the pinned Deno")
