package mutualstake.support

import mutualstake.db.TestPostgres
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/** One Spring context for every integration test: real Postgres, fake PayPal, a clock tests can move. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPostgres::class, FakePayPalConfig::class, FakeClaudeConfig::class)
annotation class IntegrationTest
