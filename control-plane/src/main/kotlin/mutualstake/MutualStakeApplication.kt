package mutualstake

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class MutualStakeApplication

fun main(args: Array<String>) {
    runApplication<MutualStakeApplication>(*args)
}
