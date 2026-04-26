// core/build.gradle.kts — Submodule: uses the MODULE variant of the plugin.
// This avoids polluting submodules with signing/publishing/run tasks.

import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    id("org.jetbrains.intellij.platform.module")   // version managed by root project
}

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // IntelliJ Platform — same IDE target as the root
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))

        // Bundled plugins this submodule needs at compile time
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map {
            it.split(',').map(String::trim).filter(String::isNotEmpty)
        })
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
    }

    // External libraries used by :core
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    // Caffeine — in-memory cache store for Phase 3 HTTP response caching (Prong A).
    implementation(libs.caffeine)

    // Test
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    // JUnit Vintage engine — bridges JUnit 4 tests (BasePlatformTestCase) onto the JUnit 5 platform.
    // Phase 5 T18 — only required by `WorkflowContextEditorIntegrationTest`.
    testRuntimeOnly(libs.junit5.vintage.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
}

tasks.test {
    useJUnitPlatform()
}
