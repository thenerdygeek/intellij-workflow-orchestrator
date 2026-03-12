// mock-server/build.gradle.kts — Standalone adversarial mock server.
// NOT an IntelliJ platform module. Uses Ktor application plugin.

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    application
}

application {
    mainClass.set("com.workflow.orchestrator.mockserver.MockServerMainKt")
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.1.1"

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines (for timed build progression)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // Testing
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
}

tasks.test {
    useJUnitPlatform()
}
