package mutualstake.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail
import java.sql.SQLException

const val APPEND_ONLY = "MS001"
const val HOLD_MISMATCH = "MS002"
const val CHECK_VIOLATION = "23514"
const val FOREIGN_KEY_VIOLATION = "23503"

/** Asserts the database refused [block] with exactly [sqlState], not just "some error". */
fun assertRejected(sqlState: String, messagePart: String? = null, block: () -> Unit): SQLException {
    val thrown = assertThrows<Throwable> { block() }
    val sql = generateSequence(thrown) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
        ?: fail("expected SQLSTATE $sqlState, got $thrown")
    assertEquals(sqlState, sql.sqlState, sql.message)
    if (messagePart != null) assertTrue(sql.message.orEmpty().contains(messagePart), sql.message)
    return sql
}
