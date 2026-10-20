import org.springframework.boot.gradle.plugin.SpringBootPlugin
import java.security.MessageDigest
import java.util.zip.ZipInputStream

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

// The verifier sandbox runs in Deno. A pinned release is downloaded into .tools and checked against
// these SHA-256s (each verified against Deno's published checksum when pinned), so nobody has to install it.
val denoVersion = "2.9.7"
val denoChecksums = mapOf(
    "aarch64-apple-darwin" to "5cd46d6268f6f78f5d88bdc7159d20bd44cdaa4b3303474839f87ec6fe7ae25c",
    "x86_64-apple-darwin" to "95daaff11c116a52ad54785e7914c8e9c9cdcaba793c5ed929c74ca2d8e6259a",
    "x86_64-unknown-linux-gnu" to "c6527f24f4b16031d3ae4fa9f658d5f11534c8d84ce7dc8502420280919c3490",
    "aarch64-unknown-linux-gnu" to "c832298b1ad4422481334855f6003e0f54145762c5a134f20a489511d2f65bbf",
)
val denoBinary: File = rootProject.file(".tools/deno-$denoVersion/deno")

val installDeno by tasks.registering {
    description = "Downloads the pinned Deno $denoVersion used as the verifier sandbox, checking its SHA-256."
    group = "build setup"
    outputs.file(denoBinary)
    doLast {
        if (denoBinary.canExecute()) return@doLast
        val os = System.getProperty("os.name").lowercase()
        val arm = System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")
        val target = when {
            "mac" in os -> if (arm) "aarch64-apple-darwin" else "x86_64-apple-darwin"
            "linux" in os -> if (arm) "aarch64-unknown-linux-gnu" else "x86_64-unknown-linux-gnu"
            else -> throw GradleException("no pinned Deno for $os; install deno $denoVersion and set MUTUALSTAKE_DENO")
        }
        val url = "https://github.com/denoland/deno/releases/download/v$denoVersion/deno-$target.zip"
        val zip = uri(url).toURL().openStream().use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(zip).joinToString("") { "%02x".format(it) }
        if (sha != denoChecksums.getValue(target)) throw GradleException("checksum mismatch for $url: got $sha")
        ZipInputStream(zip.inputStream()).use { entries ->
            generateSequence { entries.nextEntry }.first { it.name == "deno" }
            denoBinary.parentFile.mkdirs()
            denoBinary.outputStream().use { entries.copyTo(it) }
        }
        denoBinary.setExecutable(true)
        logger.lifecycle("Deno $denoVersion ($target) installed at $denoBinary")
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(installDeno)
    systemProperty("mutualstake.verifier.deno-path", denoBinary.absolutePath)
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    dependsOn(installDeno)
    systemProperty("mutualstake.verifier.deno-path", denoBinary.absolutePath)
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
