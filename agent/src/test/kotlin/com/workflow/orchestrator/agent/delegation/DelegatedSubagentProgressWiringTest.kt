package com.workflow.orchestrator.agent.delegation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text pinning tests for `onSubagentProgress` wiring on the delegated session paths.
 *
 * **Root cause (Bug):**
 * When IDE-B starts or resumes a delegated agent session, the `onSubagentProgress` callback was
 * never forwarded to the underlying `executeTask`/`resumeSession` calls. With it null, `AgentService`
 * set `SpawnAgentTool.onSubagentProgress = null`, so `SubagentRunner` had no sink and the whole
 * `AgentController.onSubagentProgress` → `dashboard.spawnSubAgent/...` → `SubAgentView` pipeline never
 * fired during delegated fan-outs.
 *
 * **Structural fix (current):** `onSubagentProgress` is now one field of the [SessionUiCallbacks]
 * bundle sourced from the single [AgentController.buildSessionUiCallbacks] builder. The delegated
 * AgentService entry points forward it as `onSubagentProgress = callbacks.onSubagentProgress`, and the
 * controller's delegated entry points pass `buildSessionUiCallbacks()` — so the wiring can never
 * diverge from the interactive path. This test pins the specific `onSubagentProgress` link; the
 * full-bundle parity is locked by [SessionUiCallbacksParityTest].
 */
class DelegatedSubagentProgressWiringTest {

    private fun agentRoot(): File {
        val d = System.getProperty("user.dir")
        return if (File("$d/src/main/kotlin").isDirectory) File("$d/src/main/kotlin")
        else File("$d/agent/src/main/kotlin")
    }

    private val serviceSource: String by lazy {
        File(agentRoot(), "com/workflow/orchestrator/agent/AgentService.kt").readText()
    }

    private val controllerSource: String by lazy {
        File(agentRoot(), "com/workflow/orchestrator/agent/ui/AgentController.kt").readText()
    }

    // ── AgentService.startDelegatedSession forwards the bundle's onSubagentProgress ──

    private fun startDelegatedExecuteTaskCall(): String {
        val body = serviceSource.substringAfter("fun startDelegatedSession(").substringBefore("\n    fun ")
        val callStart = body.indexOf("executeTask(")
        assertTrue(callStart >= 0, "startDelegatedSession must call executeTask")
        return body.substring(callStart)
    }

    @Test
    fun `startDelegatedSession forwards bundle onSubagentProgress to executeTask`() {
        val call = startDelegatedExecuteTaskCall()
        assertTrue(
            Regex("""onSubagentProgress\s*=\s*[A-Za-z0-9_]+\.onSubagentProgress""").containsMatchIn(call),
            "startDelegatedSession's executeTask call must forward onSubagentProgress = callbacks.onSubagentProgress; " +
                "without it SpawnAgentTool.onSubagentProgress stays null and SubAgentView is dark"
        )
    }

    // ── AgentService.resumeDelegatedSession forwards the bundle's onSubagentProgress ──

    private fun resumeDelegatedResumeSessionCall(): String {
        val body = serviceSource.substringAfter("fun resumeDelegatedSession(").substringBefore("\n    fun ")
        val callStart = body.indexOf("resumeSession(")
        assertTrue(callStart >= 0, "resumeDelegatedSession must call resumeSession")
        return body.substring(callStart)
    }

    @Test
    fun `resumeDelegatedSession forwards bundle onSubagentProgress to resumeSession`() {
        val call = resumeDelegatedResumeSessionCall()
        assertTrue(
            Regex("""onSubagentProgress\s*=\s*[A-Za-z0-9_]+\.onSubagentProgress""").containsMatchIn(call),
            "resumeDelegatedSession's resumeSession call must forward onSubagentProgress = callbacks.onSubagentProgress"
        )
    }

    // ── The controller builder is the source of the onSubagentProgress link ──

    @Test
    fun `buildSessionUiCallbacks wires onSubagentProgress = colon colon onSubagentProgress`() {
        val body = controllerSource.substringAfter("fun buildSessionUiCallbacks(")
            .substringBefore("\n    private fun ")
        assertTrue(
            body.contains("onSubagentProgress = ::onSubagentProgress"),
            "buildSessionUiCallbacks must wire onSubagentProgress = ::onSubagentProgress, so both the " +
                "interactive and delegated paths source the SAME sub-agent progress callback"
        )
    }

    @Test
    fun `delegated controller entry points source callbacks from buildSessionUiCallbacks`() {
        val runNow = controllerSource.substringAfter("private fun runDelegatedNow(")
            .substringBefore("\n    private fun ")
        assertTrue(
            runNow.contains("buildSessionUiCallbacks()"),
            "runDelegatedNow must source its callbacks (incl. onSubagentProgress) from buildSessionUiCallbacks()"
        )
        val resumeNow = controllerSource.substringAfter("private fun runResumedDelegatedNow(")
            .substringBefore("\n    private fun ")
        assertTrue(
            resumeNow.contains("buildSessionUiCallbacks()"),
            "runResumedDelegatedNow must source its callbacks from buildSessionUiCallbacks()"
        )
    }
}
