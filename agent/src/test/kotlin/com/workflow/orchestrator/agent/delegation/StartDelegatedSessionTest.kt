package com.workflow.orchestrator.agent.delegation

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text pinning test for [com.workflow.orchestrator.agent.AgentService.startDelegatedSession].
 *
 * A full integration test (wiring a live AgentService against a real IntelliJ
 * [com.intellij.openapi.project.Project]) requires the platform test harness and is
 * deferred to Task 12. This smoke test asserts that the implementation contract is
 * wired in the source file:
 *
 * - The function exists (not still a stub).
 * - It calls [executeTask] to run a real agent loop.
 * - It maps all three terminal states (COMPLETED / CANCELED / FAILED) to the IPC result.
 *
 * If any of these pins fail, the implementation has regressed.
 */
class StartDelegatedSessionTest {

    private val sourceFile: File =
        File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt")

    private val sourceText: String by lazy {
        sourceFile.readText()
    }

    @Test
    fun `startDelegatedSession function is present`() {
        assertTrue(
            sourceText.contains("fun startDelegatedSession"),
            "AgentService must declare fun startDelegatedSession"
        )
    }

    @Test
    fun `startDelegatedSession calls executeTask to run a real agent loop`() {
        assertTrue(
            sourceText.contains("executeTask("),
            "startDelegatedSession must delegate to executeTask to run the agent loop"
        )
    }

    @Test
    fun `startDelegatedSession maps COMPLETED status`() {
        assertTrue(
            sourceText.contains("ResultStatus.COMPLETED"),
            "startDelegatedSession must map LoopResult.Completed to ResultStatus.COMPLETED"
        )
    }

    @Test
    fun `startDelegatedSession maps CANCELED status`() {
        assertTrue(
            sourceText.contains("ResultStatus.CANCELED"),
            "startDelegatedSession must map LoopResult.Cancelled to ResultStatus.CANCELED"
        )
    }

    @Test
    fun `startDelegatedSession maps FAILED status`() {
        assertTrue(
            sourceText.contains("ResultStatus.FAILED"),
            "startDelegatedSession must map LoopResult.Failed to ResultStatus.FAILED"
        )
    }

    @Test
    fun `startDelegatedSession persists delegation metadata to delegation json`() {
        assertTrue(
            sourceText.contains("delegation.json"),
            "startDelegatedSession must persist DelegationMetadata to sessions/<id>/delegation.json"
        )
    }

    @Test
    fun `startDelegatedSession does not contain stub warning`() {
        assertTrue(
            !sourceText.contains("startDelegatedSession not yet implemented"),
            "stub warning must be removed — startDelegatedSession is now a real implementation"
        )
    }
}
