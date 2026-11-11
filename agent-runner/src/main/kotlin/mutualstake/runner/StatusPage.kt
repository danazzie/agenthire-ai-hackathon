package mutualstake.runner

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import mutualstake.agents.ReportOutcome
import mutualstake.agents.SellerSpecialty
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors

/**
 * The runner's own page on Astropods (its `frontend` interface, port 80): what it is connected to, what it has
 * done lately, and the last error. Read-only; the deal screen itself stays on the control plane's site.
 */
class StatusPage(private val config: RunnerConfig, private val state: RunnerState) {
    private var server: HttpServer? = null

    fun start(port: Int) {
        server = HttpServer.create(InetSocketAddress(port), 0).apply {
            executor = Executors.newFixedThreadPool(2) { r -> Thread(r, "status-page").apply { isDaemon = true } }
            createContext("/health") { it.reply(200, "text/plain", "ok\n") }
            createContext("/status.json") { it.reply(200, "application/json", ControlPlane.json.writeValueAsString(snapshot())) }
            createContext("/") { it.reply(if (it.requestURI.path == "/") 200 else 404, "text/html; charset=utf-8", html()) }
            start()
        }
    }

    fun stop() = server?.stop(0)

    private fun HttpExchange.reply(status: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", type)
        responseHeaders.add("Cache-Control", "no-store")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    fun snapshot(): Map<String, Any?> = mapOf(
        "configured" to config.missing.isEmpty(),
        "missing" to config.missing,
        "controlPlane" to config.controlPlaneUrl,
        "workers" to config.workers,
        "startedAt" to state.startedAt,
        "lastContact" to state.lastContact,
        "claimed" to state.claimed.get(),
        "lastError" to state.lastError,
        "lastErrorAt" to state.lastErrorAt,
        "recent" to state.recent.take(20).map {
            mapOf("at" to it.at, "contractId" to it.job.contractId, "kind" to it.job.kind, "attempt" to it.job.attempt, "outcome" to it.report.outcome, "reason" to it.report.reason, "ms" to it.took.toMillis())
        },
    )

    private fun html(): String {
        val now = Instant.now()
        val connected = state.lastContact?.let { Duration.between(it, now) < Duration.ofSeconds(60) } == true
        val health = when {
            config.missing.isNotEmpty() -> "Not configured" to "bad"
            connected -> "Connected" to "ok"
            else -> "Waiting for the control plane" to "warn"
        }
        val rows = state.recent.take(20).joinToString("") {
            val tone = when (it.report.outcome) {
                ReportOutcome.FAILED, ReportOutcome.STOPPED -> "bad"
                ReportOutcome.NO_DEAL, ReportOutcome.COULD_NOT_MEET_TEST, ReportOutcome.REJECTED -> "warn"
                else -> "ok"
            }
            "<tr><td>${ago(it.at, now)}</td><td><code>${it.job.contractId?.toString()?.take(8) ?: "sentence"}</code></td><td>${label(it.job.kind.name)}</td>" +
                "<td><span class=\"pill $tone\">${label(it.report.outcome.name)}</span>${it.report.reason?.let { r -> "<div class=\"why\">${esc(r)}</div>" }.orEmpty()}</td>" +
                "<td class=\"num\">${it.took.toMillis()} ms</td></tr>"
        }.ifEmpty { "<tr><td colspan=\"5\" class=\"empty\">No steps yet. Start a job on the HireMind screen and the agents' turns appear here.</td></tr>" }
        return """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <meta http-equiv="refresh" content="5"><title>HireMind agents</title>
            <style>
              :root { --bg:#f7f7f5; --card:#fff; --ink:#1b1b1a; --mute:#6b6b66; --line:#e4e4df; --ok:#1f7a4d; --warn:#9a6700; --bad:#b42318; }
              @media (prefers-color-scheme: dark) { :root { --bg:#141413; --card:#1d1d1b; --ink:#ededea; --mute:#a3a39c; --line:#2e2e2b; --ok:#5cc28f; --warn:#e0b14a; --bad:#f07a6e; } }
              * { box-sizing:border-box } body { margin:0; background:var(--bg); color:var(--ink); font:15px/1.5 system-ui, sans-serif; }
              main { max-width:880px; margin:0 auto; padding:24px 16px; } h1 { font-size:22px; margin:0 0 4px } p { color:var(--mute); margin:0 0 20px }
              .grid { display:grid; grid-template-columns:repeat(auto-fit, minmax(180px, 1fr)); gap:12px; margin-bottom:20px }
              .card { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:12px 14px } .card b { display:block; font-size:18px }
              .card span { color:var(--mute); font-size:13px } table { width:100%; border-collapse:collapse; background:var(--card); border:1px solid var(--line); border-radius:10px; overflow:hidden }
              th, td { text-align:left; padding:8px 10px; border-bottom:1px solid var(--line); vertical-align:top } th { color:var(--mute); font-weight:500; font-size:13px }
              .num { text-align:right; white-space:nowrap } .empty { color:var(--mute); text-align:center; padding:20px } .why { color:var(--mute); font-size:13px; word-break:break-word }
              .pill { font-size:13px; font-weight:600 } .ok { color:var(--ok) } .warn { color:var(--warn) } .bad { color:var(--bad) } .scroll { overflow-x:auto }
            </style></head><body><main>
            <h1>HireMind agents</h1>
            <p>The buyer's agent and the seller's ${SellerSpecialty.entries.size} Claude agents, one per kind of job. They negotiate and deliver through the control plane's protocol; only the control plane moves money.</p>
            <div class="grid">
              <div class="card"><span>Status</span><b class="${health.second}">${health.first}</b></div>
              <div class="card"><span>Steps run</span><b>${state.claimed.get()}</b></div>
              <div class="card"><span>Last contact</span><b>${state.lastContact?.let { ago(it, now) } ?: "never"}</b></div>
              <div class="card"><span>Up since</span><b>${ago(state.startedAt, now)}</b></div>
            </div>
            ${if (config.missing.isNotEmpty()) "<p class=\"bad\">Set ${config.missing.joinToString(", ")} and redeploy.</p>" else ""}
            ${state.lastError?.let { "<p>Last error ${state.lastErrorAt?.let { at -> ago(at, now) }.orEmpty()}: <span class=\"bad\">${esc(it)}</span></p>" }.orEmpty()}
            <h2 style="font-size:16px;margin:20px 0 8px">Maya's agents</h2>
            <div class="scroll"><table><thead><tr><th>Agent</th><th>Does</th><th class="num">Usual price</th></tr></thead><tbody>${SellerSpecialty.entries.joinToString("") { "<tr><td>${esc(it.title)}</td><td class=\"why\">${esc(it.does)}</td><td class=\"num\">$${it.usualCents / 100}.${(it.usualCents % 100).toString().padStart(2, '0')}</td></tr>" }}</tbody></table></div>
            <h2 style="font-size:16px;margin:20px 0 8px">Recent steps</h2>
            <div class="scroll"><table><thead><tr><th>When</th><th>Deal</th><th>Step</th><th>Outcome</th><th class="num">Took</th></tr></thead><tbody>$rows</tbody></table></div>
            <p style="margin-top:16px">Control plane: <code>${esc(config.controlPlaneUrl.ifBlank { "not set" })}</code></p>
            </main></body></html>
        """.trimIndent()
    }

    companion object {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        fun label(name: String) = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)

        fun ago(at: Instant, now: Instant): String {
            val s = Duration.between(at, now).seconds.coerceAtLeast(0)
            return when {
                s < 60 -> "${s}s ago"
                s < 3600 -> "${s / 60}m ago"
                s < 86400 -> "${s / 3600}h ago"
                else -> "${s / 86400}d ago"
            }
        }
    }
}
