package mutualstake.contract

import mutualstake.db.instant
import mutualstake.db.money
import mutualstake.db.toTimestamptz
import mutualstake.db.uuid
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

enum class OfferKind { PROPOSE, ACCEPT }

/** One negotiation message (US-8). Every message carries complete terms; the log is append-only. */
data class Offer(
    val id: UUID,
    val contractId: UUID,
    val partyId: UUID,
    val kind: OfferKind,
    val terms: Terms,
    val note: String?,
    val createdAt: Instant,
)

@Repository
class OfferRepository(private val jdbc: JdbcClient) {
    private val row = RowMapper { rs, _ ->
        Offer(
            id = rs.uuid("id"),
            contractId = rs.uuid("contract_id"),
            partyId = rs.uuid("party_id"),
            kind = OfferKind.valueOf(rs.getString("kind")),
            terms = Terms(rs.money("fee_minor"), rs.money("bond_minor"), rs.instant("deadline_at"), rs.uuid("verifier_id")),
            note = rs.getString("note"),
            createdAt = rs.instant("created_at"),
        )
    }

    fun insert(contractId: UUID, partyId: UUID, kind: OfferKind, terms: Terms, note: String?): Offer {
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            insert into offer (id, contract_id, party_id, kind, fee_minor, bond_minor, deadline_at, verifier_id, note)
            values (:id, :contract, :party, :kind, :fee, :bond, :deadline, :verifier, :note)
            """,
        ).param("id", id).param("contract", contractId).param("party", partyId).param("kind", kind.name)
            .param("fee", terms.fee.minor).param("bond", terms.bond.minor).param("deadline", terms.deadlineAt.toTimestamptz())
            .param("verifier", terms.verifierId).param("note", note).update()
        return jdbc.sql("select * from offer where id = :id").param("id", id).query(row).single()
    }

    fun forContract(contractId: UUID): List<Offer> =
        jdbc.sql("select * from offer where contract_id = :c order by created_at, id").param("c", contractId).query(row).list()

    fun latestBy(contractId: UUID, partyId: UUID): Offer? =
        jdbc.sql("select * from offer where contract_id = :c and party_id = :p order by created_at desc, id desc limit 1")
            .param("c", contractId).param("p", partyId).query(row).optional().orElse(null)
}
