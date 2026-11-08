package mutualstake.runner

import mutualstake.agents.AnthropicClaude
import org.slf4j.LoggerFactory
import java.time.ZoneId
import kotlin.concurrent.thread

/** The runner's settings, all from the environment Astropods injects (astropods.yml `inputs`). */
data class RunnerConfig(
    val controlPlaneUrl: String,
    val runnerToken: String,
    val anthropicApiKey: String,
    val timezone: String,
    val workers: Int,
    val port: Int,
    val maxOffers: Int,
    val writingAttempts: Int,
) {
    /** Required settings that are empty. With any missing the runner serves its page and says so, rather than crash-looping. */
    val missing: List<String>
        get() = listOfNotNull(
            "CONTROL_PLANE_URL".takeIf { controlPlaneUrl.isBlank() },
            "RUNNER_TOKEN".takeIf { runnerToken.isBlank() },
            "ANTHROPIC_API_KEY".takeIf { anthropicApiKey.isBlank() },
        )

    val zone: ZoneId get() = timezone.takeIf { it.isNotBlank() }?.let(ZoneId::of) ?: ZoneId.systemDefault()

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()) = RunnerConfig(
            controlPlaneUrl = env["CONTROL_PLANE_URL"].orEmpty().trim(),
            runnerToken = env["RUNNER_TOKEN"].orEmpty().trim(),
            anthropicApiKey = env["ANTHROPIC_API_KEY"].orEmpty().trim(),
            timezone = env["AGENT_TIMEZONE"].orEmpty().trim(),
            workers = env["RUNNER_WORKERS"]?.toIntOrNull() ?: 2,
            port = env["PORT"]?.toIntOrNull() ?: 80,
            // The same limits the control plane uses in process (MutualStakeProperties.Agents).
            maxOffers = env["AGENT_MAX_OFFERS"]?.toIntOrNull() ?: 12,
            writingAttempts = env["AGENT_WRITING_ATTEMPTS"]?.toIntOrNull() ?: 4,
        )
    }
}

fun main() {
    val log = LoggerFactory.getLogger("mutualstake.runner")
    val config = RunnerConfig.fromEnv()
    val state = RunnerState()
    StatusPage(config, state).start(config.port)
    log.info("status page on port {}", config.port)
    if (config.missing.isNotEmpty()) {
        log.error("not starting the agents: set {}", config.missing.joinToString(", "))
        return
    }
    val runner = Runner(
        ControlPlane(config.controlPlaneUrl, config.runnerToken),
        AnthropicClaude(config.anthropicApiKey),
        config.zone,
        config.maxOffers,
        config.writingAttempts,
        state = state,
    )
    // Several workers, so one deal's long delivery does not hold up another deal's turn.
    repeat(config.workers) { n -> thread(name = "runner-$n", isDaemon = false) { runner.work() } }
    Runtime.getRuntime().addShutdownHook(Thread { runner.stop() })
    log.info("agent runner: {} workers on {}", config.workers, config.controlPlaneUrl)
}
