package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.prreview.AnchorSide
import com.workflow.orchestrator.core.prreview.FindingSeverity
import com.workflow.orchestrator.core.prreview.PrReviewFinding
import com.workflow.orchestrator.core.prreview.PrReviewFindingsStore
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiReviewToolTest {

    private val project = mockk<Project>(relaxed = true)
    private val store = mockk<PrReviewFindingsStore>(relaxed = true)
    private val tool = AiReviewTool(project, storeProvider = { store })

    // ─────────────────────────────────────────────────────────
    // add_finding — happy path
    // ─────────────────────────────────────────────────────────

    @Test
    fun `add_finding happy path returns JSON with finding id`() = runTest {
        val finding = makeFinding(id = "abc-123", message = "Null pointer risk")
        coEvery { store.add(any()) } returns ToolResult.success(finding, "Finding abc-123 added")

        val result = tool.execute(
            buildJsonObject {
                put("action", "add_finding")
                put("pr_id", "42")
                put("session_id", "sess-1")
                put("severity", "NORMAL")
                put("message", "Null pointer risk")
            },
            project
        )

        assertFalse(result.isError, "expected success but got: ${result.content}")
        assertTrue(result.content.contains("abc-123"), "expected finding id in content")
    }

    // ─────────────────────────────────────────────────────────
    // add_finding — suggestion appended to message
    // ─────────────────────────────────────────────────────────

    @Test
    fun `add_finding with suggestion wraps value in suggestion fences appended to message`() = runTest {
        val capturedFindings = mutableListOf<PrReviewFinding>()
        coEvery { store.add(capture(capturedFindings)) } answers {
            ToolResult.success(capturedFindings.last(), "added")
        }

        tool.execute(
            buildJsonObject {
                put("action", "add_finding")
                put("pr_id", "42")
                put("session_id", "sess-1")
                put("severity", "BLOCKER")
                put("message", "Use requireNotNull here")
                put("suggestion", "requireNotNull(value) { \"value must not be null\" }")
            },
            project
        )

        val captured = capturedFindings.single()
        assertTrue(captured.message.contains("Use requireNotNull here"), "original message should be preserved")
        assertTrue(captured.message.contains("```suggestion"), "suggestion fence should be present")
        assertTrue(captured.message.contains("requireNotNull(value)"), "suggestion body should be present")
    }

    // ─────────────────────────────────────────────────────────
    // add_finding — missing required param (pr_id)
    // ─────────────────────────────────────────────────────────

    @Test
    fun `add_finding missing pr_id returns isError true`() = runTest {
        val result = tool.execute(
            buildJsonObject {
                put("action", "add_finding")
                put("session_id", "sess-1")
                put("severity", "NORMAL")
                put("message", "Something bad")
            },
            project
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("pr_id"), "error message should mention missing field")
    }

    // ─────────────────────────────────────────────────────────
    // add_finding — invalid severity string
    // ─────────────────────────────────────────────────────────

    @Test
    fun `add_finding invalid severity returns isError true`() = runTest {
        val result = tool.execute(
            buildJsonObject {
                put("action", "add_finding")
                put("pr_id", "42")
                put("session_id", "sess-1")
                put("severity", "CRITICAL")   // not a valid FindingSeverity
                put("message", "Something")
            },
            project
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("severity") || result.content.contains("CRITICAL"),
            "error should mention the invalid value")
    }

    // ─────────────────────────────────────────────────────────
    // list_findings — happy path
    // ─────────────────────────────────────────────────────────

    @Test
    fun `list_findings happy path returns JSON array of findings`() = runTest {
        val findings = listOf(
            makeFinding(id = "f1", message = "Issue one"),
            makeFinding(id = "f2", message = "Issue two", severity = FindingSeverity.BLOCKER),
        )
        coEvery { store.list("42", null, false) } returns ToolResult.success(findings, "2 findings")

        val result = tool.execute(
            buildJsonObject {
                put("action", "list_findings")
                put("pr_id", "42")
            },
            project
        )

        assertFalse(result.isError, "expected success but got: ${result.content}")
        assertTrue(result.content.contains("f1"), "result should contain first finding id")
        assertTrue(result.content.contains("f2"), "result should contain second finding id")
        // Verify it parses as a JSON array (starts with '[')
        assertTrue(result.content.trimStart().startsWith("["), "result should be a JSON array")
    }

    // ─────────────────────────────────────────────────────────
    // clear_findings — happy path
    // ─────────────────────────────────────────────────────────

    @Test
    fun `clear_findings happy path returns success`() = runTest {
        coEvery { store.clear("42", "sess-1") } returns ToolResult.success(Unit, "Cleared findings")

        val result = tool.execute(
            buildJsonObject {
                put("action", "clear_findings")
                put("pr_id", "42")
                put("session_id", "sess-1")
            },
            project
        )

        assertFalse(result.isError, "expected success but got: ${result.content}")
        assertTrue(result.content.contains("42") || result.content.lowercase().contains("clear"),
            "result should confirm clearance")
    }

    // ─────────────────────────────────────────────────────────
    // tool metadata
    // ─────────────────────────────────────────────────────────

    @Test
    fun `tool name is ai_review`() {
        assertEquals("ai_review", tool.name)
    }

    @Test
    fun `action is the only required parameter`() {
        assertEquals(listOf("action"), tool.parameters.required)
    }

    // ─────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────

    private fun makeFinding(
        id: String = UUID_PLACEHOLDER,
        message: String = "Test finding",
        severity: FindingSeverity = FindingSeverity.NORMAL,
    ) = PrReviewFinding(
        id = id,
        prId = "42",
        sessionId = "sess-1",
        severity = severity,
        message = message,
        createdAt = 1_000_000L,
    )

    companion object {
        private const val UUID_PLACEHOLDER = "00000000-0000-0000-0000-000000000000"
    }
}
