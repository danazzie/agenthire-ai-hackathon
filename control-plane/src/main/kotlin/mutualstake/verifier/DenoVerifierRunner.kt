package mutualstake.verifier

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import mutualstake.config.MutualStakeProperties
import mutualstake.crypto.Sha256
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs verifiers in Deno, as a separate process with no permissions at all: no files, no network, no
 * environment, no subprocesses (see resources/verifier/harness.js). The deliverable travels as JSON data
 * on stdin; only the result line carrying this run's random nonce counts.
 */
@Component
class DenoVerifierRunner(private val props: MutualStakeProperties, private val mapper: ObjectMapper) : VerifierRunner {
    private val log = LoggerFactory.getLogger(javaClass)
    private val harness: ByteArray = requireNotNull(javaClass.getResourceAsStream("/verifier/harness.js")) { "verifier harness missing" }.use { it.readAllBytes() }
    val harnessSha256: String = Sha256.hex(harness)
    private val workDir: Path = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "mutualstake-verifier"))
    private val harnessFile: Path = workDir.resolve("harness-$harnessSha256.js").also { if (!Files.exists(it)) Files.write(it, harness) }
    private val io = Executors.newCachedThreadPool { r -> Thread(r, "verifier-io").apply { isDaemon = true } }
    private val random = SecureRandom()
    private val versions = ConcurrentHashMap<String, String>()

    /** Which Deno to run. Settable so a test can point it at a binary that does not exist. */
    @Volatile
    var denoPath: String = resolveDeno(props.verifier.denoPath)

    /** e.g. "deno 2.9.7 (stable, release, aarch64-apple-darwin) / v8 15.0.245.2-rusty". */
    fun runtime(): String = versions.computeIfAbsent(denoPath) { path ->
        val process = try {
            ProcessBuilder(path, "--version").redirectErrorStream(true).start()
        } catch (e: IOException) {
            throw VerifierUnavailableException("verifier_unavailable: cannot start Deno at $path (${e.message})")
        }
        val out = process.inputStream.readNBytes(4096).toString(Charsets.UTF_8)
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw VerifierUnavailableException("verifier_unavailable: $path --version failed")
        }
        out.lines().filter { it.isNotBlank() }.take(2).joinToString(" / ")
    }

    override fun run(source: String, text: String): VerifierRunResult {
        val runtime = runtime()
        val sourceSha = Sha256.hex(source)
        val nonce = HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes))
        val payload = mapper.writeValueAsBytes(mapOf("nonce" to nonce, "source" to source, "text" to text))
        val started = System.nanoTime()
        fun elapsed() = ((System.nanoTime() - started) / 1_000_000).toInt()
        fun error(reason: String, trace: List<String> = emptyList()) =
            VerifierRunResult(RunOutcome.ERROR, emptyList(), null, null, reason, trace, sourceSha, harnessSha256, runtime, elapsed())

        val process = try {
            ProcessBuilder(command()).directory(workDir.toFile()).also { pb ->
                pb.environment().clear()
                pb.environment().putAll(
                    mapOf("DENO_DIR" to workDir.resolve("deno-dir").toString(), "NO_COLOR" to "1", "DENO_NO_UPDATE_CHECK" to "1", "DENO_NO_PROMPT" to "1"),
                )
            }.start()
        } catch (e: IOException) {
            throw VerifierUnavailableException("verifier_unavailable: cannot start Deno at $denoPath (${e.message})")
        }
        val stdout = io.submit<ByteArray> { process.inputStream.readNBytes(MAX_STDOUT) }
        val stderr = io.submit<ByteArray> { process.errorStream.readNBytes(MAX_STDERR) }
        try {
            process.outputStream.use { it.write(payload) }
        } catch (_: IOException) {
            // The process ended before reading its input; its exit code and stderr say why.
        }
        if (!process.waitFor(props.verifier.timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return error("verifier_timeout: no result within ${props.verifier.timeoutMs} ms")
        }
        val out = stdout.get(5, TimeUnit.SECONDS).toString(Charsets.UTF_8)
        val err = stderr.get(5, TimeUnit.SECONDS).toString(Charsets.UTF_8)
        if (process.exitValue() != 0) {
            return error("verifier_crashed: exit ${process.exitValue()}: ${err.lineSequence().firstOrNull { it.isNotBlank() }?.take(300).orEmpty()}")
        }
        val prefix = "MS-RESULT:$nonce:"
        val line = out.lineSequence().firstOrNull { it.startsWith(prefix) } ?: return error("verifier_silent: no result line")
        return interpret(mapper.readTree(line.removePrefix(prefix)), sourceSha, runtime, ::elapsed, ::error)
    }

    private fun interpret(
        node: JsonNode,
        sourceSha: String,
        runtime: String,
        elapsed: () -> Int,
        error: (String, List<String>) -> VerifierRunResult,
    ): VerifierRunResult {
        val trace = node.path("trace").takeIf { it.isArray }?.map { it.asText() }.orEmpty()
        if (!node.path("ok").asBoolean(false)) return error(node.path("error").asText("the verifier failed without saying why"), trace)
        val result = node.path("result")
        val pass = result.path("pass")
        val checksNode = result.path("checks")
        if (!pass.isBoolean || !checksNode.isArray || checksNode.isEmpty) {
            return error("verifier_malformed: verify(text) must return { pass, checks: [ ... ] } with at least one check", trace)
        }
        val checks = mutableListOf<CheckResult>()
        for (c in checksNode) {
            if (!c.path("name").isTextual || !c.path("passed").isBoolean || !c.path("detail").isTextual) {
                return error("verifier_malformed: every check needs a name, passed and detail", trace)
            }
            checks += CheckResult(c.path("name").asText().take(100), c.path("passed").asBoolean(), c.path("detail").asText().take(300))
        }
        if (pass.asBoolean() != checks.all { it.passed }) return error("verifier_malformed: pass disagrees with its own checks", trace)
        val failed = checks.filterNot { it.passed }
        return VerifierRunResult(
            outcome = if (failed.isEmpty()) RunOutcome.PASS else RunOutcome.FAIL,
            checks = checks,
            failingCheck = failed.firstOrNull()?.name,
            summary = (if (failed.isEmpty()) checks else failed).joinToString("; ") { it.detail },
            error = null,
            trace = trace,
            sourceSha256 = sourceSha,
            harnessSha256 = harnessSha256,
            runtime = runtime,
            durationMs = elapsed(),
        )
    }

    private fun command() = listOf(
        denoPath, "run", "--quiet", "--no-prompt", "--no-config", "--no-npm", "--no-remote", "--cached-only", "--no-lock",
        "--v8-flags=--max-old-space-size=${props.verifier.maxHeapMb}", harnessFile.toString(),
    )

    @PreDestroy
    fun close() {
        io.shutdownNow()
    }

    companion object {
        const val DENO_VERSION = "2.9.7"
        private const val MAX_STDOUT = 256 * 1024
        private const val MAX_STDERR = 16 * 1024

        /** Configured path, else the Gradle-pinned download in .tools (repo root or one level up), else PATH. */
        fun resolveDeno(configured: String): String {
            if (configured.isNotBlank()) return configured
            return listOf(".tools", "../.tools")
                .map { File(it, "deno-$DENO_VERSION/deno") }
                .firstOrNull { it.canExecute() }?.absolutePath ?: "deno"
        }
    }
}
