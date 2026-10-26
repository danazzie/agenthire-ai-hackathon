package mutualstake.session

import mutualstake.config.MutualStakeProperties
import mutualstake.db.instant
import mutualstake.db.instantOrNull
import mutualstake.db.money
import mutualstake.db.uuid
import mutualstake.money.Money
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** A demo run and its spending mandate (US-25): at most [maxJobs] jobs and [cap] committed in fees. */
data class Session(val id: UUID, val startedAt: Instant, val cap: Money, val maxJobs: Int, val firstCaptureConfirmedAt: Instant?)

data class MandateUsage(val jobs: Int, val committed: Money)

@Repository
class SessionRepository(private val jdbc: JdbcClient) {
    private val row = RowMapper { rs, _ ->
        Session(rs.uuid("id"), rs.instant("started_at"), rs.money("cap_minor"), rs.getInt("max_jobs"), rs.instantOrNull("first_capture_confirmed_at"))
    }

    fun current(): Session? = jdbc.sql("select * from session where archived_at is null").query(row).optional().orElse(null)

    fun lockForUpdate(id: UUID): Session = jdbc.sql("select * from session where id = :id for update").param("id", id).query(row).single()

    /** A plain read, for views. */
    fun lockFree(id: UUID): Session = jdbc.sql("select * from session where id = :id").param("id", id).query(row).single()

    fun isArchived(id: UUID): Boolean =
        jdbc.sql("select archived_at is not null from session where id = :id").param("id", id).query(Boolean::class.java).single()

    /** Inserts a current session unless one exists (the unique index allows only one). */
    fun createIfNone(cap: Money, maxJobs: Int) {
        jdbc.sql("insert into session (id, cap_minor, max_jobs) values (:id, :cap, :max) on conflict do nothing")
            .param("id", UUID.randomUUID()).param("cap", cap.minor).param("max", maxJobs).update()
    }

    fun archiveCurrent(): Int = jdbc.sql("update session set archived_at = now() where archived_at is null").update()

    /** US-16: a human confirmed this session's first capture; later ones run inside the mandate. */
    fun confirmFirstCapture(id: UUID) {
        jdbc.sql("update session set first_capture_confirmed_at = now() where id = :id and first_capture_confirmed_at is null").param("id", id).update()
    }

    /** Locked deals count against the mandate until they are cancelled. */
    fun usage(id: UUID): MandateUsage =
        jdbc.sql(
            """
            select count(*) as jobs, coalesce(sum(fee_minor), 0) as committed
            from contract where session_id = :id and state not in ('DRAFT', 'CANCELLED')
            """,
        ).param("id", id).query { rs, _ -> MandateUsage(rs.getInt("jobs"), Money(rs.getLong("committed"))) }.single()
}

@Service
class SessionService(private val sessions: SessionRepository, private val props: MutualStakeProperties) {

    @Transactional
    fun current(): Session {
        sessions.current()?.let { return it }
        sessions.createIfNone(Money(props.session.capMinor), props.session.maxJobs)
        return sessions.current()!!
    }

    /** A fresh session with the configured mandate. */
    @Transactional
    fun startNew(): Session = startNew(Money(props.session.capMinor), props.session.maxJobs)

    /**
     * Archives the current session and opens a fresh one. Reset demo (US-31) builds on this; nothing is deleted.
     * (No default arguments here: on a Spring proxy they would be evaluated against the proxy's empty fields.)
     */
    @Transactional
    fun startNew(cap: Money, maxJobs: Int): Session {
        sessions.archiveCurrent()
        sessions.createIfNone(cap, maxJobs)
        return sessions.current()!!
    }
}
