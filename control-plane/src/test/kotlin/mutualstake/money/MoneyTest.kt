package mutualstake.money

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MoneyTest {
    @Test
    fun `formats PayPal values from integer cents`() {
        assertEquals("1.50", Money(150).toPayPal())
        assertEquals("0.40", Money(40).toPayPal())
        assertEquals("0.00", Money(0).toPayPal())
        assertEquals("1234.05", Money(123405).toPayPal())
    }

    @Test
    fun `parses PayPal values without a floating-point step`() {
        assertEquals(Money(40), Money.parsePayPal("0.40"))
        assertEquals(Money(150), Money.parsePayPal("1.50"))
        assertEquals(Money(150), Money.parsePayPal("1.5"))
        assertEquals(Money(1200), Money.parsePayPal("12"))
        // 0.1 + 0.2 style drift cannot happen: every cent value round-trips exactly.
        for (cents in 0L..100_000L) assertEquals(Money(cents), Money.parsePayPal(Money(cents).toPayPal()))
    }

    @Test
    fun `rejects anything that is not a plain PayPal amount`() {
        for (bad in listOf("", "1.", ".50", "1.234", "-1.00", "1e2", " 1.00", "1,00", "NaN")) {
            assertThrows<IllegalArgumentException>(bad) { Money.parsePayPal(bad) }
        }
    }

    @Test
    fun `money is never negative`() {
        assertThrows<IllegalArgumentException> { Money(-1) }
        assertThrows<IllegalArgumentException> { Money(40) - Money(150) }
    }
}
