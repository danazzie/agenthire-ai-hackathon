package mutualstake.db

import org.springframework.jdbc.core.simple.JdbcClient
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Raw-SQL test data. Every call makes fresh rows: nothing in the ledger can be cleaned up. */
class Fixtures(private val jdbc: JdbcClient) {

    data class Locked(val contractId: UUID, val buyerId: UUID, val sellerId: UUID, val verifierSha256: String,
                      val feeMinor: Long, val bondMinor: Long)

    fun party(role: String): UUID {
        val id = UUID.randomUUID()
        val handle = "$role-${id.toString().take(8)}"
        jdbc.sql("insert into party (id, handle, display_name, paypal_email) values (:id, :handle, :name, :email)")
            .param("id", id).param("handle", handle).param("name", "$role agent").param("email", "$handle@personal.example.com")
            .update()
        return id
    }

    fun verifier(source: String = "export function verify(text) { return { pass: true, checks: [] }; } // ${UUID.randomUUID()}"): Pair<UUID, String> {
        val id = UUID.randomUUID()
        val sha = sha256Hex(source)
        jdbc.sql("insert into verifier (id, source, language, sha256) values (:id, :source, 'javascript', :sha)")
            .param("id", id).param("source", source).param("sha", sha).update()
        return id to sha
    }

    /** An archived session of its own, so raw fixtures never count against a live session's mandate. */
    fun archivedSession(): UUID {
        val id = UUID.randomUUID()
        jdbc.sql("insert into session (id, cap_minor, max_jobs, archived_at) values (:id, 3000, 2, now())").param("id", id).update()
        return id
    }

    fun lockedContract(feeMinor: Long = 150, bondMinor: Long = 40): Locked {
        val session = archivedSession()
        val buyer = party("buyer")
        val seller = party("seller")
        val (verifierId, sha) = verifier()
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            insert into contract (id, session_id, buyer_id, seller_id, seller_type, job_text, fee_cap_minor, fee_minor,
                                  bond_minor, deadline_at, verifier_id, terms_hash, state)
            values (:id, :session, :buyer, :seller, 'AGENT', '50-word coffee-shop blurb', 200, :fee,
                    :bond, :deadline, :verifier, :terms, 'TERMS_LOCKED')
            """,
        )
            .param("id", id).param("session", session).param("buyer", buyer).param("seller", seller)
            .param("fee", feeMinor).param("bond", bondMinor)
            .param("deadline", Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS).toTimestamptz())
            .param("verifier", verifierId).param("terms", sha256Hex("terms $id"))
            .update()
        return Locked(id, buyer, seller, sha, feeMinor, bondMinor)
    }

    /** Inserts a hold; [columns] adds or overrides any column, e.g. state or authorization_id. */
    fun hold(c: Locked, side: String, columns: Map<String, Any?> = emptyMap()): UUID {
        val id = UUID.randomUUID()
        val fee = side == "BUYER_FEE"
        val values = linkedMapOf<String, Any?>(
            "id" to id,
            "contract_id" to c.contractId,
            "side" to side,
            "payer_id" to if (fee) c.buyerId else c.sellerId,
            "amount_minor" to if (fee) c.feeMinor else c.bondMinor,
            "custom_id" to c.verifierSha256,
            "request_id" to "req-$id",
        )
        values.putAll(columns)
        val names = values.keys.joinToString(", ")
        val params = values.keys.joinToString(", ") { ":$it" }
        var spec = jdbc.sql("insert into hold ($names) values ($params)")
        for ((k, v) in values) spec = spec.param(k, v)
        spec.update()
        return id
    }
}
