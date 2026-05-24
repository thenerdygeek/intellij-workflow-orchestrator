package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.tools.process.ProcessEnvironment
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-text safety pins for RevertFileTool's hardened ProcessBuilder block.
 *
 * Audit finding agent-tools:F-2 identified five failure modes in the original
 * ProcessBuilder block: (1) no timeout, (2) unbounded stderr buffering, (3) no
 * cancellation propagation, (4) no env sanitisation, (5) process orphaned on cancel.
 *
 * Full integration tests (mocking a hanging git process, measuring 30s timeout, etc.)
 * require a real coroutine dispatcher and process control — feasible but expensive in
 * a headless unit test harness. Instead, these source-text pins assert that the
 * production code contains each critical construct, so a future contributor cannot
 * accidentally regress the fix without a test failure.
 *
 * Pattern matches against the compiled Kotlin source (read from disk) following the
 * established [com.workflow.orchestrator.agent.tools.runtime.RunInvocationLeakTest] precedent.
 */
class RevertFileToolProcessSafetyTest {

    private val sourceText: String by lazy {
        val classFile = RevertFileTool::class.java.classLoader
            .getResource("com/workflow/orchestrator/agent/tools/builtin/RevertFileTool.class")
        // Prefer reading the .kt source if accessible (test resources or source set)
        val ktSource = runCatching {
            val path = "agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RevertFileTool.kt"
            java.io.File(path).readText()
        }.getOrNull()
        // Fallback: read from the test-class's resource directory (works for source-text pins)
        ktSource ?: classFile?.let { java.io.File(it.toURI()).readText() } ?: ""
    }

    /**
     * Helper: read the Kotlin source relative to the project root.
     * Works when tests are run from the module or project root (both common in Gradle).
     */
    private fun readKtSource(): String {
        val candidates = listOf(
            "agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RevertFileTool.kt",
            "../agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RevertFileTool.kt",
            "src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RevertFileTool.kt",
        )
        for (path in candidates) {
            val f = java.io.File(path)
            if (f.exists()) return f.readText()
        }
        error("Cannot locate RevertFileTool.kt from working directory '${java.io.File(".").absolutePath}'")
    }

    @Test
    fun `production source uses withTimeout for 30s git timeout`() {
        val src = readKtSource()
        assertTrue(src.contains("withTimeout(30_000L)"),
            "RevertFileTool must use withTimeout(30_000L) — missing timeout is audit finding F-2.1")
    }

    @Test
    fun `production source uses withContext Dispatchers IO`() {
        val src = readKtSource()
        assertTrue(src.contains("withContext(Dispatchers.IO)"),
            "RevertFileTool must dispatch git I/O on Dispatchers.IO — F-2.3 cancellation propagation")
    }

    @Test
    fun `production source calls ensureActive inside read loop`() {
        val src = readKtSource()
        assertTrue(src.contains("ensureActive()"),
            "RevertFileTool must call ensureActive() in the output read loop — F-2.3")
    }

    @Test
    fun `production source applies 1 MB output cap`() {
        val src = readKtSource()
        assertTrue(src.contains("1_024 * 1_024") || src.contains("1024 * 1024"),
            "RevertFileTool must cap output at 1 MB — F-2.2 unbounded buffering")
        assertTrue(src.contains("truncated"),
            "RevertFileTool must append a truncation marker when cap is hit")
    }

    @Test
    fun `production source calls ProcessEnvironment applyToEnvironment for env scrubbing`() {
        val src = readKtSource()
        assertTrue(src.contains("ProcessEnvironment.applyToEnvironment"),
            "RevertFileTool must call ProcessEnvironment.applyToEnvironment to strip sensitive vars — F-2.4")
    }

    @Test
    fun `production source destroys process on CancellationException`() {
        val src = readKtSource()
        assertTrue(src.contains("CancellationException"),
            "RevertFileTool must catch CancellationException to destroy the process — F-2.5")
        assertTrue(src.contains("destroy()"),
            "RevertFileTool must call destroy() on the process in the cancellation handler — F-2.5")
        assertTrue(src.contains("destroyForcibly()"),
            "RevertFileTool must call destroyForcibly() when destroy does not complete in 5s — F-2.5")
        // CancellationException must be re-thrown (structural coroutine contract)
        assertTrue(src.contains("throw e"),
            "CancellationException must be re-thrown after process cleanup — coroutine contract")
    }

    @Test
    fun `ProcessEnvironment SENSITIVE vars include ANTHROPIC_API_KEY`() {
        // Confirm the env-scrubbing helper actually covers the highest-risk credential.
        assertTrue(
            ProcessEnvironment.SENSITIVE_ENV_VARS.contains("ANTHROPIC_API_KEY"),
            "ProcessEnvironment.SENSITIVE_ENV_VARS must list ANTHROPIC_API_KEY"
        )
    }

    @Test
    fun `ProcessEnvironment applyToEnvironment removes ANTHROPIC_API_KEY from env map`() {
        val env = mutableMapOf("ANTHROPIC_API_KEY" to "sk-ant-test", "PATH" to "/usr/bin")
        ProcessEnvironment.applyToEnvironment(env, isWindows = false)
        assertFalse(env.containsKey("ANTHROPIC_API_KEY"),
            "applyToEnvironment must strip ANTHROPIC_API_KEY from the process environment")
    }
}
