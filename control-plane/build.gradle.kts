import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

group = "mutualstake"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
    compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") }
}

dependencies {
    // Spring Boot 3.5 pins Kotlin 1.9; the explicit Kotlin BOM keeps every kotlin-* artifact on the compiler's version.
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform(libs.kotlin.bom))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation(libs.archunit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    // Real-sandbox tests only run when asked for: ./gradlew :control-plane:sandboxTest
    useJUnitPlatform { excludeTags("sandbox") }
    testLogging { events("passed", "skipped", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

val sandboxTest by tasks.registering(Test::class) {
    description = "Runs the opt-in tests against the real PayPal sandbox, using the repo-root .env."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("sandbox") }
    outputs.upToDateWhen { false }
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
