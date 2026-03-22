package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EvaluateExpressionToolTest {
    private val controller = mockk<AgentDebugController>(relaxed = true)
    private val project = mockk<Project>(relaxed = true)
    private val tool = EvaluateExpressionTool(controller)

    @Test
    fun `metadata is correct`() {
        assertEquals("evaluate_expression", tool.name)
        assertTrue(tool.description.contains("Evaluate"))
        assertTrue(tool.description.contains("side effects"))
    }

    @Test
    fun `expression is required`() {
        assertEquals(listOf("expression"), tool.parameters.required)
    }

    @Test
    fun `has expression, session_id, and frame_index parameters`() {
        val props = tool.parameters.properties
        assertEquals(3, props.size)
        assertTrue(props.containsKey("expression"))
        assertTrue(props.containsKey("session_id"))
        assertTrue(props.containsKey("frame_index"))
    }

    @Test
    fun `allowed workers include CODER, REVIEWER, and ANALYZER`() {
        assertEquals(setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER), tool.allowedWorkers)
    }

    @Test
    fun `returns error when expression is missing`() = runTest {
        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing required parameter: expression"))
    }

    @Test
    fun `returns error when expression is blank`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("expression", "   ")
        }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("cannot be blank"))
    }

    @Test
    fun `returns error when no session found`() = runTest {
        every { controller.getActiveSessionId() } returns null
        every { controller.getSession(null) } returns null

        val result = tool.execute(buildJsonObject {
            put("expression", "x + 1")
        }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("No debug session found"))
    }

    @Test
    fun `returns error when specified session not found`() = runTest {
        every { controller.getSession("bad-id") } returns null

        val result = tool.execute(buildJsonObject {
            put("expression", "x + 1")
            put("session_id", "bad-id")
        }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("No debug session found"))
        assertTrue(result.content.contains("bad-id"))
    }

    @Test
    fun `returns error when session not suspended`() = runTest {
        val session = mockk<XDebugSession>(relaxed = true)
        every { controller.getSession(null) } returns session
        every { session.isSuspended } returns false

        val result = tool.execute(buildJsonObject {
            put("expression", "x + 1")
        }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("not suspended"))
    }

    @Test
    fun `returns error when frame_index is negative`() = runTest {
        val result = tool.execute(buildJsonObject {
            put("expression", "x + 1")
            put("frame_index", -1)
        }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("frame_index must be >= 0"))
    }

    @Nested
    inner class WithSuspendedSession {
        private val session = mockk<XDebugSession>(relaxed = true)

        @BeforeEach
        fun setUp() {
            every { controller.getSession(any()) } returns session
            every { controller.getSession(null) } returns session
            every { session.isSuspended } returns true
        }

        @Test
        fun `returns successful evaluation result`() = runTest {
            coEvery { controller.evaluate(session, "user.getName()", 0) } returns EvaluationResult(
                expression = "user.getName()",
                result = "\"John Doe\"",
                type = "java.lang.String"
            )

            val result = tool.execute(buildJsonObject {
                put("expression", "user.getName()")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("Expression: user.getName()"))
            assertTrue(result.content.contains("Result: \"John Doe\""))
            assertTrue(result.content.contains("Type: java.lang.String"))
        }

        @Test
        fun `returns evaluation error from controller`() = runTest {
            coEvery { controller.evaluate(session, "nonExistent", 0) } returns EvaluationResult(
                expression = "nonExistent",
                result = "Cannot find local variable 'nonExistent'",
                type = "error",
                isError = true
            )

            val result = tool.execute(buildJsonObject {
                put("expression", "nonExistent")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Cannot find local variable 'nonExistent'"))
        }

        @Test
        fun `passes frame_index to controller`() = runTest {
            coEvery { controller.evaluate(session, "x", 2) } returns EvaluationResult(
                expression = "x",
                result = "42",
                type = "int"
            )

            val result = tool.execute(buildJsonObject {
                put("expression", "x")
                put("frame_index", 2)
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("Result: 42"))
            coVerify { controller.evaluate(session, "x", 2) }
        }

        @Test
        fun `uses specified session_id`() = runTest {
            coEvery { controller.evaluate(session, "1+1", 0) } returns EvaluationResult(
                expression = "1+1",
                result = "2",
                type = "int"
            )

            tool.execute(buildJsonObject {
                put("expression", "1+1")
                put("session_id", "debug-5")
            }, project)
            verify { controller.getSession("debug-5") }
        }
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("evaluate_expression", def.function.name)
        assertEquals("object", def.function.parameters.type)
        assertEquals(listOf("expression"), def.function.parameters.required)
    }
}
