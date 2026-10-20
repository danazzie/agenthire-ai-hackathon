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
) {
    data class PayPal(
        val clientId: String = "",
        val clientSecret: String = "",
        val baseUrl: String = "https://api-m.sandbox.paypal.com",
        val webhookId: String = "",
        val brandName: String = "Mutual Stake (sandbox)",
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

    data class Scheduling(
        val enabled: Boolean = true,
        val outboxPollMs: Long = 500,
        val sweepMs: Long = 3000,
        /** How often an unapproved order is re-read from PayPal, the fallback when no webhook arrives. */
        val orderPollSeconds: Long = 4,
    )
}
