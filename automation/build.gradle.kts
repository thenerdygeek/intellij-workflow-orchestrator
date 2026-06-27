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

// Trimmed sqlite-jdbc runtime jar relocated to :plugin-b (Phase 2a): A no longer ships :automation,
// so the runtime native jar moves to Plugin B (which bundles :automation). :automation now only
// compiles against the JDBC API (compileOnly) and uses a real driver in tests (testImplementation).
// The trimmed-jar invariants are pinned by TrimmedSqliteJarInvariantsTest, relocated to :plugin-b.

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map {
            it.split(',').map(String::trim).filter(String::isNotEmpty)
        })
        testFramework(TestFrameworkType.Platform)
    }

    implementation(project(":core"))
    implementation(project(":bamboo"))
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // sqlite-jdbc: compileOnly gives the JDBC API surface to compile against. The runtime jar is
    // shipped by :plugin-b (which bundles :automation) — A does not ship sqlite. See :plugin-b.
    compileOnly(libs.sqlite.jdbc) {
        exclude(group = "org.slf4j")
    }

    testImplementation(libs.sqlite.jdbc) {
        exclude(group = "org.slf4j")
    }
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
}

intellijPlatform {
    // Isolate this module's test sandbox into its own build dir so the aggregate `./gradlew test`
    // doesn't have several modules writing the shared root sandbox — Gradle 9.x then fails on the
    // resulting undeclared cross-task dependency. Matches :bamboo/:sonar/:pullrequest/:handover.
    sandboxContainer = layout.buildDirectory.dir("idea-sandbox")
}

tasks.test {
    useJUnitPlatform()
}
