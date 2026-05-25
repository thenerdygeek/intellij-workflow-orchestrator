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

// Exclude SLF4J from all dependencies — IntelliJ provides its own.
// Bundling a second SLF4J causes LinkageError due to plugin classloader isolation.
// `configureEach` (not `all`) applies the exclude lazily per-configuration as each is realized,
// avoiding eager realization/resolution of every configuration at configuration time.
configurations.configureEach {
    exclude(group = "org.slf4j")
}

// IntelliJ Platform 2025.1+ provides `kotlin-stdlib` at runtime (see `gradle.properties`:
// `kotlin.stdlib.default.dependency = false`). The Kotlin Gradle plugin honours that flag
// for direct dependencies, but transitive pulls (notably OkHttp 4.12.0 → kotlin-stdlib-jdk8 →
// kotlin-stdlib) still drag a redundant ~1.7 MB stdlib JAR into the plugin ZIP and risk a
// classloader split. Strip kotlin-stdlib + jdk7/jdk8/common variants from the plugin-assembly
// configurations only — the platform stdlib is visible at compile and runtime, but Kotlin's
// build-tooling classpaths (`kotlinCompilerClasspath`, `kotlinBuildToolsApiClasspath`, etc.)
// still need stdlib to instantiate `CompilationServiceProxy`, so they are left untouched.
val PLUGIN_DIST_CONFIGURATIONS = setOf(
    "runtimeClasspath",
    "intellijPlatformRuntimeClasspath",
    "intellijPlatformComposedJar",
)
subprojects {
    configurations.configureEach {
        if (name in PLUGIN_DIST_CONFIGURATIONS) {
            exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
            exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
            exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
            exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
        }
    }
    // The default test-JVM heap (~512m) OOMs once a module's suite grows large
    // (MockK + MockWebServer + coroutine fixtures retain memory across a single
    // forked JVM). Raise it for every module's Test task. (audit 2026-05-31)
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        maxHeapSize = "2g"
    }
}
configurations.configureEach {
    if (name in PLUGIN_DIST_CONFIGURATIONS) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
    }
}

// ---- Dependency Locking (Phase 6 T6) ----
// Locks every configuration in every module to a deterministic resolution
// recorded in `<module>/gradle.lockfile`. Combined with
// `gradle/verification-metadata.xml` (SHA-256 per artifact), this protects the
// supply chain from version drift and tampered downloads.
//
// Update protocol when bumping `libs.versions.toml`:
//   ./gradlew dependencies --write-locks --write-verification-metadata sha256
//   ./gradlew clean verifyPlugin buildPlugin --refresh-dependencies
// See docs/architecture/dependency-locking.md.
allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

// ---- Dependencies ----
dependencies {
    // -- IntelliJ Platform --
    intellijPlatform {
        // Target IDE: IntelliJ IDEA (unified since 2025.3; works for 2025.1 too)
        intellijIdeaUltimate(providers.gradleProperty("platformVersion"))

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
    implementation(project(":jira"))
    implementation(project(":bamboo"))
    implementation(project(":sonar"))
    implementation(project(":pullrequest"))
    implementation(project(":automation"))
    implementation(project(":handover"))
    implementation(project(":agent"))
    implementation(project(":web"))

    // -- External libraries --
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.serialization.json)
    // sqlite-jdbc was previously bundled at the root for the agent's db_query SQLite branch.
    // It shipped 22-platform native libs (~13.5 MB) for marginal user benefit, so it has been
    // removed from the plugin distribution. SQLite profiles now rely on the user supplying the
    // driver via IntelliJ DataSources or Generic JDBC mode — see DatabaseConnectionManager
    // (`loadSqliteDriverOrThrow`) for the resolution chain and the user-facing error message.

    // -- Test --
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
            name = "Subhankar Halder"
            email = "subhankarhalder22@gmail.com"
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
            // Pinned to the EXACT build target (IDEA Ultimate @ platformVersion) instead of
            // recommended(). recommended() resolves the JetBrains-recommended IDE set across the
            // 251..253.* range, downloading + EXTRACTING extra IDE versions (e.g. 2025.2) into the
            // Gradle artifact-transforms cache — a full extra ~3GB extraction per IDE. Pinning to the
            // build target reuses the SAME content-addressed extracted IDE that buildPlugin already
            // produced, so verification adds zero extra extraction. For a full pre-release
            // compatibility sweep across 251..253, temporarily swap this line back to `recommended()`.
            create(
                org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaUltimate,
                providers.gradleProperty("platformVersion"),
            )
        }
        // Standard compatibility gate (the plugin's default set) PLUS @OverrideOnly.
        // COMPATIBILITY_PROBLEMS and NON_EXTENDABLE_API_USAGES are the load-bearing checks
        // ("will this crash on the user's IDE") and must stay in the failure set.
        //
        // EXPERIMENTAL_API_USAGES is intentionally excluded BY DESIGN (decision 2026-05-25, not a
        // TODO). runBlockingCancellable and writeAction are intentional, accepted platform APIs.
        // Gating on EXPERIMENTAL was evaluated and rejected: (1) the surface is self-resolving —
        // IU-251 reports 34 usages but IU-252/253 only 4 (runBlockingCancellable already graduated
        // out of @Experimental upstream); (2) the required ignoredProblems entries embed
        // auto-generated lambda names + the plugin version, so an exact-match ignore file would go
        // stale on any refactor/version bump and start failing the build on already-accepted code.
        // These usages still surface as warnings in build/reports/pluginVerifier — review there.
        failureLevel = listOf(
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
        )
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
        // Exact version so `./gradlew wrapper` never drifts the wrapper and spawns a separate
        // per-version transforms cache. Standardized on 9.4.0 (the version installed on dev
        // machines) so IDE + CLI share ONE ~/.gradle/caches/9.4.0/transforms tree.
        // Keep this aligned with gradle/wrapper/gradle-wrapper.properties.
        gradleVersion = "9.4.0"
    }
}

// TODO(zip-size-budget): a build-time assertion that the produced ZIP stays under a fixed
// size budget (e.g. 75 MB) is desirable so dependency creep gets caught before a release ships.
// A first attempt added a `doLast` to `buildPlugin`, but Gradle 9's configuration cache
// rejects the closure because the IntelliJ Platform plugin's `BuildPluginTask` carries
// non-serializable script object references that flow into the closure scope. Revisit by
// either (a) wiring a separate task that only `inputs.files(...)`-references the ZIP path
// and reads it via `println` instead of `logger`, (b) writing the assertion as a JUnit test
// in the root project's test source set, or (c) running a small CI script (`du -h` + bash
// numeric comparison) outside Gradle. For now, the per-build ZIP size is visible in the
// `gh release create` output and in `ls -la build/distributions/`.
