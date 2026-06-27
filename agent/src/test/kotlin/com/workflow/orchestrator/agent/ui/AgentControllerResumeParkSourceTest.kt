package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * BUG-1 (owner's #1): clicking the in-chat "Resume" button on an *interrupted* session
 * immediately re-ran the agent loop (re-emitted the tool call, re-opened the approval gate)
 * with NO user message typed. Desired = **load-and-park**: a no-text Resume click must show
 * the conversation and wait; the iteration only starts once the user sends a message.
 *
 * The fix lives in [AgentController.resumeViewedSession]: when `userText.isNullOrBlank()` it
 * must NOT call [AgentController.resumeSession] (which auto-runs the loop). It parks instead —
 * dismissing the resume bar's auto-run affordance and leaving the session VIEWED so the next
 * typed message routes back through `resumeViewedSession(text)` → `resumeSession(sessionId,
 * text)` (which rehydrates persisted history). Keeping `viewedSessionId` SET in the park case
 * is load-bearing: clearing it would route a later typed message to the fresh-session
 * `executeTask` path (new ContextManager) and LOSE the conversation history.
 *
 * AgentController is not unit-instantiable (`AgentService.getInstance(project)` + a JCEF
 * dashboard), so per repo precedent (see [AgentControllerSessionActiveSourceTest],
 * [AgentControllerShowSessionOffEdtTest]) this pins the source text of the affected function.
 */
class AgentControllerResumeParkSourceTest {

    private val src = java.io.File(
        "src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt"
    ).readText()

    /** Body of `resumeViewedSession` only — `resumeSession` immediately follows it. */
    private val fn = src
        .substringAfter("fun resumeViewedSession(userText: String? = null) {")
        .substringBefore("\n    fun resumeSession(")

    @Test
    fun `no-text Resume click parks and does NOT call resumeSession`() {
        val guardIdx = fn.indexOf("userText.isNullOrBlank()")
        assertTrue(
            guardIdx >= 0,
            "resumeViewedSession must guard the no-text path with userText.isNullOrBlank() (load-and-park)"
        )
        val resumeCallIdx = fn.indexOf("resumeSession(sessionId, userText)")
        assertTrue(resumeCallIdx >= 0, "the non-blank path must still continue via resumeSession(sessionId, userText)")

        val parkReturnIdx = fn.indexOf("return", guardIdx)
        assertTrue(parkReturnIdx in (guardIdx + 1) until resumeCallIdx) {
            "the blank-userText branch must early-return BEFORE reaching resumeSession — " +
                "otherwise the loop auto-runs (the BUG-1 regression)"
        }
    }

    @Test
    fun `park branch keeps viewedSessionId SET so the next typed message rehydrates history`() {
        val guardIdx = fn.indexOf("userText.isNullOrBlank()")
        assertTrue(guardIdx >= 0, "no-text guard missing")
        val parkReturnIdx = fn.indexOf("return", guardIdx)
        assertTrue(parkReturnIdx > guardIdx, "park branch must early-return")

        val parkBranch = fn.substring(guardIdx, parkReturnIdx)
        assertFalse(
            parkBranch.contains("viewedSessionId = null"),
            "the park branch must NOT clear viewedSessionId — clearing it routes the next typed " +
                "message to the fresh-session executeTask path and loses the conversation history"
        )
        // The only viewedSessionId = null lives in the non-blank (continue-now) path, after the guard.
        val nullIdx = fn.indexOf("viewedSessionId = null")
        assertTrue(
            nullIdx > guardIdx,
            "viewedSessionId must only be cleared in the non-blank continue-now path, after the guard"
        )
    }

    @Test
    fun `non-blank Resume still continues the loop with history via resumeSession`() {
        assertTrue(
            fn.contains("resumeSession(sessionId, userText)"),
            "resume-WITH-text must keep continuing via resumeSession(sessionId, userText)"
        )
    }

    @Test
    fun `park dismisses the resume bar's auto-run affordance`() {
        val guardIdx = fn.indexOf("userText.isNullOrBlank()")
        val parkReturnIdx = fn.indexOf("return", guardIdx)
        val parkBranch = fn.substring(guardIdx, parkReturnIdx)
        assertTrue(
            parkBranch.contains("hideResumeBar"),
            "the park branch must hide the resume bar so the user isn't left able to re-trigger auto-run"
        )
    }

    @Test
    fun `typed-while-viewing path routes back through resumeViewedSession with the text`() {
        // The continue-by-typing path (executeTaskInternal viewed-session branch) is what makes
        // the park usable: after parking, the user's next message re-enters here WITH text.
        assertTrue(
            src.contains("resumeViewedSession(task)"),
            "the viewed-session branch must route a typed message to resumeViewedSession(task)"
        )
    }
}
