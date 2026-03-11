// Root build.gradle.kts — applies the main IntelliJ Platform plugin.
// Submodules use org.jetbrains.intellij.platform.module instead.

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.intelliJPlatform)
    alias(libs.plugins.changelog)
    alias(libs.plugins.kover)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// ---- Java Toolchain ----
kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

// ---- Repositories ----
repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// ---- Dependencies ----
dependencies {
    // -- IntelliJ Platform --
    intellijPlatform {
        // Target IDE: IntelliJ IDEA (unified since 2025.3; works for 2025.1 too)
        intellijIdea(providers.gradleProperty("platformVersion"))

        // Bundled plugins your plugin depends on
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map {
            it.split(',').map(String::trim).filter(String::isNotEmpty)
        })

        // Marketplace plugins (if any)
        plugins(providers.gradleProperty("platformPlugins").map {
            it.split(',').map(String::trim).filter(String::isNotEmpty)
        })

        // Tooling
        pluginVerifier()
        zipSigner()
    }

    // -- Submodule composition: flatten into a single plugin JAR --
    implementation(project(":core"))
    implementation(project(":git-integration"))

    // -- External libraries --
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.sqlite.jdbc)

    // -- Test --
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)
}

// ---- IntelliJ Platform Configuration ----
intellijPlatform {
    pluginConfiguration {
        id = providers.gradleProperty("pluginGroup").map { "$it.plugin" }
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        description = providers.fileContents(
            layout.projectDirectory.file("DESCRIPTION.md")
        ).asText.orElse("My IntelliJ Plugin")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }

        vendor {
            name = "Your Name"
            email = "you@example.com"
            url = "https://example.com"
        }
    }

    // Sign the plugin before publishing (credentials via env vars)
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // Publish to JetBrains Marketplace
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf("default")
    }

    // Verify against recommended IDE versions
    pluginVerification {
        ides {
            recommended()
        }
    }
}

// ---- Changelog ----
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

// ---- Code Coverage ----
kover {
    reports {
        total {
            xml { onCheck = true }
            html { onCheck = true }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "9.0"
    }
}
