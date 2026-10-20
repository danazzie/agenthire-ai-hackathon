package mutualstake.paypal

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mutualstake.config.MutualStakeProperties
import mutualstake.crypto.Sha256
import mutualstake.money.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.io.File
import java.time.Clock
import java.util.UUID

/**
 * Opt-in, against the real PayPal sandbox with the repo-root .env: `./gradlew :control-plane:sandboxTest`.
 * It needs no approval click: it creates an order, replays the create, and reads the order back. The order
 * is left unapproved and expires on its own.
 */
@Tag("sandbox")
class PayPalSandboxTest {
    private val env: Map<String, String> by lazy {
        val file = listOf(File("../.env"), File(".env")).firstOrNull(File::isFile)
        val fromFile = file?.readLines().orEmpty()
            .map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim().removeSurrounding("\"") }
        fromFile + System.getenv().filterKeys { it.startsWith("PAYPAL_") }
    }

    private val noLog = object : PayPalCallRecorder {
        override fun record(holdId: UUID?, call: PayPalCall) {
            println("  ${call.operation} ${call.method} ${call.path} -> ${call.httpStatus} debug_id=${call.debugId}")
        }
    }

    @Test
    fun `the Kotlin client creates an AUTHORIZE order that PayPal echoes back with the verifier hash`() {
        assumeTrue(env["PAYPAL_CLIENT_ID"].orEmpty().isNotBlank(), "no sandbox credentials in .env")
        val props = MutualStakeProperties(paypal = MutualStakeProperties.PayPal(clientId = env.getValue("PAYPAL_CLIENT_ID"), clientSecret = env.getValue("PAYPAL_CLIENT_SECRET")))
        val gateway = PayPalRestGateway(RestClient.builder(), props, noLog, jacksonObjectMapper(), Clock.systemUTC())
        val hash = Sha256.hex("Mutual Stake sandbox test verifier ${UUID.randomUUID()}")
        val request = CreateOrderRequest(
            UUID.randomUUID(), "ms-sandboxtest-${UUID.randomUUID()}:create", "BUYER_FEE", "Mutual Stake sandbox test order: never approved",
            Money(150), hash, "http://localhost:8088/paypal/return?hold=test", "http://localhost:8088/paypal/cancel?hold=test",
        )

        val created = gateway.createOrder(request)
        val replayed = gateway.createOrder(request)
        val read = gateway.getOrder(created.id, null)

        println("sandbox order ${created.id}: ${created.status}, custom_id ${created.customId}")
        assertEquals("PAYER_ACTION_REQUIRED", created.status)
        assertEquals(hash, created.customId)
        assertTrue(created.approveUrl.orEmpty().startsWith("https://www.sandbox.paypal.com/"), created.approveUrl)
        assertEquals(created.id, replayed.id, "same PayPal-Request-Id, same order")
        assertEquals(hash, read.customId)
        assertEquals("PAYER_ACTION_REQUIRED", read.status)
        assertNull(read.authorization)
    }
}
