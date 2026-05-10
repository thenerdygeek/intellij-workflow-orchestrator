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

// Trimmed sqlite-jdbc — strips native libraries for platforms we never ship for. Defined before
// `dependencies { ... }` so the `files(trimmedSqliteJar)` reference below can resolve at config time.
// We keep: Mac/aarch64, Mac/x86_64, Linux/aarch64, Linux/x86_64, Windows/aarch64, Windows/x86_64.
// We strip: FreeBSD (×3), Linux-Android (×4), Linux-Musl (×3), Linux/ppc64, Linux/armv6, Linux/armv7,
// Linux/arm, Linux/x86 (32-bit), Windows/x86 (32-bit), Windows/armv7. This shaves ~7 MB compressed.
val sqliteJdbcUpstream by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    // CRITICAL: must be non-transitive. The previous `implementation(libs.sqlite.jdbc)
    // { exclude(group = "org.slf4j") }` excluded SLF4J at the module level. Here we are
    // building a *fat* JAR via `zipTree(it)` over every artifact in this configuration —
    // if slf4j-api (a transitive of sqlite-jdbc) were resolved, its classes would land in
    // the trimmed JAR and collide with IntelliJ's platform SLF4J, producing
    // `LinkageError: loader constraint violation` for `org.slf4j.LoggerFactory` /
    // `StaticLoggerBinder` at `Class.forName("org.sqlite.JDBC")` time. sqlite-jdbc has no
    // required transitive runtime deps, so non-transitive resolution is correct here.
    isTransitive = false
}
dependencies {
    sqliteJdbcUpstream(libs.sqlite.jdbc)
}
val trimmedSqliteJar = tasks.register<Jar>("trimmedSqliteJar") {
    archiveBaseName.set("sqlite-jdbc-trimmed")
    archiveVersion.set("3.45.3.0")
    from({ sqliteJdbcUpstream.map { zipTree(it) } })
    exclude(
        "org/sqlite/native/Linux-Android/**",
        "org/sqlite/native/FreeBSD/**",
        "org/sqlite/native/Linux-Musl/**",
        "org/sqlite/native/Linux/ppc64/**",
        "org/sqlite/native/Linux/armv6/**",
        "org/sqlite/native/Linux/armv7/**",
        "org/sqlite/native/Linux/arm/**",
        "org/sqlite/native/Linux/x86/**",
        "org/sqlite/native/Windows/x86/**",
        "org/sqlite/native/Windows/armv7/**",
    )
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Invariants on the trimmed JAR (no SLF4J leak; JDBC entrypoint present; only the shipped-
// platform natives) are enforced by `TrimmedSqliteJarInvariantsTest` in
// `automation/src/test/kotlin/...`. The test runs as part of `:automation:test` (and therefore
// `:automation:check`) so a regression fails the normal verification flow.

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map {
            it.split(',').map(String::trim).filter(String::isNotEmpty)
        })
    }

    implementation(project(":core"))
    implementation(project(":bamboo"))
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // sqlite-jdbc native trim — see [trimmedSqliteJar] above. compileOnly gives the JDBC API
    // surface to compile against; the runtime JAR is supplied by the trimmed task output.
    compileOnly(libs.sqlite.jdbc) {
        exclude(group = "org.slf4j")
    }
    implementation(files(trimmedSqliteJar))

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

tasks.test {
    useJUnitPlatform()
}
