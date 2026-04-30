package com.workflow.orchestrator.automation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipFile

// Locks in classloader-isolation invariants on the built plugin ZIP. Each entry below has
// gone wrong on `refactor/cleanup-perf-caching` once already, and the failure mode in every
// case was the same: the test JVM had a richer classpath than the deployed plugin's
// per-plugin classloader, so the regression shipped green.
//
//   1. `log4j-api` MUST be bundled. POI 5.x and several Tika 3.x parser modules call
//      `org.apache.logging.log4j.LogManager.getLogger(...)` directly. The IntelliJ Platform
//      stopped exposing log4j-api to plugin classloaders after Log4Shell, so its absence
//      surfaces as `NoClassDefFoundError: org/apache/logging/log4j/Logger` at the first POI
//      class load. Originally exposed as "Failed to load Agent tab" on v0.83.35-alpha.
//
//   2. `slf4j-api` MUST NOT be bundled. The IntelliJ Platform provides it; bundling a second
//      copy triggers `LinkageError: loader constraint violation` because two classloaders
//      define `org.slf4j.LoggerFactory`. Same trap as the v0.83.36-alpha sqlite-jdbc fix.
//
//   3. `kotlin-stdlib` MUST NOT be bundled. The IntelliJ Platform 2025.1+ provides it.
//      Bundling it splits the classloader and breaks Kotlin reflection. Guarded by
//      `kotlin.stdlib.default.dependency = false` plus root-level subproject excludes;
//      this test asserts the result.
//
//   4. The legacy `bc*-jdk15on` BouncyCastle family MUST NOT be bundled. The modern
//      `bc*-jdk18on` set replaces it, and both register the same `org.bouncycastle.*`
//      packages. With both on the classpath, encrypted-PDF AES-256 detection can fall
//      through to the older provider and silently fail. The first three (`bcprov`,
//      `bcpkix`, `bcutil`) were caught in the original 87→61 MB trim; `bcmail-jdk15on`
//      was missed and ships alongside `bcjmail-jdk18on` with 63 colliding classes.
//
// The test resolves the plugin ZIP by walking up from `user.dir` to `build/distributions/`.
// If the ZIP isn't present (regular `./gradlew test` run without a prior `:buildPlugin`),
// the test skips with `Assumptions.assumeTrue` so unit-test runs aren't disrupted. Release
// gates should run `./gradlew :buildPlugin :automation:test --tests "*BundledPluginLib*"`.
class BundledPluginLibInvariantsTest {

    @Test
    fun `plugin zip bundles log4j-api so POI and Tika can class-verify`() {
        val libEntries = readPluginLibEntries() ?: return
        val log4jApi = libEntries.filter { it.matches(Regex("log4j-api-[0-9.]+\\.jar")) }
        assertTrue(
            log4jApi.isNotEmpty(),
            "Plugin ZIP regression: log4j-api JAR is missing from lib/. POI 5.x and Tika " +
                "3.x reference `org.apache.logging.log4j.LogManager` directly; without the " +
                "API JAR the agent tool window fails with NoClassDefFoundError on first " +
                "POI class load. Re-check `exclude(group = \"org.apache.logging.log4j\")` " +
                "lines in document/build.gradle.kts — log4j-api must NOT be excluded; " +
                "log4j-core may be (we route via log4j-to-slf4j).",
        )
    }

    @Test
    fun `plugin zip does not bundle slf4j-api`() {
        val libEntries = readPluginLibEntries() ?: return
        val slf4j = libEntries.filter { it.matches(Regex("slf4j-api-[0-9.]+\\.jar")) }
        assertTrue(
            slf4j.isEmpty(),
            "Plugin ZIP regression: slf4j-api leaked into lib/ (${slf4j.joinToString()}). " +
                "IntelliJ Platform provides slf4j; a second copy triggers LinkageError. " +
                "Verify the root `configurations.all { exclude(group = \"org.slf4j\") }` " +
                "and per-dependency excludes in document/build.gradle.kts are intact.",
        )
    }

    @Test
    fun `plugin zip does not bundle kotlin-stdlib`() {
        val libEntries = readPluginLibEntries() ?: return
        val stdlib = libEntries.filter { it.matches(Regex("kotlin-stdlib(-jdk[78]|-common)?-[0-9.]+\\.jar")) }
        assertTrue(
            stdlib.isEmpty(),
            "Plugin ZIP regression: kotlin-stdlib leaked into lib/ (${stdlib.joinToString()}). " +
                "IntelliJ Platform 2025.1+ provides kotlin-stdlib at runtime; bundling a " +
                "second copy splits the classloader. Verify `kotlin.stdlib.default.dependency` " +
                "in gradle.properties and the PLUGIN_DIST_CONFIGURATIONS excludes in " +
                "build.gradle.kts (root) are intact.",
        )
    }

    @Test
    fun `plugin zip does not bundle any legacy BouncyCastle jdk15on jars`() {
        val libEntries = readPluginLibEntries() ?: return
        val legacy = libEntries.filter { it.matches(Regex("bc[a-z]+-jdk15on-[0-9.]+\\.jar")) }
        assertEquals(
            emptyList<String>(),
            legacy,
            "Plugin ZIP regression: legacy BouncyCastle jdk15on JARs leaked into lib/ " +
                "($legacy). They register `org.bouncycastle.*` classes that collide with " +
                "the modern jdk18on family, causing classloader-order-dependent silent " +
                "fallback to old crypto. Add the missing module(s) to the project-wide " +
                "excludes in document/build.gradle.kts (configurations.all block).",
        )
    }

    private fun readPluginLibEntries(): List<String>? {
        val zip = locatePluginZip() ?: run {
            assumeTrue(false, "Plugin ZIP not found — run `./gradlew :buildPlugin` first.")
            return null
        }
        return ZipFile(zip).use { zf ->
            zf.entries().asSequence()
                .map { it.name }
                .filter { it.contains("/lib/") && it.endsWith(".jar") }
                .map { it.substringAfterLast('/') }
                .toList()
        }
    }

    private fun locatePluginZip(): File? {
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(5) {
            val distributions = dir?.resolve("build/distributions")
            if (distributions?.isDirectory == true) {
                return distributions
                    .listFiles { f ->
                        f.isFile &&
                            f.name.startsWith("intellij-workflow-orchestrator-") &&
                            f.name.endsWith(".zip")
                    }
                    ?.maxByOrNull { it.lastModified() }
            }
            dir = dir?.parentFile
        }
        return null
    }
}
