// Root build.gradle.kts — applies the main IntelliJ Platform plugin.
// Submodules use org.jetbrains.intellij.platform.module instead.

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.intelliJPlatform)
    alias(libs.plugins.changelog)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
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
    // Remote Robot (`com.intellij.remoterobot:remote-robot` / `remote-fixtures`) is NOT published
    // to Maven Central — it lives only in the JetBrains intellij-dependencies space. Needed by the
    // `uiTest` source set below; harmless for every other configuration.
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
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
        // :mock-server is a STANDALONE JVM app (Ktor `application` run), not a plugin-dist module —
        // there is no IntelliJ platform to provide kotlin-stdlib at runtime, so it MUST keep stdlib
        // on its runtimeClasspath (otherwise `:mock-server:run` → NoClassDefFoundError Function1).
        if (name in PLUGIN_DIST_CONFIGURATIONS && project.name != "mock-server") {
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
        // 4g (was 2g): the growing :agent test suite (monitor framework sources + MockK/MockWebServer/coroutine fixtures retained across a single forked JVM) OOMed at 2g — 2026-06-05.
        maxHeapSize = "4g"

        // Coverage-run failure tolerance. Under Kover bytecode instrumentation, MockK cannot
        // mock certain targets (suspend-function-type mocks in the :agent delegation tests; the
        // reflective tool construction in ToolDslSchemaParityTest; UI-card mocks in :handover
        // TemplateEditorCardTest; possibly others across modules) and those tests fail with
        // UnsupportedOperationException / CONSTRUCT_FAILURE. Test CORRECTNESS is enforced by the
        // separate, non-instrumented `test` CI job (no `-Pcoverage`), which runs ALL tests with
        // MockK intact. This coverage-only run (`-Pcoverage`, used by koverXmlReport / koverVerify
        // in the dedicated CI job) only needs to MEASURE, so it tolerates the instrumentation-
        // incompatible failures and lets koverXmlReport aggregate full coverage; koverVerify still
        // enforces the floor. See docs/superpowers/plans/2026-06-06-phase0-enforcement-foundation.md.
        if (project.hasProperty("coverage")) {
            ignoreFailures = true
        }
    }

    // Apply Kover to every module so its production code is instrumented and its execution
    // data is aggregated into the root total-coverage report (see root `dependencies {}` +
    // `kover {}` blocks). Aggregation requires the plugin on every aggregated module, else
    // root `kover(project(":x"))` cannot resolve the module's `kover` variant.
    apply(plugin = "org.jetbrains.kotlinx.kover")

    // CRITICAL: once applied, Kover instruments EVERY test task by default. That instrumentation
    // is what breaks MockK in a handful of tests (UnsupportedOperationException / CONSTRUCT_FAILURE).
    // Gate instrumentation on the `-Pcoverage` flag so the normal `test` task/job runs
    // UN-instrumented (MockK intact, no `ignoreFailures` needed there) and only the dedicated
    // coverage run (`-Pcoverage`) instruments. The plugin stays applied either way so the
    // per-module lockfiles and root aggregation remain valid.
    extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
        currentProject {
            instrumentation {
                disabledForAll = !project.hasProperty("coverage")
            }
        }
    }
}

// ---- Static Analysis (detekt) ----
// Captured at top-level script scope where the `libs` version-catalog accessor resolves;
// `libs` is NOT available inside the subprojects {} closure, so we hoist the provider here.
val detektFormatting = libs.detekt.formatting
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        // Per-module baseline (NOT a single shared file): each module's detektBaseline task
        // writes its own baseline, so a shared path would be overwritten last-write-wins and
        // leave most modules un-baselined. Resolves against each subproject's projectDir.
        baseline = file("detekt-baseline.xml")
        parallel = true
    }

    dependencies {
        add("detektPlugins", detektFormatting)
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = providers.gradleProperty("javaVersion").get()
        reports {
            html.required.set(true)
            xml.required.set(false)
            sarif.required.set(false)
            txt.required.set(false)
        }
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

// ---- UI Test Source Set (Remote Robot) ----
// A dedicated `uiTest` source set hosts the out-of-process Remote Robot UI tests so they never
// run under the normal `test` task (they need a live IDE + display + Ultimate license, see the
// test file header). It compiles against the plugin's own `main` output and runs against it.
// Wiring (task registration + dependencies) lives in the `// ---- UI Tests` section below.
val uiTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

// `uiTestImplementation` inherits everything on the plugin's `implementation` classpath so the
// tests see the same libraries the plugin ships with; `uiTestRuntimeOnly` carries the JUnit engine.
configurations["uiTestImplementation"].extendsFrom(configurations.implementation.get())
val uiTestRuntimeOnly: Configuration by configurations.getting

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

    // -- Kover total-coverage aggregation --
    // Pull every code module's coverage into the root total report. `:document` is listed
    // explicitly even though it is only a transitive (non-root) dependency via `:agent`.
    // Infra-only modules (`:konsist` arch tests, `:mock-server` test fixtures) are excluded.
    kover(project(":core"))
    kover(project(":document"))
    kover(project(":jira"))
    kover(project(":bamboo"))
    kover(project(":sonar"))
    kover(project(":pullrequest"))
    kover(project(":automation"))
    kover(project(":handover"))
    kover(project(":agent"))
    kover(project(":web"))

    // -- External libraries --
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.serialization.json)
    // sqlite-jdbc was previously bundled at the root for the agent's db_query SQLite branch.
    // It shipped 22-platform native libs (~13.5 MB) for marginal user benefit, so it has been
    // removed from the plugin distribution. SQLite profiles now rely on the user supplying the
    // driver via IntelliJ DataSources or Generic JDBC mode — see DatabaseConnectionManager
    // (`loadSqliteDriverOrThrow`) for the resolution chain and the user-facing error message.

    // -- UI Test (Remote Robot) --
    // Out-of-process UI test harness: the JUnit5 test drives the running IDE over HTTP via
    // Remote Robot. String config names are required because `uiTest*` configs are created
    // dynamically from the `uiTest` source set above.
    "uiTestImplementation"(libs.remote.robot)
    "uiTestImplementation"(libs.remote.fixtures)
    // kotlin.stdlib.default.dependency=false (gradle.properties) means stdlib is NOT auto-added, and
    // the uiTest source set has neither the IntelliJ platform nor stdlib on its classpath — so add it
    // explicitly (else even kotlin.Any/Unit/listOf don't resolve). okhttp must be on the COMPILE
    // classpath because RemoteRobot's constructor signature references okhttp3.OkHttpClient, which
    // remote-robot only exposes as a runtime (implementation) transitive.
    "uiTestImplementation"(kotlin("stdlib"))
    "uiTestImplementation"(libs.okhttp)
    "uiTestImplementation"(libs.junit5.api)
    "uiTestRuntimeOnly"(libs.junit5.engine)
    "uiTestRuntimeOnly"(libs.junit5.platform.launcher)
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

// ---- Dev sandbox: allow localhost mock server ----
// Lets the runIde sandbox point the plugin at a LOCALHOST mock server (the :mock-server module).
// BaseUrlValidator's SSRF guard otherwise rejects localhost / 127.0.0.1 / private-LAN URLs; this
// system property flips that guard's dev-only escape hatch ON. Sandbox-only — the shipped plugin
// never sets it, so production keeps full SSRF protection.
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>().configureEach {
    jvmArgumentProviders += CommandLineArgumentProvider {
        listOf("-Dworkflow.orchestrator.allowPrivateUrls=true")
    }
}

// ---- UI Tests (Remote Robot) ----
// Two-task flow (IntelliJ Platform Gradle Plugin 2.x — `runIdeForUiTests` is NOT a built-in here):
//   1. `./gradlew runIdeForUiTests`  — launches the sandbox IDE with the Robot Server plugin
//      listening on :8082 (registered below via the v2 `intellijPlatformTesting.runIde` DSL).
//   2. `./gradlew uiTest`            — runs the JUnit5 tests that drive that IDE over HTTP.
// Both REQUIRE a licensed IDEA Ultimate + a display (see the test file header). They are never
// wired into `check`/`build` and must be invoked explicitly.
val runIdeForUiTests by intellijPlatformTesting.runIde.registering {
    task {
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf(
                "-Drobot-server.port=8082",
                "-Djb.privacy.policy.text=<!--999.999-->",
                "-Djb.consents.confirmation.enabled=false",
                "-Didea.trust.all.projects=true",
                "-Dide.mac.message.dialogs.as.sheets=false",
                "-Didea.initially.ask.config=never",
            )
        }
    }
    plugins {
        robotServerPlugin()
    }
}

tasks.register<Test>("uiTest") {
    group = "verification"
    description = "Remote Robot UI tests (start runIdeForUiTests first)"
    testClassesDirs = sourceSets["uiTest"].output.classesDirs
    classpath = sourceSets["uiTest"].runtimeClasspath
    useJUnitPlatform()
}

// ---- Changelog ----
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

// ---- Code Coverage ----
// Aggregated across the 10 code modules (see `kover(project(...))` in `dependencies {}`).
// Measure/verify with `-Pcoverage` (the dedicated CI coverage job), which tolerates the
// Kover-instrumentation × MockK test failures (see the subprojects Test config) so the
// report can aggregate. Correctness is enforced separately by the non-instrumented `test` job.
kover {
    reports {
        total {
            xml { onCheck = true }
            html { onCheck = true }
            // Total line-coverage floor. Baseline measured 2026-06-07 = 49.66%
            // (covered 50789 / total 102282). Floor = floor(49.66) - 1 = 48, so the gate
            // starts green with a small buffer and ratchets UP over time — never down.
            verify {
                rule("Minimum line coverage") {
                    minBound(48)
                }
            }
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

    // Gradle 9.4 changed the Zip task's default duplicatesStrategy from INCLUDE to FAIL.
    // buildPlugin assembles the plugin's `lib/` from two contributions that both carry the
    // `<plugin>-<ver>-searchableOptions.jar` (the jarSearchableOptions output AND the composed
    // plugin layout). Those entries are byte-identical (only one searchableOptions JAR is ever
    // produced), so de-duplicating (EXCLUDE) yields the correct single-entry ZIP. Pre-9.4 the
    // dup was silently INCLUDE'd; post-bump it hard-fails buildPlugin / verifyPlugin /
    // :plugin-b:runIde. Scoped to buildPlugin only (not all Zip tasks) to stay surgical.
    named<org.gradle.api.tasks.bundling.Zip>("buildPlugin") {
        duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
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
