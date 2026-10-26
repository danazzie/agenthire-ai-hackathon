package mutualstake.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

@Configuration
class AppConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

/** The outbox poller and the sweeper run on a schedule unless tests switch it off and drive them by hand. */
@Configuration
@EnableScheduling
@ConditionalOnProperty("mutualstake.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig
