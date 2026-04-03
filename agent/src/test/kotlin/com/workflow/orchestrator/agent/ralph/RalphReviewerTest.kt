package com.workflow.orchestrator.agent.ralph

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RalphReviewerTest {

    @Test
    fun `parses ACCEPT verdict`() {
        val result = RalphReviewer.parseResponse("ACCEPT — work meets requirements")
        assertEquals(ReviewVerdict.ACCEPT, result.verdict)
        assertNull(result.feedback)
    }

    @Test
    fun `parses ACCEPT with no trailing text`() {
        val result = RalphReviewer.parseResponse("ACCEPT")
        assertEquals(ReviewVerdict.ACCEPT, result.verdict)
    }

    @Test
    fun `parses IMPROVE verdict with feedback`() {
        val result = RalphReviewer.parseResponse("IMPROVE: Missing error handling in UserController.kt")
        assertEquals(ReviewVerdict.IMPROVE, result.verdict)
        assertEquals("Missing error handling in UserController.kt", result.feedback)
    }

    @Test
    fun `parses IMPROVE with multiline feedback`() {
        val result = RalphReviewer.parseResponse(
            "IMPROVE: Two issues found:\n1. No input validation\n2. Missing unit tests"
        )
        assertEquals(ReviewVerdict.IMPROVE, result.verdict)
        assertTrue(result.feedback!!.contains("No input validation"))
        assertTrue(result.feedback!!.contains("Missing unit tests"))
    }

    @Test
    fun `defaults to IMPROVE for ambiguous response`() {
        val result = RalphReviewer.parseResponse("The code has some issues that need fixing")
        assertEquals(ReviewVerdict.IMPROVE, result.verdict)
        assertEquals("The code has some issues that need fixing", result.feedback)
    }

    @Test
    fun `detects ACCEPT embedded in response`() {
        val result = RalphReviewer.parseResponse("After reviewing the code, I'll say ACCEPT. It looks good.")
        assertEquals(ReviewVerdict.ACCEPT, result.verdict)
    }

    @Test
    fun `IMPROVE takes priority when both present`() {
        val result = RalphReviewer.parseResponse("I want to ACCEPT but must IMPROVE: fix the bug first")
        assertEquals(ReviewVerdict.IMPROVE, result.verdict)
    }

    @Test
    fun `trims whitespace from feedback`() {
        val result = RalphReviewer.parseResponse("IMPROVE:   needs tests   ")
        assertEquals("needs tests", result.feedback)
    }

    @Test
    fun `caps feedback at MAX_FEEDBACK_LENGTH`() {
        val longFeedback = "IMPROVE: " + "x".repeat(3000)
        val result = RalphReviewer.parseResponse(longFeedback)
        assertTrue(result.feedback!!.length <= RalphReviewer.MAX_FEEDBACK_LENGTH)
    }

    @Test
    fun `buildReviewerPrompt includes all sections`() {
        val prompt = RalphReviewer.buildReviewerPrompt(
            originalTask = "Build REST API",
            iteration = 2, maxIterations = 10,
            completionSummary = "Added /users endpoint",
            changedFiles = listOf("src/UserController.kt", "src/UserService.kt"),
            planStatus = "Step 1: done, Step 2: pending",
            priorFeedback = "Add validation"
        )
        assertTrue(prompt.contains("Build REST API"))
        assertTrue(prompt.contains("2 of 10"))
        assertTrue(prompt.contains("Added /users endpoint"))
        assertTrue(prompt.contains("UserController.kt"))
        assertTrue(prompt.contains("Step 1: done"))
        assertTrue(prompt.contains("Add validation"))
        assertTrue(prompt.contains("ACCEPT"))
        assertTrue(prompt.contains("IMPROVE"))
    }

    @Test
    fun `buildReviewerPrompt omits optional sections when null`() {
        val prompt = RalphReviewer.buildReviewerPrompt(
            originalTask = "Fix bug", iteration = 1, maxIterations = 5,
            completionSummary = "Fixed it", changedFiles = emptyList(),
            planStatus = null, priorFeedback = null
        )
        assertFalse(prompt.contains("plan_status"))
        assertFalse(prompt.contains("prior_reviewer_feedback"))
    }
}
