package mutualstake.runner

import com.sun.net.httpserver.HttpServer
import mutualstake.agents.MoveRefusedException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.util.UUID

/** The wire, against a stand-in control plane: the token goes out, refusals come back as feedback, other problems as failures. */
class ControlPlaneTest {
    private lateinit var server: HttpServer
    private val seen = mutableListOf<String>()
    private var status = 204
    private var body = ""

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                seen += "${ex.requestMethod} ${ex.requestURI} ${ex.requestHeaders.getFirst("Authorization")}"
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(status, if (status == 204) -1 else bytes.size.toLong())
                if (status != 204) ex.responseBody.use { it.write(bytes) } else ex.close()
            }
            start()
        }
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun client() = ControlPlane("http://127.0.0.1:${server.address.port}/", "s3cret")

    @Test
    fun `no step waiting is null, the token is sent, and the runner says it takes drafts`() {
        assertNull(client().claim(0))
        assertEquals("POST /api/runner/claim?waitSeconds=0&drafts=true Bearer s3cret", seen.single())
    }

    @Test
    fun `a refusal becomes MoveRefusedException with its code`() {
        status = 422
        body = """{"code":"over_cap","detail":"fee 5.00 is over the buyer's cap","refused":true}"""
        val e = assertThrows<MoveRefusedException> { client().accept(UUID.randomUUID(), "alex", "OK") }
        assertEquals("over_cap", e.code)
        assertEquals("fee 5.00 is over the buyer's cap", e.message)
    }

    @Test
    fun `any other problem is a failure, not feedback`() {
        status = 409
        body = """{"code":"illegal_transition","detail":"DRAFT to PASS"}"""
        val e = assertThrows<ControlPlaneException> { client().propose(UUID.randomUUID(), "maya", 180, 40, "x") }
        assertEquals(409, e.status)
        assertEquals("illegal_transition", e.code)
    }

    @Test
    fun `missing settings are named, not guessed`() {
        assertEquals(listOf("CONTROL_PLANE_URL", "RUNNER_TOKEN", "ANTHROPIC_API_KEY"), RunnerConfig.fromEnv(emptyMap()).missing)
        assertEquals(80, RunnerConfig.fromEnv(emptyMap()).port)
    }
}
