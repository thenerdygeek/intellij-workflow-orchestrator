package com.workflow.orchestrator.automation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.io.File
import java.util.jar.JarFile

// Locks in three invariants on the output of `:automation:trimmedSqliteJar`. Each has gone
// wrong once already on `refactor/cleanup-perf-caching`:
//
//   1. No `org/slf4j` entries. v0.83.35-alpha shipped a JAR that merged slf4j-api into the
//      trimmed output (transitive resolution + `zipTree`), causing a `LinkageError` on
//      first `Class.forName("org.sqlite.JDBC")` because the platform-bundled SLF4J and the
//      bundled-in one disagreed on `ILoggerFactory`'s identity. Fixed in v0.83.36-alpha by
//      `isTransitive = false` on the upstream resolvable configuration.
//
//   2. JDBC entry-point class present. `org/sqlite/JDBC.class` must remain. If the strip
//      ever gets too aggressive (e.g. someone adds `org/sqlite/` glob to the exclude list),
//      the `:automation` and `:agent` runtime paths fail at `Class.forName` time. Catch it
//      at build time instead.
//
//   3. Native libraries match exactly the shipped-platform list. Adding a new platform
//      requires conscious approval (touching this file's expected list AND the build
//      script's exclude list). Removing one is caught immediately.
//
// The test resolves the trimmed JAR by walking the `java.class.path` system property and
// matching on the filename pattern `sqlite-jdbc-trimmed-*.jar`. Both Gradle's test classpath
// and IntelliJ's runner expose the JAR this way, so no Gradle-side wiring is needed.
class TrimmedSqliteJarInvariantsTest {

    private val expectedNativePaths = listOf(
        "org/sqlite/native/Linux/aarch64/",
        "org/sqlite/native/Linux/x86_64/",
        "org/sqlite/native/Mac/aarch64/",
        "org/sqlite/native/Mac/x86_64/",
        "org/sqlite/native/Windows/aarch64/",
        "org/sqlite/native/Windows/x86_64/",
    )

    @Test
    fun `trimmed sqlite-jdbc has no SLF4J leak`() {
        val entries = readTrimmedJarEntries()
        val slf4j = entries.filter { it.startsWith("org/slf4j/") }
        assertTrue(
            slf4j.isEmpty(),
            "trimmedSqliteJar regression: ${slf4j.size} `org/slf4j/*` entries leaked in " +
                "(${slf4j.take(3).joinToString()}). Would collide with platform-bundled " +
                "SLF4J at runtime. Verify `sqliteJdbcUpstream` is `isTransitive = false` " +
                "in automation/build.gradle.kts.",
        )
    }

    @Test
    fun `trimmed sqlite-jdbc retains the JDBC entry-point class`() {
        val entries = readTrimmedJarEntries()
        assertTrue(
            "org/sqlite/JDBC.class" in entries,
            "trimmedSqliteJar regression: `org/sqlite/JDBC.class` is missing. The exclude " +
                "list is too aggressive — sqlite-jdbc cannot be loaded by `Class.forName`.",
        )
    }

    @Test
    fun `trimmed sqlite-jdbc native-libs match the shipped-platform set`() {
        val entries = readTrimmedJarEntries()
        val nativeFiles = entries.filter { it.startsWith("org/sqlite/native/") && !it.endsWith("/") }
        val actualPaths = nativeFiles.map { it.substringBeforeLast('/') + "/" }.distinct().sorted()
        assertEquals(
            expectedNativePaths,
            actualPaths,
            "trimmedSqliteJar regression: native-libs path set drifted. If this is " +
                "intentional, update `expectedNativePaths` in this test AND the exclude " +
                "list in automation/build.gradle.kts.",
        )
    }

    private fun readTrimmedJarEntries(): List<String> {
        val classpath = System.getProperty("java.class.path") ?: ""
        val jar = classpath
            .split(File.pathSeparator)
            .map(::File)
            .firstOrNull { it.name.startsWith("sqlite-jdbc-trimmed-") && it.name.endsWith(".jar") }
            ?: fail(
                "trimmedSqliteJar JAR not found on test classpath. The test depends on " +
                    "`:automation:trimmedSqliteJar` being built before `:automation:test`. " +
                    "Run `./gradlew :automation:trimmedSqliteJar :automation:test`.",
            )
        return JarFile(jar).use { jf -> jf.entries().asSequence().map { it.name }.toList() }
    }
}
