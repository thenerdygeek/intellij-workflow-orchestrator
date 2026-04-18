package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text guardrails that pin the FailureReason wiring in AgentLoop and AgentService
 * so the correct reason is always carried to the UI. Each test asserts that the expected
 * FailureReason enum constant appears in the relevant makeFailed() call at the identified
 * exit site. Pattern identical to RunCommandStreamingWiringTest.
 */
class FailureReasonWiringTest {

    @Test
    fun `AgentLoop API error exit carries API_ERROR reason`() {
        val text = readSource("loop", "AgentLoop.kt")
        assertTrue(
            text.contains("makeFailed(apiResult.message, iteration, FailureReason.API_ERROR)"),
            "AgentLoop.kt must pass FailureReason.API_ERROR to makeFailed() at the API-retries-exhausted exit."
        )
    }

    @Test
    fun `AgentLoop no-tools-used exit carries NO_TOOLS_USED reason`() {
        val text = readSource("loop", "AgentLoop.kt")
        assertTrue(
            text.contains("FailureReason.NO_TOOLS_USED"),
            "AgentLoop.kt must pass FailureReason.NO_TOOLS_USED to makeFailed() at the max-consecutive-mistakes exit."
        )
    }

    @Test
    fun `AgentLoop empty-responses exit carries EMPTY_RESPONSES reason`() {
        val text = readSource("loop", "AgentLoop.kt")
        assertTrue(
            text.contains("FailureReason.EMPTY_RESPONSES"),
            "AgentLoop.kt must pass FailureReason.EMPTY_RESPONSES to makeFailed() at the consecutive-empties exit."
        )
    }

    @Test
    fun `AgentLoop max-iterations exit carries MAX_ITERATIONS reason`() {
        val text = readSource("loop", "AgentLoop.kt")
        assertTrue(
            text.contains("FailureReason.MAX_ITERATIONS"),
            "AgentLoop.kt must pass FailureReason.MAX_ITERATIONS to makeFailed() at the max-iterations exit. " +
                "This is the reason that drives the 'Continue' button in the UI."
        )
    }

    @Test
    fun `AgentLoop doom-loop exit carries DOOM_LOOP reason`() {
        val text = readSource("loop", "AgentLoop.kt")
        assertTrue(
            text.contains("FailureReason.DOOM_LOOP"),
            "AgentLoop.kt must pass FailureReason.DOOM_LOOP to makeFailed() at the hard-loop-limit exit."
        )
    }

    @Test
    fun `AgentService exception path carries EXCEPTION reason`() {
        val text = readSource("", "AgentService.kt")
        assertTrue(
            text.contains("FailureReason.EXCEPTION"),
            "AgentService.kt must pass FailureReason.EXCEPTION to LoopResult.Failed() in the catch block."
        )
    }

    @Test
    fun `AgentController retry handler sends continue not lastTaskText`() {
        val text = readSource("ui", "AgentController.kt")
        assertTrue(
            text.contains("""executeTask("continue", "continue", null)"""),
            "AgentController.kt retry callback must call executeTask(\"continue\", ...) — not replay lastTaskText. " +
                "Replaying the original task makes the LLM think its prior work was wrong."
        )
    }

    @Test
    fun `AgentController showRetryButton branches on MAX_ITERATIONS for Continue vs Retry`() {
        val text = readSource("ui", "AgentController.kt")
        assertTrue(
            text.contains("result.reason == FailureReason.MAX_ITERATIONS"),
            "AgentController.kt must branch on FailureReason.MAX_ITERATIONS to choose 'continue' kind for the UI pill."
        )
    }

    // ── Helpers ──

    private fun readSource(subPackage: String, name: String): String {
        val userDir = System.getProperty("user.dir")
            ?: error("user.dir system property is not set")
        val root = File(userDir)
        val rel = if (subPackage.isEmpty()) {
            "src/main/kotlin/com/workflow/orchestrator/agent/$name"
        } else {
            "src/main/kotlin/com/workflow/orchestrator/agent/$subPackage/$name"
        }
        val moduleRooted = File(root, rel)
        val repoRooted = File(root, "agent/$rel")
        val path = when {
            moduleRooted.isFile -> moduleRooted
            repoRooted.isFile -> repoRooted
            else -> error(
                "Source file '$name' not found at either expected path:\n" +
                    "  1. ${moduleRooted.absolutePath}\n" +
                    "  2. ${repoRooted.absolutePath}\n" +
                    "user.dir=$userDir"
            )
        }
        return path.readText()
    }
}
