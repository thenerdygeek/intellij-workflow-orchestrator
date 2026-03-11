// git-integration/build.gradle.kts — Submodule for Git4Idea integration.
// Uses the MODULE variant; depends on :core.

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
    // IntelliJ Platform
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))

        // This submodule compiles against Git4Idea (bundled with the IDE)
        bundledPlugin("Git4Idea")
    }

    // Internal dependency on the :core submodule
    implementation(project(":core"))

    // Test
    testImplementation(libs.junit)
}
