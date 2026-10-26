package mutualstake.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.servers.Server
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The API description springdoc serves at /v3/api-docs (and the Swagger UI at /swagger-ui.html). */
@Configuration
class OpenApiConfig(private val props: MutualStakeProperties) {
    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Mutual Stake API")
                .version("0.1.0")
                .license(License().name("MIT").url("https://opensource.org/licenses/MIT"))
                .description(
                    """
                    Bilateral stakes on PayPal for agent-to-agent work. The buyer's fee and the seller's bond are
                    authorized, never captured up front. A compiled test, whose SHA-256 is on both PayPal orders as
                    `custom_id`, decides which one is captured and which one is voided.

                    There is deliberately no endpoint that captures, voids, refunds or pays: money moves only on
                    PayPal's confirmations and the verifier's verdict, and the first capture of a session waits for
                    a human to confirm it. Mutations name the acting party in the `X-Party` header (`alex` is the
                    buyer, `maya` the seller); v1 has no login. Refusals are RFC 9457 problem details with a
                    stable `code`, such as `mandate_exceeded` or `no_credentials`.
                    """.trimIndent(),
                ),
        )
        .servers(listOf(Server().url(props.publicUrl).description("This control plane")))

    /** Every refusal is a problem document with a stable code (ApiErrors), so every operation says so. */
    @Bean
    fun problemResponses(): OpenApiCustomizer = OpenApiCustomizer { api ->
        api.components.addSchemas(
            "Problem",
            ObjectSchema()
                .description("RFC 9457 problem details. `code` is stable; `detail` is one human-readable line.")
                .addProperty("code", StringSchema().example("mandate_exceeded"))
                .addProperty("title", StringSchema())
                .addProperty("status", IntegerSchema())
                .addProperty("detail", StringSchema())
                .addProperty("instance", StringSchema())
                .addProperty("type", StringSchema())
                .required(listOf("code", "title", "status")),
        )
        val refusal = ApiResponse()
            .description("A refusal, e.g. not_found, unknown_party, mandate_exceeded, not_funded, deal_in_flight, no_credentials")
            .content(Content().addMediaType("application/problem+json", MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/Problem"))))
        api.paths.values.flatMap { it.readOperations() }.forEach { it.responses.addApiResponse("default", refusal) }
    }
}
