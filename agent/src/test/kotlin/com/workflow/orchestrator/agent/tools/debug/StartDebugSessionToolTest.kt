package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class StartDebugSessionToolTest {
    private val controller = mockk<AgentDebugController>(relaxed = true)
    private val project = mockk<Project>(relaxed = true)

    // --- StartDebugSessionTool ---

    private val startTool = StartDebugSessionTool(controller)

    @Test
    fun `start tool metadata is correct`() {
        assertEquals("start_debug_session", startTool.name)
        assertTrue(startTool.description.contains("debug mode"))
        assertTrue(startTool.description.contains("session ID"))
    }

    @Test
    fun `start tool requires config_name`() {
        assertEquals(listOf("config_name"), startTool.parameters.required)
    }

    @Test
    fun `start tool has config_name and wait_for_pause parameters`() {
        val props = startTool.parameters.properties
        assertEquals(2, props.size)
        assertTrue(props.containsKey("config_name"))
        assertTrue(props.containsKey("wait_for_pause"))
        assertEquals("string", props["config_name"]?.type)
        assertEquals("integer", props["wait_for_pause"]?.type)
    }

    @Test
    fun `start tool allowed workers`() {
        assertEquals(setOf(WorkerType.CODER), startTool.allowedWorkers)
    }

    @Test
    fun `start tool returns error when config_name is missing`() = runTest {
        val params = buildJsonObject { }
        val result = startTool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing required parameter: config_name"))
    }

    @Test
    fun `start tool returns error when run configuration not found`() = runTest {
        // RunManager.getInstance will throw in test env without IDE
        val params = buildJsonObject {
            put("config_name", "NonExistentConfig")
        }
        val result = startTool.execute(params, project)
        assertTrue(result.isError)
    }

    @Test
    fun `start tool toToolDefinition produces valid schema`() {
        val def = startTool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("start_debug_session", def.function.name)
        assertEquals("object", def.function.parameters.type)
        assertEquals(listOf("config_name"), def.function.parameters.required)
    }

    // --- DebugStepOverTool ---

    @Nested
    inner class StepOverToolTest {
        private val tool = DebugStepOverTool(controller)

        @Test
        fun `metadata is correct`() {
            assertEquals("debug_step_over", tool.name)
            assertTrue(tool.description.contains("Step over"))
        }

        @Test
        fun `session_id is optional`() {
            assertTrue(tool.parameters.required.isEmpty())
            assertTrue(tool.parameters.properties.containsKey("session_id"))
        }

        @Test
        fun `allowed workers include CODER only`() {
            assertEquals(setOf(WorkerType.CODER), tool.allowedWorkers)
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
        fun `returns error when session specified but not found`() = runTest {
            every { controller.getSession("bad-id") } returns null

            val result = tool.execute(buildJsonObject {
                put("session_id", "bad-id")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("No debug session found"))
            assertTrue(result.content.contains("bad-id"))
        }
    }

    // --- DebugStepIntoTool ---

    @Nested
    inner class StepIntoToolTest {
        private val tool = DebugStepIntoTool(controller)

        @Test
        fun `metadata is correct`() {
            assertEquals("debug_step_into", tool.name)
            assertTrue(tool.description.contains("Step into"))
        }

        @Test
        fun `session_id is optional`() {
            assertTrue(tool.parameters.required.isEmpty())
        }

        @Test
        fun `returns error when no session found`() = runTest {
            every { controller.getActiveSessionId() } returns null
            every { controller.getSession(null) } returns null

            val result = tool.execute(buildJsonObject { }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("No debug session found"))
        }
    }

    // --- DebugStepOutTool ---

    @Nested
    inner class StepOutToolTest {
        private val tool = DebugStepOutTool(controller)

        @Test
        fun `metadata is correct`() {
            assertEquals("debug_step_out", tool.name)
            assertTrue(tool.description.contains("Step out"))
        }

        @Test
        fun `session_id is optional`() {
            assertTrue(tool.parameters.required.isEmpty())
        }

        @Test
        fun `returns error when no session found`() = runTest {
            every { controller.getActiveSessionId() } returns null
            every { controller.getSession(null) } returns null

            val result = tool.execute(buildJsonObject { }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("No debug session found"))
        }
    }

    // --- DebugResumeTool ---

    @Nested
    inner class ResumeToolTest {
        private val tool = DebugResumeTool(controller)

        @Test
        fun `metadata is correct`() {
            assertEquals("debug_resume", tool.name)
            assertTrue(tool.description.contains("Resume"))
        }

        @Test
        fun `session_id is optional`() {
            assertTrue(tool.parameters.required.isEmpty())
        }

        @Test
        fun `allowed workers include CODER only`() {
            assertEquals(setOf(WorkerType.CODER), tool.allowedWorkers)
        }

        @Test
        fun `returns error when no session found`() = runTest {
            every { controller.getActiveSessionId() } returns null
            every { controller.getSession(null) } returns null

            val result = tool.execute(buildJsonObject { }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("No debug session found"))
        }
    }

    // --- DebugPauseTool ---

    @Nested
    inner class PauseToolTest {
        private val tool = DebugPauseTool(controller)

        @Test
        fun `metadata is correct`() {
            assertEquals("debug_pause", tool.name)
            assertTrue(tool.description.contains("Pause"))
        }

        @Test
        fun `session_id is optional`() {
            assertTrue(tool.parameters.required.isEmpty())
        }

        @Test
        fun `returns error when no session found`() = runTest {
            every { controller.getActiveSessionId() } returns null
            every { controller.getSession(null) } returns null

            val result = tool.execute(buildJsonObject { }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("No debug session found"))
        }
    }

    // --- DebugRunToCursorTool ---

    @Nested
    inner class RunToCursorToolTest {
        private val tool = DebugRunToCursorTool(controller)

        @Test
        fun `metadata is correct`() {
            assertEquals("debug_run_to_cursor", tool.name)
            assertTrue(tool.description.contains("Run to"))
        }

        @Test
        fun `required parameters are file and line`() {
            assertEquals(listOf("file", "line"), tool.parameters.required)
        }

        @Test
        fun `has file, line, and session_id parameters`() {
            val props = tool.parameters.properties
            assertEquals(3, props.size)
            assertTrue(props.containsKey("file"))
            assertTrue(props.containsKey("line"))
            assertTrue(props.containsKey("session_id"))
        }

        @Test
        fun `returns error when file is missing`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("line", 10)
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Missing required parameter: file"))
        }

        @Test
        fun `returns error when line is missing`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("file", "Main.kt")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Missing or invalid required parameter: line"))
        }

        @Test
        fun `returns error when line is less than 1`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("file", "Main.kt")
                put("line", 0)
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Line number must be >= 1"))
        }

        @Test
        fun `returns error when no session found`() = runTest {
            every { controller.getActiveSessionId() } returns null
            every { controller.getSession(null) } returns null

            val result = tool.execute(buildJsonObject {
                put("file", "Main.kt")
                put("line", 10)
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("No debug session found"))
        }
    }

    // --- DebugStopTool ---

    @Nested
    inner class StopToolTest {
        private val tool = DebugStopTool(controller)

        @Test
        fun `metadata is correct`() {
            assertEquals("debug_stop", tool.name)
            assertTrue(tool.description.contains("Stop"))
        }

        @Test
        fun `session_id is optional`() {
            assertTrue(tool.parameters.required.isEmpty())
        }

        @Test
        fun `allowed workers include CODER only`() {
            assertEquals(setOf(WorkerType.CODER), tool.allowedWorkers)
        }

        @Test
        fun `returns error when no session found`() = runTest {
            every { controller.getActiveSessionId() } returns null
            every { controller.getSession(null) } returns null

            val result = tool.execute(buildJsonObject { }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("No debug session found"))
        }
    }

    // --- formatVariables ---

    @Nested
    inner class FormatVariablesTest {
        @Test
        fun `formats single variable`() {
            val vars = listOf(VariableInfo("x", "int", "42"))
            val result = formatVariables(vars)
            assertEquals("  x: int = 42", result)
        }

        @Test
        fun `formats nested variables`() {
            val vars = listOf(
                VariableInfo("obj", "MyClass", "{...}", listOf(
                    VariableInfo("field", "String", "hello")
                ))
            )
            val result = formatVariables(vars)
            assertTrue(result.contains("  obj: MyClass = {...}"))
            assertTrue(result.contains("    field: String = hello"))
        }

        @Test
        fun `formats empty list`() {
            val result = formatVariables(emptyList())
            assertEquals("", result)
        }

        @Test
        fun `formats multiple variables`() {
            val vars = listOf(
                VariableInfo("a", "int", "1"),
                VariableInfo("b", "String", "test")
            )
            val result = formatVariables(vars)
            assertTrue(result.contains("  a: int = 1"))
            assertTrue(result.contains("  b: String = test"))
        }
    }

    // --- All tools schema validation ---

    @Test
    fun `all 8 tools produce valid tool definitions`() {
        val tools = listOf(
            startTool,
            DebugStepOverTool(controller),
            DebugStepIntoTool(controller),
            DebugStepOutTool(controller),
            DebugResumeTool(controller),
            DebugPauseTool(controller),
            DebugRunToCursorTool(controller),
            DebugStopTool(controller)
        )

        val names = tools.map { it.name }
        assertEquals(8, names.size)
        assertEquals(8, names.toSet().size) // All unique

        tools.forEach { tool ->
            val def = tool.toToolDefinition()
            assertEquals("function", def.type)
            assertTrue(def.function.name.isNotBlank())
            assertTrue(def.function.description.isNotBlank())
            assertEquals("object", def.function.parameters.type)
        }
    }

    @Test
    fun `all 8 tools have correct allowed workers`() {
        val expected = setOf(WorkerType.CODER)
        val tools = listOf(
            startTool,
            DebugStepOverTool(controller),
            DebugStepIntoTool(controller),
            DebugStepOutTool(controller),
            DebugResumeTool(controller),
            DebugPauseTool(controller),
            DebugRunToCursorTool(controller),
            DebugStopTool(controller)
        )
        tools.forEach { tool ->
            assertEquals(expected, tool.allowedWorkers, "Wrong allowedWorkers for ${tool.name}")
        }
    }
}
