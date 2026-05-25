package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Repro guard for the post-handoff/post-resume chat-wipe bug: the view-reset
 * (dashboard.startSession) must NOT be gated on `contextManager == null`, because
 * handoff/resume legitimately leave that field null while a session is active.
 */
class AgentControllerSessionActiveSourceTest {
    private val src = java.io.File(
        "src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt"
    ).readText()

    @Test
    fun `a sessionActive flag exists and gates the first-message view reset`() {
        assertTrue(src.contains("sessionActive"), "sessionActive flag missing")
        assertTrue(
            src.contains("val isFirstMessage = !sessionActive"),
            "isFirstMessage must be gated on !sessionActive, not contextManager == null"
        )
    }

    @Test
    fun `isFirstMessage is no longer derived from contextManager == null`() {
        assertFalse(
            src.contains("val isFirstMessage = contextManager == null"),
            "the buggy gate must be removed"
        )
    }

    @Test
    fun `resetForNewChat resets sessionActive`() {
        // newChat() is a 5-line delegate; the real reset (incl. currentSessionId = null)
        // lives in resetForNewChat(). Anchor there. (Review item #7.)
        val reset = src.substringAfter("fun resetForNewChat(").substringBefore("\n    fun ")
        assertTrue(reset.contains("sessionActive = false"), "resetForNewChat must clear sessionActive")
    }

    @Test
    fun `handoff decision handlers send the loop sentinels through the channel`() {
        assertTrue(src.contains("HANDOFF_FORK_SENTINEL"), "fork handler must send fork sentinel")
        assertTrue(src.contains("HANDOFF_DECLINE_SENTINEL"), "decline handler must send decline sentinel")
        assertTrue(src.contains("setCefHandoffCallbacks"), "handoff card callbacks not wired")
    }

    @Test
    fun `resume and revert mark the session active`() {
        val resume = src.substringAfter("fun resumeSession(").substringBefore("\n    fun ")
        assertTrue(resume.contains("sessionActive = true"), "resume must set sessionActive")
        val revert = src.substringAfter("suspend fun revertToUserMessage(").substringBefore("\n    /**")
        assertTrue(revert.contains("sessionActive = true"), "revert must set sessionActive")
    }

    @Test
    fun `handoff branch no longer shows the misleading context-limit caption`() {
        assertFalse(src.contains("Context limit reached. Starting fresh session"),
            "the misleading caption must be removed")
    }

    @Test
    fun `handoff branch wires onSessionStarted and onContextManagerReady for the forked session`() {
        // Use a unique anchor from the actual handoff onComplete branch (not the debug-log branch)
        val branch = src.substringAfter("Continuing in a fresh session with the preserved context.")
        assertTrue(branch.contains("startHandoffSession"), "startHandoffSession not in handoff branch")
        assertTrue(branch.contains("onSessionStarted"), "onSessionStarted not in handoff branch")
        assertTrue(branch.contains("onContextManagerReady"), "onContextManagerReady not in handoff branch")
    }

    // ── Regression guards for the two call-site omissions the final review caught ──
    // The new_task card only works if the controller actually FORWARDS onHandoffProposed
    // to the loop at every session-entry call site; otherwise the loop suspends with no
    // card and deadlocks. These pin the primary executeTask path and the resume path.

    @Test
    fun `primary executeTask call forwards onHandoffProposed`() {
        // handleUserMessage's executeTask call must pass the handoff render callback,
        // or a new_task in a normal session renders no card and deadlocks.
        // Bound on the unique comment that immediately follows the call's closing paren —
        // substringBefore(")") would stop at the first paren inside an early lambda arg.
        val call = src.substringAfter("currentJob = service.executeTask(")
            .substringBefore("// Start 30s Haiku phrase timer")
        assertTrue(
            call.contains("onHandoffProposed = ::onHandoffProposed"),
            "primary executeTask call must forward onHandoffProposed"
        )
    }

    @Test
    fun `resumeSession call forwards onHandoffProposed and onContextManagerReady`() {
        val call = src.substringAfter("service.resumeSession(").substringBefore("\n        )")
        assertTrue(
            call.contains("onHandoffProposed = ::onHandoffProposed"),
            "resumeSession call must forward onHandoffProposed (else new_task deadlocks on resume)"
        )
        assertTrue(
            call.contains("onContextManagerReady"),
            "resumeSession call must forward onContextManagerReady (else post-resume context is lost)"
        )
    }
}
