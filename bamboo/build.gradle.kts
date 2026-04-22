// bamboo/build.gradle.kts — Submodule for Bamboo build monitoring.
// Uses the MODULE variant; depends on :core.

import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    id("org.jetbrains.intellij.platform.module")
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
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
    }

    implementation(project(":core"))
    // TEMPORARY — Phase 6 removes this via the CreatePrLauncher EP.
    // :bamboo currently constructs CreatePrDialog directly from PrBar.openCreatePrDialog().
    // Phase 6 switches that to CreatePrLauncher.getInstance()?.launch(...) and deletes this line.
    implementation(project(":pullrequest"))
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
}

intellijPlatform {
    sandboxContainer = layout.buildDirectory.dir("idea-sandbox")
}

tasks.test {
    useJUnitPlatform()
}
