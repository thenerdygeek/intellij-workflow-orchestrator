// web/build.gradle.kts — Submodule: web_fetch and web_search tools.
// Uses the MODULE variant; depends only on :core.

plugins {
    alias(libs.plugins.kotlin)
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
    }

    implementation(project(":core"))
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    compileOnly(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
    // Gradle 9: declare ordering so that when multiple modules are tested in one build
    // invocation (e.g. ./gradlew :core:test :web:test :agent:test) the test task does
    // not implicitly consume sandbox outputs from sibling modules before those tasks run.
    // mustRunAfter (not dependsOn) so :web:test still works standalone without pulling
    // in every other module's test infrastructure.
    mustRunAfter(
        ":core:prepareTestSandbox",
        ":agent:prepareTestSandbox",
        ":document:prepareTestSandbox",
        ":jira:prepareTestSandbox",
        ":bamboo:prepareTestSandbox",
        ":sonar:prepareTestSandbox",
        ":pullrequest:prepareTestSandbox",
        ":automation:prepareTestSandbox",
        ":handover:prepareTestSandbox",
    )
}
