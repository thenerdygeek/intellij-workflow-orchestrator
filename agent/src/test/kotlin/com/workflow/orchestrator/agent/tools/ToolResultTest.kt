package com.workflow.orchestrator.agent.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolResultTest {

    @Test
    fun `error factory creates proper error result`() {
        val result = ToolResult.error("File not found", "not found")
        assertTrue(result.isError)
        assertEquals(ToolResult.ERROR_TOKEN_ESTIMATE, result.tokenEstimate)
        assertEquals("File not found", result.content)
        assertEquals("not found", result.summary)
    }

    @Test
    fun `error factory uses message as summary when summary is omitted`() {
        val result = ToolResult.error("Something went wrong")
        assertTrue(result.isError)
        assertEquals("Something went wrong", result.content)
        assertEquals("Something went wrong", result.summary)
        assertEquals(ToolResult.ERROR_TOKEN_ESTIMATE, result.tokenEstimate)
    }

    // ── ToolResultType sealed class tests ────────────────────────────────

    @Test
    fun `default type is Standard for normal result`() {
        val result = ToolResult("content", "summary", 10)
        assertEquals(ToolResultType.Standard, result.type)
    }

    @Test
    fun `default type is Error when isError is true`() {
        val result = ToolResult("content", "summary", 10, isError = true)
        assertEquals(ToolResultType.Error, result.type)
    }

    @Test
    fun `error factory produces Error type`() {
        val result = ToolResult.error("oops")
        assertEquals(ToolResultType.Error, result.type)
    }

    @Test
    fun `completion factory produces Completion type`() {
        val result = ToolResult.completion(
            "done", "completed", 10,
            completionData = CompletionData(CompletionKind.DONE, "done", verifyHow = "npm test")
        )
        assertTrue(result.type is ToolResultType.Completion)
        assertEquals("npm test", result.completionData?.verifyHow)
    }

    @Test
    fun `planResponse factory produces PlanResponse type`() {
        val result = ToolResult.planResponse("plan", "planned", 10, needsMoreExploration = true)
        val pr = result.type as ToolResultType.PlanResponse
        assertTrue(pr.needsMoreExploration)
    }

    @Test
    fun `skillActivation factory produces SkillActivation type`() {
        val result = ToolResult.skillActivation("loaded", "skill", 10, skillName = "tdd", skillContent = "content")
        val sa = result.type as ToolResultType.SkillActivation
        assertEquals("tdd", sa.skillName)
        assertEquals("content", sa.skillContent)
    }

    @Test
    fun `sessionHandoff factory produces SessionHandoff type`() {
        val result = ToolResult.sessionHandoff("handoff", "handing off", 10, context = "context data")
        val sh = result.type as ToolResultType.SessionHandoff
        assertEquals("context data", sh.context)
    }

    @Test
    fun `planModeToggle factory produces PlanModeToggle type`() {
        val result = ToolResult.planModeToggle("switched", "plan mode", 10)
        assertEquals(ToolResultType.PlanModeToggle, result.type)
    }

    @Test
    fun `completion type preserves isCompletion backward compat`() {
        val result = ToolResult.completion("done", "completed", 10)
        assertTrue(result.isCompletion)
        assertFalse(result.isPlanResponse)
        assertFalse(result.isSkillActivation)
        assertFalse(result.isSessionHandoff)
        assertFalse(result.enablePlanMode)
    }

    @Test
    fun `planResponse type preserves backward compat properties`() {
        val result = ToolResult.planResponse("plan", "planned", 10, needsMoreExploration = false)
        assertTrue(result.isPlanResponse)
        assertFalse(result.needsMoreExploration)
        assertFalse(result.isCompletion)
    }

    @Test
    fun `standard result has all flags false`() {
        val result = ToolResult("content", "summary", 10)
        assertFalse(result.isCompletion)
        assertFalse(result.isPlanResponse)
        assertFalse(result.isSkillActivation)
        assertFalse(result.isSessionHandoff)
        assertFalse(result.enablePlanMode)
    }

    @Test
    fun `artifacts field preserved on standard result`() {
        val result = ToolResult("content", "summary", 10, artifacts = listOf("file.kt"))
        assertEquals(listOf("file.kt"), result.artifacts)
        assertEquals(ToolResultType.Standard, result.type)
    }

    @Test
    fun `diff field preserved on standard result`() {
        val result = ToolResult("content", "summary", 10, diff = "--- a\n+++ b")
        assertEquals("--- a\n+++ b", result.diff)
    }

    @Test
    fun `completionData preserved through completion factory`() {
        val result = ToolResult.completion(
            "done", "completed", 10,
            completionData = CompletionData(CompletionKind.DONE, "done", verifyHow = "make test")
        )
        assertEquals("make test", result.completionData?.verifyHow)
        assertNull(result.completionData?.discovery)
    }

    @Test
    fun `when dispatch covers all types`() {
        val allTypes: List<ToolResultType> = listOf(
            ToolResultType.Standard,
            ToolResultType.Error,
            ToolResultType.Completion,
            ToolResultType.PlanResponse(needsMoreExploration = false),
            ToolResultType.SkillActivation("s", "c"),
            ToolResultType.SessionHandoff("ctx"),
            ToolResultType.PlanModeToggle,
            ToolResultType.PlanDiscarded,
        )
        for (t in allTypes) {
            val label = when (t) {
                is ToolResultType.Standard -> "standard"
                is ToolResultType.Error -> "error"
                is ToolResultType.Completion -> "completion"
                is ToolResultType.PlanResponse -> "plan"
                is ToolResultType.SkillActivation -> "skill"
                is ToolResultType.SessionHandoff -> "handoff"
                is ToolResultType.PlanModeToggle -> "toggle"
                is ToolResultType.PlanDiscarded -> "discarded"
            }
            assertNotNull(label)
        }
    }
}
