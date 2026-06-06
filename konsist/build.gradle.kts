// konsist/build.gradle.kts — plain Kotlin/JUnit5 module for architecture tests.
// Deliberately does NOT apply the IntelliJ Platform plugin: Konsist scans source
// files as text, it does not need the IDE on the classpath. This keeps :konsist:test
// fast (no multi-GB IDE download/extraction).
plugins {
    alias(libs.plugins.kotlin)
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

repositories {
    mavenCentral()
}

dependencies {
    // This project sets kotlin.stdlib.default.dependency=false globally (other modules get
    // stdlib from the IntelliJ Platform). :konsist does NOT apply the platform plugin, so it
    // must declare the Kotlin stdlib explicitly or test code can't see listOf/String/etc.
    implementation(kotlin("stdlib"))

    testImplementation(libs.konsist)
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
