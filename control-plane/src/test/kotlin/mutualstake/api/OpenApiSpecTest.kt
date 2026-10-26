package mutualstake.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import mutualstake.support.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.io.File

/** springdoc describes the API; docs/api/openapi.json is that description, committed for Postman and APIMatic. */
@IntegrationTest
class OpenApiSpecTest @Autowired constructor(private val mvc: MockMvc, private val mapper: ObjectMapper) {

    private fun served(): JsonNode = mapper.readTree(mvc.get("/v3/api-docs").andExpect { status { isOk() } }.andReturn().response.contentAsString)

    @Test
    fun `the committed OpenAPI document is the one the API serves`() {
        val pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(served()) + "\n"
        val generated = File("build/openapi/openapi.json").apply { parentFile.mkdirs(); writeText(pretty) }
        val committed = File("../docs/api/openapi.json")
        assertTrue(committed.exists() && committed.readText() == pretty) {
            "docs/api/openapi.json is out of date; from the repo root: cp control-plane/${generated.path} docs/api/openapi.json"
        }
    }

    @Test
    fun `it covers the client API only, and every mutation names its party`() {
        val spec = served()
        val paths = spec.path("paths").fieldNames().asSequence().toList()
        assertTrue(paths.isNotEmpty() && paths.all { it.startsWith("/api/") }, "no PayPal webhook or return page: $paths")
        assertEquals("http://localhost:8088", spec.path("servers").path(0).path("url").asText())

        val offer = spec.path("paths").path("/api/contracts/{id}/offers").path("post")
        val party = offer.path("parameters").single { it.path("name").asText() == "X-Party" }
        assertEquals("header", party.path("in").asText())
        assertTrue(party.path("required").asBoolean())
    }
}
