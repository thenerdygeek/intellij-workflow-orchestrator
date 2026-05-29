// jira/build.gradle.kts — Submodule for Jira integration.
// Uses the MODULE variant; depends on :core.

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
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("Git4Idea")
        bundledPlugin("com.intellij.tasks")
        testFramework(TestFrameworkType.Platform)
    }

    implementation(project(":core"))
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    // JUnit Vintage engine — bridges JUnit 4 tests (BasePlatformTestCase) onto the JUnit 5 platform.
    // Required by StartWorkDialogActivateOnlyTest which needs a real Project from BasePlatformTestCase.
    testRuntimeOnly(libs.junit5.vintage.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

intellijPlatform {
    // Isolate this module's test sandbox into its own build dir so the aggregate `./gradlew test`
    // doesn't have several modules writing the shared root sandbox — Gradle 9.x then fails on the
    // resulting undeclared cross-task dependency. Matches :bamboo/:sonar/:pullrequest/:handover.
    sandboxContainer = layout.buildDirectory.dir("idea-sandbox")
}

tasks.test {
    useJUnitPlatform()
    // Retained as a harmless explicit ordering; with the isolated sandbox above this module no
    // longer reads :core's sandbox, but over-declaring a dependency is always safe.
    dependsOn(":core:prepareTestSandbox")
}
