package mutualstake.db

import mutualstake.money.Money
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** pgjdbc binds OffsetDateTime to timestamptz; Instant needs this hop in both directions. */
fun Instant.toTimestamptz(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

fun ResultSet.instant(column: String): Instant = getObject(column, OffsetDateTime::class.java).toInstant()

fun ResultSet.instantOrNull(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

fun ResultSet.money(column: String): Money = Money(getLong(column))

fun ResultSet.moneyOrNull(column: String): Money? = (getObject(column) as Number?)?.let { Money(it.toLong()) }
