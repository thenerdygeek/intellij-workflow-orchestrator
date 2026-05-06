package com.workflow.orchestrator.agent.tools.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text contract for the multi-method native-runner recovery path.
 *
 * Recovery flow:
 *   1. createJUnitRunSettings populates patterns via the public setPatterns
 *      method on JUnitConfiguration.Data.
 *   2. If reflection fails (e.g., a forked or future plugin build where the
 *      setter signature has shifted), the failure reason is emitted with the
 *      sentinel prefix `MULTI_METHOD_PATTERNS_UNAVAILABLE`.
 *   3. The dispatcher in executeRunTests recognizes the sentinel and routes
 *      the same call through the shell fallback (which supports multi-method
 *      on every JUnit version) instead of returning a hard error.
 *
 * If either side of the sentinel handshake is removed, the auto-recovery
 * silently breaks — the LLM gets the old hard error and has to retry with
 * use_native_runner=false. These assertions lock in both ends.
 *
 * Storage format itself is pinned by [JUnitPatternsFormatTest].
 */
class JavaRuntimeExecPatternsRecoveryTest {

    private val source by lazy { readSource("JavaRuntimeExecTool.kt") }

    @Test
    fun `createJUnitRunSettings emits the sentinel prefix when PATTERNS reflection fails`() {
        val emitCount = source.split("MULTI_METHOD_PATTERNS_UNAVAILABLE: ").size - 1
        assertTrue(
            emitCount >= 1,
            "Expected at least one emit site of the literal 'MULTI_METHOD_PATTERNS_UNAVAILABLE: ' " +
                "(the sentinel format is '<sentinel>: <details>'). The dispatcher only routes through " +
                "the shell fallback when the reason starts with this exact prefix."
        )
    }

    @Test
    fun `dispatcher recognizes the sentinel and routes to executeWithShell`() {
        val recognitionPattern = Regex(
            """startsWith\(\s*"MULTI_METHOD_PATTERNS_UNAVAILABLE"\s*\)"""
        )
        assertTrue(
            recognitionPattern.containsMatchIn(source),
            "Expected dispatcher to check `reason.startsWith(\"MULTI_METHOD_PATTERNS_UNAVAILABLE\")` " +
                "before returning the hard 'Native runner unavailable' error. Without this guard the " +
                "PATTERNS reflection failure surfaces as an opaque setup error and the agent has to " +
                "manually retry with use_native_runner=false."
        )

        // The dispatcher's recovery branch must call executeWithShell — otherwise the
        // sentinel recognition is dead code.
        val recoveryRange = source.substringAfter("startsWith(\"MULTI_METHOD_PATTERNS_UNAVAILABLE\")")
            .substringBefore("return ToolResult(")  // up to the next branch
        assertTrue(
            "executeWithShell" in recoveryRange,
            "Sentinel recognition branch must call executeWithShell to actually perform the fallback. " +
                "Found the sentinel check but no shell call before the next return statement."
        )
    }

    @Test
    fun `PATTERNS failure diagnostic reports the actual data class name`() {
        // The diagnostic should include the runtime class returned by getPersistentData()
        // rather than hard-coding 'JUnit 5'. This lets future failures on unusual
        // platform builds (e.g. a forked JUnit configuration type) be debugged without
        // re-running with a debugger.
        assertTrue(
            "data.javaClass.name" in source,
            "PATTERNS reflection failure should report `data.javaClass.name` so the actual " +
                "configuration data class name appears in the error. Hard-coded labels like " +
                "'JUnit 5 PATTERNS field' hide the real class identity."
        )
    }

    private fun readSource(name: String): String {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        val root = File(userDir)
        val relSubdir = "src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/$name"
        val moduleRootedPath = File(root, relSubdir)               // user.dir == <repo>/agent
        val repoRootedPath = File(root, "agent/$relSubdir")        // user.dir == <repo>
        val path = when {
            moduleRootedPath.isFile -> moduleRootedPath
            repoRootedPath.isFile -> repoRootedPath
            else -> error(
                "Source file '$name' not found at:\n" +
                    "  1. ${moduleRootedPath.absolutePath}\n" +
                    "  2. ${repoRootedPath.absolutePath}"
            )
        }
        return path.readText()
    }
}
