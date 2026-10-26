package mutualstake.verifier

import mutualstake.crypto.Sha256
import mutualstake.db.instant
import mutualstake.db.uuid
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * A compiled test. Its SHA-256 is what both PayPal orders carry as custom_id (US-18). [spec] is the
 * structured test it was compiled from; offers accept only verifiers that have one.
 */
data class Verifier(val id: UUID, val source: String, val language: String, val sha256: String, val compiledAt: Instant, val spec: String?)

@Repository
class VerifierRepository(private val jdbc: JdbcClient) {
    private val row = RowMapper { rs, _ ->
        Verifier(rs.uuid("id"), rs.getString("source"), rs.getString("language"), rs.getString("sha256"), rs.instant("compiled_at"), rs.getString("spec"))
    }

    /** Stores the source once; the same source always resolves to the same verifier. */
    fun save(source: String, spec: String? = null, language: String = "javascript"): Verifier {
        val sha = Sha256.hex(source)
        jdbc.sql(
            """
            insert into verifier (id, source, language, sha256, spec) values (:id, :source, :language, :sha, cast(:spec as jsonb))
            on conflict (sha256) do nothing
            """,
        ).param("id", UUID.randomUUID()).param("source", source).param("language", language).param("sha", sha).param("spec", spec).update()
        return bySha256(sha)!!
    }

    fun byId(id: UUID): Verifier? = jdbc.sql("select * from verifier where id = :id").param("id", id).query(row).optional().orElse(null)

    fun bySha256(sha: String): Verifier? = jdbc.sql("select * from verifier where sha256 = :sha").param("sha", sha).query(row).optional().orElse(null)
}
