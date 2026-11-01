package mutualstake.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("mutualstake")
data class MutualStakeProperties(
    /** Public base URL of this service; PayPal sends payers back here after they approve a hold. */
    val publicUrl: String = "http://localhost:8088",
    /** The web app, when deployed, for the link back from the return page. */
    val webUrl: String = "",
    val paypal: PayPal = PayPal(),
    val parties: Parties = Parties(),
    val session: SessionDefaults = SessionDefaults(),
    val scheduling: Scheduling = Scheduling(),
    val verifier: Verifier = Verifier(),
    val agents: Agents = Agents(),
) {
    data class PayPal(
        val clientId: String = "",
        val clientSecret: String = "",
        val baseUrl: String = "https://api-m.sandbox.paypal.com",
        val webhookId: String = "",
        val brandName: String = "HireMind (sandbox)",
    ) {
        val configured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
        val webhooksConfigured: Boolean get() = webhookId.isNotBlank()
    }

    /** The two seeded sandbox identities (US-29). */
    data class Parties(
        val buyer: PartySeed = PartySeed("alex", "Alex's agent"),
        val seller: PartySeed = PartySeed("maya", "Maya's agent"),
    )

    data class PartySeed(val handle: String, val displayName: String, val paypalEmail: String = "")

    /** US-25: what one session's agents may commit, and on how many jobs. */
    data class SessionDefaults(val capMinor: Long = 3000, val maxJobs: Int = 2)

    /** The Claude agents. Without a key they are off, and say so (no_credentials). */
    data class Agents(val anthropicApiKey: String = "", val timezone: String = "", val maxOffers: Int = 12, val writingAttempts: Int = 4) {
        val configured: Boolean get() = anthropicApiKey.isNotBlank()
    }

    /** The sandboxed verifier runtime (Deno). An empty path means the pinned download in .tools, else PATH. */
    data class Verifier(
        val denoPath: String = "",
        val timeoutMs: Long = 5000,
        val maxHeapMb: Int = 64,
        val maxDeliveryChars: Int = 20_000,
    )

    data class Scheduling(
        val enabled: Boolean = true,
        val outboxPollMs: Long = 500,
        val sweepMs: Long = 3000,
        /** First polling interval for an unapproved order (the fallback when no webhook arrives); it then backs off. */
        val orderPollSeconds: Long = 4,
        /** Hard limit on one outbox step (a PayPal call, a verifier run, an agent turn). Past it the step is retried. */
        val handlerTimeoutSeconds: Long = 90,
    )
}
