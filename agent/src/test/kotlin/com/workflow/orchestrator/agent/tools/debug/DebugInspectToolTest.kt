package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DebugInspectToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val controller = mockk<AgentDebugController>(relaxed = true)
    private val tool = DebugInspectTool(controller)

    @Test
    fun `tool name is debug_inspect`() {
        assertEquals("debug_inspect", tool.name)
    }

    @Test
    fun `action enum contains all 9 actions`() {
        val actions = tool.parameters.properties["action"]?.enumValues
        assertNotNull(actions)
        assertEquals(9, actions!!.size)
        assertTrue("evaluate" in actions)
        assertTrue("get_stack_frames" in actions)
        assertTrue("get_variables" in actions)
        assertTrue("set_value" in actions)
        assertTrue("thread_dump" in actions)
        assertTrue("memory_view" in actions)
        assertTrue("hotswap" in actions)
        assertTrue("force_return" in actions)
        assertTrue("drop_frame" in actions)
    }

    @Test
    fun `only action is required`() {
        assertEquals(listOf("action"), tool.parameters.required)
    }

    @Test
    fun `allowedWorkers includes CODER, REVIEWER, ANALYZER`() {
        assertEquals(
            setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER),
            tool.allowedWorkers
        )
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("debug_inspect", def.function.name)
        assertEquals("object", def.function.parameters.type)
        assertTrue(def.function.description.isNotBlank())
    }

    @Test
    fun `missing action returns error`() = runTest {
        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("action"))
    }

    @Test
    fun `unknown action returns error`() = runTest {
        val result = tool.execute(buildJsonObject { put("action", "nonexistent") }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Unknown action"))
    }

    @Test
    fun `get_variables on running session returns message mentioning session_id and get_state`() = runTest {
        // Arrange: a running (not paused) session is the only one
        val runningSession = mockk<XDebugSession>(relaxed = true) {
            every { isSuspended } returns false
            every { currentStackFrame } returns null
            every { suspendContext } returns null
        }
        mockkStatic(XDebuggerManager::class)
        val mgr = mockk<XDebuggerManager>(relaxed = true)
        every { XDebuggerManager.getInstance(project) } returns mgr
        every { mgr.debugSessions } returns arrayOf(runningSession)
        every { mgr.currentSession } returns runningSession

        // Act
        val result = tool.execute(buildJsonObject { put("action", "get_variables") }, project)

        // Assert
        assertTrue(result.isError)
        val content = result.content
        assertTrue(content.contains("session_id"), "expected 'session_id' in message: $content")
        assertTrue(content.contains("get_state"), "expected 'get_state' hint in message: $content")
        // Also confirm it still communicates the suspension requirement
        assertTrue(content.contains("paused", ignoreCase = true) || content.contains("suspend", ignoreCase = true),
            "expected message to still mention paused/suspended requirement: $content")

        unmockkStatic(XDebuggerManager::class)
    }

    // ── Bug 2 — drop_frame description must be explicit about PC-only rewind ─
    //
    // The action name implies a full rewind, but only the program counter moves.
    // Locals, fields, and any side effects already produced are NOT undone.
    // Fix: the `description` field must say "program counter" explicitly, and
    // `documentation().commonLLMMistakes` must have an entry about state not
    // being undone.
    //
    // These tests MUST fail before the fix and pass after.

    @Test
    fun `drop_frame description explicitly mentions program counter`() {
        // The tool-level description string is what the LLM sees. It must contain
        // "program counter" so the LLM understands the rewind is PC-only.
        assertTrue(
            tool.description.contains("program counter", ignoreCase = true),
            "drop_frame description must contain 'program counter' to signal PC-only semantics; " +
                "got description:\n${tool.description}"
        )
    }

    @Test
    fun `drop_frame documentation commonLLMMistakes contains state-not-undone entry`() {
        val doc = tool.documentation()
        val mistakes = doc.commonLLMMistakes
        val hasStateNotUndoneEntry = mistakes.any { entry ->
            (entry.contains("drop_frame", ignoreCase = true) ||
                entry.contains("side effect", ignoreCase = true)) &&
                (entry.contains("not undo", ignoreCase = true) ||
                    entry.contains("NOT undo", ignoreCase = false) ||
                    entry.contains("state", ignoreCase = true))
        }
        assertTrue(
            hasStateNotUndoneEntry,
            "documentation().commonLLMMistakes must contain an entry explaining that " +
                "drop_frame does not undo side effects / state. Entries found:\n" +
                mistakes.joinToString("\n- ", prefix = "- ")
        )
    }

    // ── evaluate retries once on "<value not ready>" sentinel ────────────────
    //
    // Feedback 2026-05-18: same-shape evaluations (org.slf4j.MDC.get("oemId") works,
    // org.slf4j.MDC.get("requestId") returns the sentinel) — the root cause is
    // stacked timeouts: 10s outer + 8s inner presentation deadline. When JDI
    // class-loading bites into the budget, the inner 8s presentation timeout
    // fires and the user sees "<value not ready — …>". A single tool-level
    // retry with a longer deadline absorbs the slow case without re-introducing
    // the queued-duplicate-evaluation regression that the in-controller retry
    // loop was removed for (the second attempt re-executes the expression
    // fresh — no overlapping listeners on the same XValue).

    @Test
    fun `evaluate retries once when first result is the value-not-ready sentinel`() = runTest {
        val sessionId = "sess-1"
        val pausedSession = mockk<XDebugSession>(relaxed = true) {
            every { isSuspended } returns true
        }
        mockkStatic(XDebuggerManager::class)
        val mgr = mockk<XDebuggerManager>(relaxed = true)
        every { XDebuggerManager.getInstance(project) } returns mgr
        every { mgr.debugSessions } returns arrayOf(pausedSession)
        every { mgr.currentSession } returns pausedSession
        every { pausedSession.sessionName } returns sessionId

        coEvery { controller.evaluate(pausedSession, "MDC.get(\"requestId\")", 0) } returnsMany listOf(
            EvaluationResult(
                "MDC.get(\"requestId\")",
                "<value not ready — JDI evaluation didn't complete in 8s; retry the evaluate / get_variables call>",
                "unknown",
            ),
            EvaluationResult("MDC.get(\"requestId\")", "\"abc-123\"", "java.lang.String"),
        )

        val result = tool.execute(
            buildJsonObject {
                put("action", "evaluate")
                put("expression", "MDC.get(\"requestId\")")
            },
            project,
        )

        assertFalse(result.isError, "second attempt's success should propagate as non-error")
        assertTrue(
            result.content.contains("\"abc-123\""),
            "expected retried value 'abc-123' in result content: ${result.content}",
        )
        assertFalse(
            result.content.contains("value not ready"),
            "must not surface the first attempt's sentinel after a successful retry: ${result.content}",
        )
        coVerify(exactly = 2) { controller.evaluate(pausedSession, "MDC.get(\"requestId\")", 0) }

        unmockkStatic(XDebuggerManager::class)
    }

    @Test
    fun `evaluate does not retry when the first attempt resolves`() = runTest {
        val pausedSession = mockk<XDebugSession>(relaxed = true) {
            every { isSuspended } returns true
        }
        mockkStatic(XDebuggerManager::class)
        val mgr = mockk<XDebuggerManager>(relaxed = true)
        every { XDebuggerManager.getInstance(project) } returns mgr
        every { mgr.debugSessions } returns arrayOf(pausedSession)
        every { mgr.currentSession } returns pausedSession

        coEvery { controller.evaluate(pausedSession, "1 + 1", 0) } returns
            EvaluationResult("1 + 1", "2", "int")

        val result = tool.execute(
            buildJsonObject {
                put("action", "evaluate")
                put("expression", "1 + 1")
            },
            project,
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("Result: 2"), "expected 'Result: 2' in: ${result.content}")
        coVerify(exactly = 1) { controller.evaluate(pausedSession, "1 + 1", 0) }

        unmockkStatic(XDebuggerManager::class)
    }

    @Test
    fun `evaluate surfaces sentinel when both attempts are not-ready`() = runTest {
        val pausedSession = mockk<XDebugSession>(relaxed = true) {
            every { isSuspended } returns true
        }
        mockkStatic(XDebuggerManager::class)
        val mgr = mockk<XDebuggerManager>(relaxed = true)
        every { XDebuggerManager.getInstance(project) } returns mgr
        every { mgr.debugSessions } returns arrayOf(pausedSession)
        every { mgr.currentSession } returns pausedSession

        coEvery { controller.evaluate(pausedSession, "stuckExpr()", 0) } returns
            EvaluationResult(
                "stuckExpr()",
                "<value not ready — JDI evaluation didn't complete in 8s; retry the evaluate / get_variables call>",
                "unknown",
            )

        val result = tool.execute(
            buildJsonObject {
                put("action", "evaluate")
                put("expression", "stuckExpr()")
            },
            project,
        )

        // The sentinel itself is non-error (presentation didn't render, but eval didn't fail);
        // the tool should still surface it so the LLM can decide what to do.
        assertTrue(
            result.content.contains("value not ready"),
            "expected sentinel in result content when both attempts fail to render: ${result.content}",
        )
        coVerify(exactly = 2) { controller.evaluate(pausedSession, "stuckExpr()", 0) }

        unmockkStatic(XDebuggerManager::class)
    }
}
