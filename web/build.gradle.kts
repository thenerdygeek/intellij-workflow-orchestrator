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
    testImplementation(libs.junit5.params)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

intellijPlatform {
    // Each module gets its own sandbox dir so concurrent/sequential :test tasks
    // across modules don't collide on the shared plugins-test/ directory.
    sandboxContainer = layout.buildDirectory.dir("idea-sandbox")
}

tasks.test {
    useJUnitPlatform()
    // Forward live-smoke system properties to the test JVM. Tests gated by
    // @EnabledIfSystemProperty (e.g. SearXNGLiveSmokeTest) only run when the matching
    // -Dsearxng.live.url=... -Dbrave.live.key=... -Dtavily.live.key=... is set.
    System.getProperties()
        .filterKeys { (it as String).startsWith("searxng.") || (it as String).startsWith("brave.") || (it as String).startsWith("tavily.") }
        .forEach { (key, value) -> systemProperty(key as String, value as String) }
}
