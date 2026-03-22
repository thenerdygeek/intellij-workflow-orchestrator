package com.workflow.orchestrator.agent.tools.debug

import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AgentDebugControllerTest {

    private lateinit var controller: AgentDebugController

    @BeforeEach
    fun setup() {
        controller = AgentDebugController()
    }

    @AfterEach
    fun tearDown() {
        controller.dispose()
    }

    @Test
    fun `registerSession assigns unique IDs`() {
        val session1 = createMockSession()
        val session2 = createMockSession()

        val id1 = controller.registerSession(session1)
        val id2 = controller.registerSession(session2)

        assertNotEquals(id1, id2)
        assertTrue(id1.startsWith("debug-"))
        assertTrue(id2.startsWith("debug-"))
    }

    @Test
    fun `getSession returns registered session`() {
        val session = createMockSession()
        val id = controller.registerSession(session)

        val retrieved = controller.getSession(id)

        assertSame(session, retrieved)
    }

    @Test
    fun `getSession with null returns active session`() {
        val session = createMockSession()
        controller.registerSession(session)

        val retrieved = controller.getSession(null)

        assertSame(session, retrieved)
    }

    @Test
    fun `getSession returns null for unknown ID`() {
        assertNull(controller.getSession("nonexistent"))
    }

    @Test
    fun `getActiveSessionId returns latest registered session`() {
        val session1 = createMockSession()
        val session2 = createMockSession()

        controller.registerSession(session1)
        val id2 = controller.registerSession(session2)

        assertEquals(id2, controller.getActiveSessionId())
    }

    @Test
    fun `waitForPause returns immediately when already suspended`() = runTest {
        val session = createMockSession(isSuspended = true)
        val id = controller.registerSession(session)

        val event = controller.waitForPause(id, timeoutMs = 1000)

        assertNotNull(event)
        assertEquals(id, event!!.sessionId)
        assertEquals("/path/to/File.kt", event.file)
        assertEquals(11, event.line) // 0-based (10) + 1
        assertEquals("breakpoint", event.reason)
    }

    @Test
    fun `waitForPause returns null on timeout`() = runTest {
        val session = createMockSession(isSuspended = false)
        val id = controller.registerSession(session)

        val event = controller.waitForPause(id, timeoutMs = 100)

        assertNull(event)
    }

    @Test
    fun `waitForPause returns null for unknown session`() = runTest {
        val event = controller.waitForPause("nonexistent", timeoutMs = 100)
        assertNull(event)
    }

    @Test
    fun `stopAllSessions stops all tracked sessions`() {
        val session1 = createMockSession()
        val session2 = createMockSession()

        val id1 = controller.registerSession(session1)
        val id2 = controller.registerSession(session2)

        controller.stopAllSessions()

        verify { session1.stop() }
        verify { session2.stop() }
        assertNull(controller.getSession(id1))
        assertNull(controller.getSession(id2))
        assertNull(controller.getActiveSessionId())
    }

    @Test
    fun `evaluate returns error when no active frame`() = runTest {
        val session = mockk<XDebugSession>(relaxed = true) {
            every { currentStackFrame } returns null
            every { addSessionListener(any()) } just Runs
        }
        controller.registerSession(session)

        val result = controller.evaluate(session, "x + 1")

        assertTrue(result.isError)
        assertEquals("x + 1", result.expression)
        assertTrue(result.result.contains("No active stack frame"))
    }

    @Test
    fun `evaluate returns error when no evaluator available`() = runTest {
        val frame = mockk<XStackFrame>(relaxed = true) {
            every { evaluator } returns null
        }
        val session = mockk<XDebugSession>(relaxed = true) {
            every { currentStackFrame } returns frame
            every { addSessionListener(any()) } just Runs
        }
        controller.registerSession(session)

        val result = controller.evaluate(session, "y * 2")

        assertTrue(result.isError)
        assertTrue(result.result.contains("No evaluator"))
    }

    @Test
    fun `evaluate delegates to XDebuggerEvaluator`() = runTest {
        val evaluator = mockk<XDebuggerEvaluator>(relaxed = true)
        val frame = mockk<XStackFrame>(relaxed = true) {
            every { getEvaluator() } returns evaluator
        }
        val session = mockk<XDebugSession>(relaxed = true) {
            every { currentStackFrame } returns frame
            every { addSessionListener(any()) } just Runs
        }

        // Capture the callback and invoke it — evaluate(String, XEvaluationCallback, XSourcePosition?)
        every { evaluator.evaluate(any<String>(), any(), any()) } answers {
            val callback = secondArg<XDebuggerEvaluator.XEvaluationCallback>()
            callback.errorOccurred("Variable not found")
        }

        controller.registerSession(session)
        val result = controller.evaluate(session, "unknownVar")

        assertTrue(result.isError)
        assertEquals("Variable not found", result.result)
    }

    @Test
    fun `trackBreakpoint stores breakpoint for cleanup`() {
        val bp = mockk<XLineBreakpoint<*>>(relaxed = true)
        controller.trackBreakpoint(bp)
        // Verify no crash — cleanup happens on dispose
        controller.removeAgentBreakpoints()
    }

    @Test
    fun `dispose stops sessions and clears breakpoints`() {
        val session = createMockSession()
        controller.registerSession(session)
        val bp = mockk<XLineBreakpoint<*>>(relaxed = true)
        controller.trackBreakpoint(bp)

        controller.dispose()

        verify { session.stop() }
        assertNull(controller.getActiveSessionId())
    }

    @Test
    fun `session listener callback fires pause event`() = runTest {
        val listenerSlot = slot<XDebugSessionListener>()
        val position = mockk<XSourcePosition> {
            every { file } returns mockk {
                every { path } returns "/src/Main.kt"
            }
            every { line } returns 24
        }
        val session = mockk<XDebugSession>(relaxed = true) {
            every { addSessionListener(capture(listenerSlot)) } just Runs
            every { isSuspended } returns false andThen true
            every { currentPosition } returns position
        }

        val id = controller.registerSession(session)

        // Simulate the debugger pausing
        listenerSlot.captured.sessionPaused()

        // Now waitForPause should see the emitted event
        every { session.isSuspended } returns true
        val event = controller.waitForPause(id, timeoutMs = 1000)

        assertNotNull(event)
        assertEquals("/src/Main.kt", event!!.file)
        assertEquals(25, event.line) // 24 + 1
        assertEquals("breakpoint", event.reason)
    }

    // --- Helper ---

    private fun createMockSession(isSuspended: Boolean = false): XDebugSession {
        val position = mockk<XSourcePosition> {
            every { file } returns mockk {
                every { path } returns "/path/to/File.kt"
            }
            every { line } returns 10
        }
        return mockk<XDebugSession>(relaxed = true) {
            every { this@mockk.isSuspended } returns isSuspended
            every { currentPosition } returns position
            every { addSessionListener(any()) } just Runs
            every { stop() } just Runs
        }
    }
}
