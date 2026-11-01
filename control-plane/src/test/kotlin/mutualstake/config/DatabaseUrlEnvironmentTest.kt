package mutualstake.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment

/** Render's single Postgres URL becomes Spring's JDBC URL and credentials (render.yaml: DATABASE_URL). */
class DatabaseUrlEnvironmentTest {

    @Test
    fun `a Render connection string becomes a JDBC URL with the credentials apart`() {
        val props = DatabaseUrlEnvironment.jdbcProperties("postgresql://mutualstake:s3cr%40t@dpg-abc123-a:5432/mutualstake")
        assertEquals("jdbc:postgresql://dpg-abc123-a:5432/mutualstake", props["spring.datasource.url"])
        assertEquals("mutualstake", props["spring.datasource.username"])
        assertEquals("s3cr@t", props["spring.datasource.password"], "percent-encoded characters are decoded")
    }

    @Test
    fun `the default port and query parameters survive`() {
        val props = DatabaseUrlEnvironment.jdbcProperties("postgres://u:p@db.example/app?sslmode=require")
        assertEquals("jdbc:postgresql://db.example:5432/app?sslmode=require", props["spring.datasource.url"])
    }

    @Test
    fun `DATABASE_URL wins over the DB_ defaults, and nothing changes without it`() {
        val env = MockEnvironment().withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/mutualstake")
        DatabaseUrlEnvironment().postProcessEnvironment(env, SpringApplication())
        assertEquals("jdbc:postgresql://localhost:5432/mutualstake", env.getProperty("spring.datasource.url"))
        assertNull(env.propertySources.get("DATABASE_URL"))

        env.setProperty("DATABASE_URL", "postgresql://u:p@dpg-x:6543/db")
        DatabaseUrlEnvironment().postProcessEnvironment(env, SpringApplication())
        assertEquals("jdbc:postgresql://dpg-x:6543/db", env.getProperty("spring.datasource.url"))
        assertEquals("u", env.getProperty("spring.datasource.username"))
    }

    @Test
    fun `anything but a postgres URL is refused at startup`() {
        assertThrows<IllegalArgumentException> { DatabaseUrlEnvironment.jdbcProperties("mysql://u:p@h/db") }
    }
}
