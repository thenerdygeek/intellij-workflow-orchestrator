package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Fix A — graceful tool-stop: kill the foreground run_command OS process on ANY cancellation path.
 *
 * Two pins:
 *  1. Behavioral: spawns `sleep 30`, runs execute() in a separate coroutine scope, waits until
 *     the OS process is registered in ProcessRegistry, cancels that scope, joins, then verifies
 *     the process is dead and no longer in ProcessRegistry. This covers the global loop-cancel
 *     path that the per-tool Stop button does NOT cover.
 *
 *  2. Source-text contract: asserts that RunCommandTool.kt contains both the `try {` that
 *     opens the foreground block immediately after `ProcessRegistry.register` and the
 *     `} finally {` + `ProcessRegistry.kill(toolCallId)` closing it. A future refactor that
 *     silently drops the finally will fail this test before it reaches production.
 */
class RunCommandCancelKillTest {

    private val project: Project = mockk {
        every { basePath } returns "/tmp"
    }

    @BeforeEach
    fun mockSettings() {
        mockkObject(AgentSettings.Companion)
        every { AgentSettings.getInstance(any()) } returns mockk {
            every { state } returns mockk {
                every { commandIdleThresholdSeconds } returns 15
                every { buildCommandIdleThresholdSeconds } returns 60
                every { runCommandMaxTimeoutMinutes } returns 10
            }
        }
    }

    @AfterEach
    fun cleanup() {
        unmockkAll()
        ProcessRegistry.killAll()
        RunCommandTool.streamCallback = null
        RunCommandTool.currentToolCallId.remove()
    }

    // ── Behavioral test ──────────────────────────────────────────────────────────

    /**
     * Behavioral: cancel the coroutine running execute() and verify the OS process is killed.
     *
     * Key design choices:
     * - currentToolCallId is a ThreadLocal; we set it BEFORE launching the coroutine on
     *   the calling thread. We pass the toolCallId string explicitly and set the ThreadLocal
     *   inside the launched coroutine's body so it runs on the actual execution thread.
     * - We launch on Dispatchers.IO (not the runBlocking event loop) so execute() truly runs
     *   concurrently and the poll loop on the main test thread can see registry updates.
     * - The scope is a standalone Job (not a child of runBlocking's scope) so cancelling it
     *   doesn't cancel the outer runBlocking scope.
     *
     * Unix-only: "sleep 30" is platform-specific; process-tree kill uses POSIX signals.
     * Windows behavior is covered by the source-text contract below.
     */
    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun `cancelling the execute coroutine kills the foreground OS process`() {
        val toolCallId = "cancel-kill-test-${System.nanoTime()}"

        val tool = RunCommandTool(allowedShells = listOf("bash"))
        val params = buildJsonObject {
            put("command", "sleep 30")
            put("description", "long-running process for cancel test")
            // large timeout so the monitor loop does not kill it via timeout
            put("timeout", 120)
        }

        // Independent scope so cancelling it doesn't cancel the test itself
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val executeJob = scope.launch {
            // Set ThreadLocal on this IO thread — currentToolCallId is thread-local
            RunCommandTool.currentToolCallId.set(toolCallId)
            try {
                tool.execute(params, project)
            } finally {
                RunCommandTool.currentToolCallId.remove()
            }
        }

        // Wait until the OS process appears in ProcessRegistry (poll up to 5 s)
        val registered = run {
            repeat(100) {
                val mp = ProcessRegistry.get(toolCallId)
                if (mp != null) return@run mp
                Thread.sleep(50)
            }
            null
        }
        checkNotNull(registered) {
            "Process was not registered in ProcessRegistry within 5 seconds — test infrastructure broken"
        }
        val process = registered.process

        // Verify: OS process is alive at this point (sanity check)
        assert(process.isAlive) { "Process should be alive before cancel" }

        // Cancel the coroutine — this is the global loop-cancel path (e.g. agent Stop)
        executeJob.cancel()
        runBlocking { executeJob.join() }

        // Give SIGTERM time to propagate and the daemon kill-executor time to run
        // (gracefulKill sends SIGTERM immediately, then waits up to 5 s before SIGKILL).
        // We only need to verify the process IS being killed — a 2 s window is generous.
        val deadline = System.currentTimeMillis() + 2_000L
        while (process.isAlive && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }

        assertFalse(process.isAlive) {
            "OS process must be dead after coroutine cancellation (Fix A: finally-kill). " +
                "PID=${process.pid()}"
        }
        assertNull(ProcessRegistry.get(toolCallId)) {
            "ProcessRegistry must not retain the entry after cancellation"
        }
    }

    // ── Source-text contract ─────────────────────────────────────────────────────

    private val runCommandSrc: String by lazy {
        java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt"
        ).readText()
    }

    /**
     * Source-text contract: pin that a `try { ... } finally { ProcessRegistry.kill(toolCallId) }`
     * block wraps the foreground monitor loop in RunCommandTool.kt. A future refactor that
     * drops the finally (e.g. inlining the monitor loop or restructuring the foreground path)
     * will fail here and force a conscious decision to re-add the kill.
     *
     * Specifics pinned:
     *  - ProcessRegistry.register call exists (precondition)
     *  - A `try {` block appears between register and the while(true) monitor loop
     *  - A `} finally {` block appears after the while(true) block
     *  - `ProcessRegistry.kill(toolCallId)` appears in a window near that finally
     */
    @Test
    fun `RunCommandTool foreground path has finally-kill after ProcessRegistry register`() {
        // 1. The register call must be present (pre-condition)
        assert("ProcessRegistry.register(toolCallId, process, command)" in runCommandSrc) {
            "RunCommandTool must register the process in ProcessRegistry — precondition for the finally-kill"
        }

        // 2. A try { block must appear between register and the monitor loop's while(true)
        val registerIdx = runCommandSrc.indexOf("ProcessRegistry.register(toolCallId, process, command)")
        assert(registerIdx >= 0) { "ProcessRegistry.register not found" }
        val afterRegister = runCommandSrc.substring(registerIdx)

        val whileIdx = afterRegister.indexOf("while (true)")
        assert(whileIdx > 0) { "while(true) monitor loop not found after ProcessRegistry.register" }
        val windowRegisterToWhile = afterRegister.substring(0, whileIdx)
        assert("try {" in windowRegisterToWhile) {
            "A `try {` block must appear between ProcessRegistry.register and the while(true) monitor loop " +
                "to bracket the foreground path with a finally-kill"
        }

        // 3. The `} finally {` closing the foreground try must appear after the while(true) block.
        //    Search from the while's absolute position in the source.
        val whileAbsoluteIdx = registerIdx + whileIdx
        val finallyIdx = runCommandSrc.indexOf("} finally {", whileAbsoluteIdx)
        assert(finallyIdx >= 0) {
            "A `} finally {` block must appear after the while(true) monitor loop in RunCommandTool. " +
                "This is Fix A — ensures the foreground OS process is killed on ANY cancel path."
        }

        // 4. ProcessRegistry.kill(toolCallId) must appear somewhere after the finally opening.
        //    Use a generous window (1000 chars) to cover the comment + the call.
        val afterFinallyWindow = runCommandSrc.substring(finallyIdx, minOf(finallyIdx + 1000, runCommandSrc.length))
        assert("ProcessRegistry.kill(toolCallId)" in afterFinallyWindow) {
            "The `} finally {` block after the monitor loop must call ProcessRegistry.kill(toolCallId). " +
                "This is Fix A: ensures the foreground OS process is killed on ANY cancel path, " +
                "not just the per-tool Stop button."
        }
    }
}
