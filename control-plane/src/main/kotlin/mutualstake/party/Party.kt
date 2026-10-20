package mutualstake.party

import mutualstake.config.MutualStakeProperties
import mutualstake.db.uuid
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.util.UUID

data class Party(val id: UUID, val handle: String, val displayName: String, val paypalEmail: String)

@Repository
class PartyRepository(private val jdbc: JdbcClient) {
    private val row = RowMapper { rs, _ -> Party(rs.uuid("id"), rs.getString("handle"), rs.getString("display_name"), rs.getString("paypal_email")) }

    fun all(): List<Party> = jdbc.sql("select * from party order by handle").query(row).list()

    fun byHandle(handle: String): Party? = jdbc.sql("select * from party where handle = :h").param("h", handle).query(row).optional().orElse(null)

    fun byId(id: UUID): Party = jdbc.sql("select * from party where id = :id").param("id", id).query(row).single()

    fun upsert(handle: String, displayName: String, paypalEmail: String): Party =
        jdbc.sql(
            """
            insert into party (id, handle, display_name, paypal_email) values (:id, :handle, :name, :email)
            on conflict (handle) do update set display_name = excluded.display_name, paypal_email = excluded.paypal_email
            returning *
            """,
        ).param("id", UUID.randomUUID()).param("handle", handle).param("name", displayName).param("email", paypalEmail)
            .query(row).single()
}

/** Seeds the buyer and seller from config. Without their sandbox emails nothing is seeded, and the API says so. */
@Component
class PartySeeder(private val parties: PartyRepository, private val props: MutualStakeProperties) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        for ((role, seed) in listOf("buyer" to props.parties.buyer, "seller" to props.parties.seller)) {
            if (seed.paypalEmail.isBlank()) {
                log.warn("no_credentials: no PayPal sandbox email for the {} ({}); party not seeded", role, seed.handle)
                continue
            }
            parties.upsert(seed.handle, seed.displayName, seed.paypalEmail)
        }
    }
}
