package mutualstake.db

import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** pgjdbc binds OffsetDateTime to timestamptz; Instant needs this hop in both directions. */
fun Instant.toTimestamptz(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

fun ResultSet.instantOrNull(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
