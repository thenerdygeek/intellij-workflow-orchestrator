package com.workflow.orchestrator.agent.tools.process

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Static-analysis contract for shell-spawning sites in `:agent` and `:core`.
 *
 * Background: prior to commit 57ab56ea (the Windows-compatibility sweep),
 * `JavaRuntimeExecTool` and `SonarTool` both spawned `ProcessBuilder("mvn", …)`
 * and `ProcessBuilder("gradle", …)` directly. Java's `ProcessBuilder` calls
 * `CreateProcess` on Windows, which auto-appends `.exe` for missing extensions
 * but does NOT consult `PATHEXT` for `.cmd` / `.bat`. Maven and Gradle ship
 * as `mvn.cmd` / `gradle.bat` on Windows, so the bare literals failed with
 * `error=2 The system cannot find the file specified` even on correctly
 * installed Windows boxes.
 *
 * The fix routes every Maven/Gradle invocation through
 * [com.workflow.orchestrator.core.util.BuildToolExecutableResolver]. This
 * test prevents the bug class from coming back: any new code that hand-rolls
 * a bare-build-tool literal as a ProcessBuilder/exec argument will fail this
 * assertion at build time.
 *
 * For Python ecosystem CLIs (pip, poetry, uv, pytest, python -m …) the
 * canonical wrapper is [PlatformCommandWrapper.cmdWrap], which prepends
 * `cmd.exe /c` on Windows so PATHEXT is engaged.
 */
class ProcessSpawnContractTest {

    /**
     * Forbidden patterns (bare build-tool literals as the first argument
     * of a `ProcessBuilder` constructor or in a fallback `?: "<tool>"`
     * expression that lands at a spawn site).
     *
     * Each entry: regex + the constant the offending file should use instead.
     */
    private val forbiddenPatterns = listOf(
        "ProcessBuilder\\s*\\(\\s*\"mvn\"" to "BuildToolExecutableResolver.resolveMaven(baseDir)",
        "ProcessBuilder\\s*\\(\\s*\"mvnw\"" to "BuildToolExecutableResolver.resolveMaven(baseDir)",
        "ProcessBuilder\\s*\\(\\s*\"gradle\"" to "BuildToolExecutableResolver.resolveGradle(baseDir)",
        "ProcessBuilder\\s*\\(\\s*\"gradlew\"" to "BuildToolExecutableResolver.resolveGradle(baseDir)",
        "\\?\\:\\s*\"mvn\"" to "BuildToolExecutableResolver.mavenExecutableName()",
        "\\?\\:\\s*\"mvnw\"" to "BuildToolExecutableResolver.mavenWrapperName()",
        "\\?\\:\\s*\"gradle\"" to "BuildToolExecutableResolver.gradleExecutableName()",
        "\\?\\:\\s*\"gradlew\"" to "BuildToolExecutableResolver.gradleWrapperName()",
    ).map { (regex, suggestion) -> Regex(regex) to suggestion }

    /**
     * Files allowed to contain the literals (the resolver itself returns
     * them; the resolver's own test asserts on them).
     */
    private val allowlist = setOf(
        "BuildToolExecutableResolver.kt",
        "BuildToolExecutableResolverTest.kt",
        "ProcessSpawnContractTest.kt",
    )

    @Test
    fun `no main-source file spawns bare mvn or gradle literals via ProcessBuilder`() {
        val violations = mutableListOf<String>()
        for (root in mainSourceRoots()) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.name !in allowlist }
                .forEach { file ->
                    val text = file.readText()
                    for ((regex, suggestion) in forbiddenPatterns) {
                        regex.findAll(text).forEach { match ->
                            val lineNum = text.substring(0, match.range.first).count { it == '\n' } + 1
                            violations += "${file.relativeTo(root)}:$lineNum — `${match.value}` " +
                                "(use $suggestion instead)"
                        }
                    }
                }
        }
        assertTrue(
            violations.isEmpty(),
            "Found ${violations.size} bare-build-tool spawn site(s) — these will fail on Windows " +
                "because CreateProcess does not auto-resolve .cmd / .bat extensions:\n" +
                violations.joinToString("\n  ", prefix = "  ")
        )
    }

    /**
     * Locates the main-source roots for `:agent` and `:core` regardless of
     * whether the test is launched from the repo root (Gradle CLI) or the
     * agent module dir (IntelliJ).
     */
    private fun mainSourceRoots(): List<File> {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        val candidates = listOf(
            File(userDir, "src/main/kotlin"),                // user.dir == <repo>/agent
            File(userDir, "../core/src/main/kotlin"),
            File(userDir, "agent/src/main/kotlin"),          // user.dir == <repo>
            File(userDir, "core/src/main/kotlin"),
        )
        val roots = candidates.filter { it.isDirectory }.map { it.canonicalFile }.distinct()
        if (roots.isEmpty()) {
            error("No source roots found. user.dir=$userDir; tried: ${candidates.map { it.absolutePath }}")
        }
        return roots
    }
}
