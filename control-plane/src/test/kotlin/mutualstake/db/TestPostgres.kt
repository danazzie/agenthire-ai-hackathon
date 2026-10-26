package mutualstake.db

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.PostgreSQLContainer

/** One throwaway Postgres per test run, shared by every Spring test context that imports this. */
@TestConfiguration(proxyBeanMethods = false)
class TestPostgres {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer<*> = PostgreSQLContainer<Nothing>("postgres:16-alpine")
}
