package mutualstake.money

/** The only currency v1 supports. */
const val CURRENCY = "USD"

/**
 * An amount in minor units (US cents). Money is never a float or a double: amounts are
 * carried as integers end to end and only turned into PayPal's decimal strings at the edge.
 */
@JvmInline
value class Money(val minor: Long) : Comparable<Money> {
    init {
        require(minor >= 0) { "money is never negative: $minor" }
    }

    operator fun plus(other: Money) = Money(Math.addExact(minor, other.minor))

    operator fun minus(other: Money) = Money(Math.subtractExact(minor, other.minor))

    override fun compareTo(other: Money) = minor.compareTo(other.minor)

    /** PayPal's `value` string: 150 -> "1.50". */
    fun toPayPal(): String = "${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"

    override fun toString() = "$" + toPayPal()

    companion object {
        private val PAYPAL_VALUE = Regex("""(\d{1,15})(?:\.(\d{1,2}))?""")

        /** Parses PayPal's `value` string without ever going through a floating-point type. */
        fun parsePayPal(value: String): Money {
            val m = PAYPAL_VALUE.matchEntire(value) ?: throw IllegalArgumentException("not a PayPal amount: '$value'")
            val cents = m.groupValues[2].padEnd(2, '0')
            return Money(Math.addExact(Math.multiplyExact(m.groupValues[1].toLong(), 100L), cents.toLong()))
        }
    }
}
