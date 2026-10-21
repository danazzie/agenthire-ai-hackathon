package mutualstake.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * The deployed screen is a static site on its own origin (WEB_URL). Only that origin may call the API from a
 * browser; with WEB_URL unset (local dev behind the Vite proxy) no cross-origin call is allowed at all.
 */
@Configuration
class WebConfig(private val props: MutualStakeProperties) : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        val origin = props.webUrl.trimEnd('/')
        if (origin.isBlank()) return
        registry.addMapping("/api/**")
            .allowedOrigins(origin)
            .allowedMethods("GET", "POST")
            .allowedHeaders("Content-Type", "X-Party")
            .maxAge(3600)
    }
}
