package mutualstake.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import java.net.URI
import java.net.URLDecoder

/**
 * Render hands a service its Postgres as one URL (postgresql://user:password@host:port/database) and offers no
 * separate host or port. Spring wants a JDBC URL and the credentials apart, so DATABASE_URL, when set, becomes
 * those three properties and wins over the DB_* defaults in application.yml.
 */
class DatabaseUrlEnvironment : EnvironmentPostProcessor {
    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val url = environment.getProperty("DATABASE_URL")?.takeIf { it.isNotBlank() } ?: return
        environment.propertySources.addFirst(MapPropertySource("DATABASE_URL", jdbcProperties(url)))
    }

    companion object {
        fun jdbcProperties(databaseUrl: String): Map<String, Any> {
            val uri = URI(databaseUrl)
            require(uri.scheme == "postgres" || uri.scheme == "postgresql") { "DATABASE_URL must be a postgres:// or postgresql:// URL" }
            requireNotNull(uri.host) { "DATABASE_URL has no host" }
            val credentials = requireNotNull(uri.rawUserInfo) { "DATABASE_URL has no user" }.split(":", limit = 2)
                .map { URLDecoder.decode(it, Charsets.UTF_8) }
            val port = if (uri.port == -1) 5432 else uri.port
            return mapOf(
                "spring.datasource.url" to "jdbc:postgresql://${uri.host}:$port${uri.rawPath}${uri.rawQuery?.let { "?$it" } ?: ""}",
                "spring.datasource.username" to credentials[0],
                "spring.datasource.password" to credentials.getOrElse(1) { "" },
            )
        }
    }
}
