package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class GetVariablesToolTest {
    private val controller = mockk<AgentDebugController>(relaxed = true)
    private val project = mockk<Project>(relaxed = true)
    private val tool = GetVariablesTool(controller)

    @Test
    fun `metadata is correct`() {
        assertEquals("get_variables", tool.name)
        assertTrue(tool.description.contains("variable values"))
        assertTrue(tool.description.contains("stack frame"))
    }

    @Test
    fun `no required parameters`() {
        assertTrue(tool.parameters.required.isEmpty())
    }

    @Test
    fun `has session_id, max_depth, and variable_name parameters`() {
        val props = tool.parameters.properties
        assertEquals(3, props.size)
        assertTrue(props.containsKey("session_id"))
        assertTrue(props.containsKey("max_depth"))
        assertTrue(props.containsKey("variable_name"))
    }

    @Test
    fun `allowed workers include CODER, REVIEWER, and ANALYZER`() {
        assertEquals(setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER), tool.allowedWorkers)
    }

    @Test
    fun `returns error when no session found`() = runTest {
        every { controller.getActiveSessionId() } returns null
        every { controller.getSession(null) } returns null

        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("No debug session found"))
    }

    @Test
    fun `returns error when specified session not found`() = runTest {
        every { controller.getSession("bad-id") } returns null

        val result = tool.execute(buildJsonObject {
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

        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("not suspended"))
    }


    @Nested
    inner class WithSuspendedSession {
        private val session = mockk<XDebugSession>(relaxed = true)
        private val frame = mockk<XStackFrame>(relaxed = true)
        private val sourcePos = mockk<XSourcePosition>(relaxed = true)
        private val vFile = mockk<VirtualFile>(relaxed = true)

        @BeforeEach
        fun setUp() {
            every { controller.getSession(any()) } returns session
            every { controller.getSession(null) } returns session
            every { session.isSuspended } returns true
            every { session.currentStackFrame } returns frame
            every { session.currentPosition } returns sourcePos
            every { sourcePos.file } returns vFile
            every { vFile.name } returns "UserService.kt"
            every { sourcePos.line } returns 77 // 0-based, displayed as 78
        }

        @Test
        fun `returns no variables message when frame has none`() = runTest {
            coEvery { controller.getVariables(frame, any()) } returns emptyList()

            val result = tool.execute(buildJsonObject { }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("No variables"))
        }

        @Test
        fun `returns formatted variables`() = runTest {
            val vars = listOf(
                VariableInfo("request", "CreateUserRequest", "{...}", listOf(
                    VariableInfo("email", "String", "\"test@example.com\""),
                    VariableInfo("name", "String", "\"Test User\"")
                )),
                VariableInfo("existingUser", "User?", "null")
            )
            coEvery { controller.getVariables(frame, any()) } returns vars

            val result = tool.execute(buildJsonObject { }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("Frame #0"))
            assertTrue(result.content.contains("Variables:"))
            assertTrue(result.content.contains("request: CreateUserRequest"))
            assertTrue(result.content.contains("email: String"))
            assertTrue(result.content.contains("existingUser: User?"))
        }

        @Test
        fun `filters to specific variable when variable_name specified`() = runTest {
            val vars = listOf(
                VariableInfo("x", "int", "42"),
                VariableInfo("y", "String", "\"hello\""),
                VariableInfo("z", "double", "3.14")
            )
            coEvery { controller.getVariables(frame, any()) } returns vars

            val result = tool.execute(buildJsonObject {
                put("variable_name", "y")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("y: String"))
            assertFalse(result.content.contains("x: int"))
            assertFalse(result.content.contains("z: double"))
        }

        @Test
        fun `returns error when variable_name not found`() = runTest {
            val vars = listOf(
                VariableInfo("x", "int", "42")
            )
            coEvery { controller.getVariables(frame, any()) } returns vars

            val result = tool.execute(buildJsonObject {
                put("variable_name", "nonExistent")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Variable 'nonExistent' not found"))
            assertTrue(result.content.contains("Available: x"))
        }

        @Test
        fun `respects max_depth parameter`() = runTest {
            coEvery { controller.getVariables(frame, 3) } returns listOf(
                VariableInfo("a", "int", "1")
            )

            tool.execute(buildJsonObject {
                put("max_depth", 3)
            }, project)

            coVerify { controller.getVariables(frame, 3) }
        }

        @Test
        fun `clamps max_depth to cap of 4`() = runTest {
            coEvery { controller.getVariables(frame, 4) } returns listOf(
                VariableInfo("a", "int", "1")
            )

            tool.execute(buildJsonObject {
                put("max_depth", 10)
            }, project)

            coVerify { controller.getVariables(frame, 4) }
        }

        @Test
        fun `truncates output at 3000 chars`() = runTest {
            // Create many variables to exceed the cap
            val vars = (1..100).map {
                VariableInfo("variable_$it", "String", "\"${"x".repeat(50)}\"")
            }
            coEvery { controller.getVariables(frame, any()) } returns vars

            val result = tool.execute(buildJsonObject { }, project)
            assertFalse(result.isError)
            assertTrue(result.content.length <= GetVariablesTool.MAX_OUTPUT_CHARS + 100) // Allow for truncation message
            assertTrue(result.content.contains("use variable_name to inspect specific variable"))
        }
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("get_variables", def.function.name)
        assertEquals("object", def.function.parameters.type)
        assertTrue(def.function.parameters.required.isEmpty())
    }
}
