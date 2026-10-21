// The Claude agents: the buyer's and the seller's. A library inside the one service; it sees the protocol
// only through ProtocolPort, which has no way to capture, void, pay or confirm.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "mutualstake"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
    compilerOptions { freeCompilerArgs.add("-Xjsr305=strict") }
}

dependencies {
    // Same versions as the control plane (Jackson in particular).
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.kotlin.bom))
    api(libs.anthropic)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform { excludeTags("claude") }
    testLogging { events("passed", "skipped", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
