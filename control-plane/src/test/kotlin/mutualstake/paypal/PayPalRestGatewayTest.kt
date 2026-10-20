package mutualstake.paypal

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mutualstake.config.MutualStakeProperties
import mutualstake.money.Money
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/** The real client's request shapes, checked against what the US-4 spike saw PayPal accept and return. */
class PayPalRestGatewayTest {
    private val base = "https://api-m.sandbox.paypal.com"
    private val hash = "906a15f04468dd6dfd9b898590f12551c73f3c32b21172a6004e144b4e678727"
    private val recorded = mutableListOf<PayPalCall>()
    private val recorder = object : PayPalCallRecorder {
        override fun record(holdId: UUID?, call: PayPalCall) {
            recorded += call
        }
    }

    private fun props(clientId: String = "client", baseUrl: String = base) =
        MutualStakeProperties(paypal = MutualStakeProperties.PayPal(clientId = clientId, clientSecret = "secret", baseUrl = baseUrl))

    private fun gateway(props: MutualStakeProperties = props()): Pair<PayPalRestGateway, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val clock = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC)
        return PayPalRestGateway(builder, props, recorder, jacksonObjectMapper(), clock) to server
    }

    private fun MockRestServiceServer.expectToken() {
        expect(requestTo("$base/v1/oauth2/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString("client:secret".toByteArray())))
            .andExpect(content().string("grant_type=client_credentials"))
            .andRespond(withSuccess("""{"access_token":"T","expires_in":32400,"app_id":"APP-1"}""", MediaType.APPLICATION_JSON))
    }

    @Test
    fun `creates an AUTHORIZE order carrying the verifier hash and the hold's request id`() {
        val (gw, server) = gateway()
        server.expectToken()
        server.expect(requestTo("$base/v2/checkout/orders"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer T"))
            .andExpect(header("PayPal-Request-Id", "ms-h1:create"))
            .andExpect(header("Prefer", "return=representation"))
            .andExpect(jsonPath("$.intent").value("AUTHORIZE"))
            .andExpect(jsonPath("$.purchase_units[0].reference_id").value("SELLER_BOND"))
            .andExpect(jsonPath("$.purchase_units[0].custom_id").value(hash))
            .andExpect(jsonPath("$.purchase_units[0].amount.currency_code").value("USD"))
            .andExpect(jsonPath("$.purchase_units[0].amount.value").value("0.40"))
            .andExpect(jsonPath("$.payment_source.paypal.experience_context.shipping_preference").value("NO_SHIPPING"))
            .andExpect(jsonPath("$.payment_source.paypal.experience_context.return_url").value("http://localhost:8088/paypal/return?hold=h1"))
            .andRespond(
                withSuccess(
                    """{"id":"6AK65289E83342326","status":"PAYER_ACTION_REQUIRED","purchase_units":[{"reference_id":"SELLER_BOND","custom_id":"$hash",
                    "amount":{"currency_code":"USD","value":"0.40"}}],"links":[{"href":"$base/v2/checkout/orders/6AK65289E83342326","rel":"self","method":"GET"},
                    {"href":"https://www.sandbox.paypal.com/checkoutnow?token=6AK65289E83342326","rel":"payer-action","method":"GET"}]}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val order = gw.createOrder(
            CreateOrderRequest(
                UUID.randomUUID(), "ms-h1:create", "SELLER_BOND", "bond", Money(40), hash,
                "http://localhost:8088/paypal/return?hold=h1", "http://localhost:8088/paypal/cancel?hold=h1",
            ),
        )

        server.verify()
        assertEquals("6AK65289E83342326", order.id)
        assertEquals("PAYER_ACTION_REQUIRED", order.status)
        assertEquals(hash, order.customId)
        assertEquals("https://www.sandbox.paypal.com/checkoutnow?token=6AK65289E83342326", order.approveUrl)
        assertEquals(listOf("oauth_token", "create_order"), recorded.map { it.operation })
    }

    @Test
    fun `reads who approved and the authorization PayPal placed`() {
        val (gw, server) = gateway()
        server.expectToken()
        server.expect(requestTo("$base/v2/checkout/orders/O1/authorize"))
            .andExpect(header("PayPal-Request-Id", "ms-h1:authorize"))
            .andRespond(
                withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(
                    """{"id":"O1","status":"COMPLETED","payment_source":{"paypal":{"email_address":"maya@personal.example.com"}},
                    "purchase_units":[{"custom_id":"$hash","payments":{"authorizations":[{"id":"6B127203P6458315P","status":"CREATED",
                    "amount":{"currency_code":"USD","value":"0.40"},"custom_id":"$hash","expiration_time":"2026-11-18T20:39:46Z",
                    "create_time":"2026-10-20T20:39:46Z"}]}}]}""",
                ),
            )

        val order = gw.authorizeOrder("O1", "ms-h1:authorize", UUID.randomUUID())

        assertEquals("maya@personal.example.com", order.payerEmail)
        val auth = order.authorization!!
        assertEquals("6B127203P6458315P", auth.id)
        assertEquals("CREATED", auth.status)
        assertEquals(Money(40), auth.amount)
        assertEquals(Instant.parse("2026-11-18T20:39:46Z"), auth.expiresAt)
    }

    @Test
    fun `a refusal keeps PayPal's issue and debug id, and is not worth retrying`() {
        val (gw, server) = gateway()
        server.expectToken()
        server.expect(requestTo("$base/v2/checkout/orders/O1/authorize"))
            .andRespond(
                withStatus(HttpStatus.UNPROCESSABLE_ENTITY).header("paypal-debug-id", "f3956608844ff").contentType(MediaType.APPLICATION_JSON)
                    .body("""{"name":"UNPROCESSABLE_ENTITY","details":[{"issue":"ORDER_ALREADY_AUTHORIZED"}],"debug_id":"f3956608844ff"}"""),
            )

        val e = assertThrows<PayPalException> { gw.authorizeOrder("O1", "ms-h1:authorize", UUID.randomUUID()) }

        assertEquals(422, e.httpStatus)
        assertEquals("ORDER_ALREADY_AUTHORIZED", e.issue)
        assertEquals("f3956608844ff", e.debugId)
        assertFalse(e.retryable)
        assertTrue(e.reason.contains("debug_id f3956608844ff"), e.reason)
        assertEquals("f3956608844ff" to "ORDER_ALREADY_AUTHORIZED", recorded.last().debugId to recorded.last().errorIssue)
    }

    @Test
    fun `a 5xx is worth retrying`() {
        val (gw, server) = gateway()
        server.expectToken()
        server.expect(requestTo("$base/v2/checkout/orders/O1")).andRespond(withServerError())
        assertTrue(assertThrows<PayPalException> { gw.getOrder("O1", null) }.retryable)
    }

    @Test
    fun `the access token is fetched once and reused`() {
        val (gw, server) = gateway()
        server.expectToken()
        repeat(2) {
            server.expect(requestTo("$base/v2/checkout/orders/O1"))
                .andRespond(withSuccess("""{"id":"O1","status":"PAYER_ACTION_REQUIRED"}""", MediaType.APPLICATION_JSON))
        }
        gw.getOrder("O1", null)
        gw.getOrder("O1", null)
        server.verify()
    }

    @Test
    fun `without credentials nothing is sent and the error says no_credentials`() {
        val (gw, server) = gateway(props(clientId = ""))
        val e = assertThrows<PayPalException> { gw.getOrder("O1", null) }
        assertEquals("no_credentials", e.reason)
        assertFalse(e.retryable)
        server.verify()
    }

    @Test
    fun `anything but the sandbox is refused at startup`() {
        assertThrows<IllegalArgumentException> { gateway(props(baseUrl = "https://api-m.paypal.com")) }
    }

    @Test
    fun `webhook verification sends the event byte for byte`() {
        val raw = """{"id":"WH-1",  "event_type":"CHECKOUT.ORDER.APPROVED","resource":{"id":"O1"}}"""
        val (gw, server) = gateway(MutualStakeProperties(paypal = MutualStakeProperties.PayPal(clientId = "client", clientSecret = "secret", webhookId = "WEBHOOK-1")))
        server.expectToken()
        server.expect(requestTo("$base/v1/notifications/verify-webhook-signature"))
            .andExpect(content().string(containsString(""""webhook_id":"WEBHOOK-1","webhook_event":$raw}""")))
            .andExpect(content().string(containsString(""""transmission_id":"T-1"""")))
            .andRespond(withSuccess("""{"verification_status":"SUCCESS"}""", MediaType.APPLICATION_JSON))

        assertTrue(gw.verifyWebhook(mapOf("PAYPAL-TRANSMISSION-ID" to "T-1", "PAYPAL-AUTH-ALGO" to "SHA256withRSA"), raw))
        server.verify()
    }
}
