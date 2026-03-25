package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AddBreakpointToolTest {
    private lateinit var controller: AgentDebugController
    private lateinit var tool: AddBreakpointTool
    private val mockProject = mockk<Project>(relaxed = true)

    @BeforeEach
    fun setUp() {
        controller = AgentDebugController(mockProject)
        tool = AddBreakpointTool(controller)
    }

    @Test
    fun `tool metadata is correct`() {
        assertEquals("add_breakpoint", tool.name)
        assertTrue(tool.description.contains("breakpoint"))
        assertTrue(tool.description.contains("conditional"))
        assertTrue(tool.description.contains("log"))
        assertTrue(tool.description.contains("temporary"))
    }

    @Test
    fun `required parameters are file and line`() {
        assertEquals(listOf("file", "line"), tool.parameters.required)
    }

    @Test
    fun `has all five parameters`() {
        val props = tool.parameters.properties
        assertEquals(5, props.size)
        assertTrue(props.containsKey("file"))
        assertTrue(props.containsKey("line"))
        assertTrue(props.containsKey("condition"))
        assertTrue(props.containsKey("log_expression"))
        assertTrue(props.containsKey("temporary"))
    }

    @Test
    fun `file parameter is string type`() {
        assertEquals("string", tool.parameters.properties["file"]?.type)
    }

    @Test
    fun `line parameter is integer type`() {
        assertEquals("integer", tool.parameters.properties["line"]?.type)
    }

    @Test
    fun `condition parameter is string type`() {
        assertEquals("string", tool.parameters.properties["condition"]?.type)
    }

    @Test
    fun `log_expression parameter is string type`() {
        assertEquals("string", tool.parameters.properties["log_expression"]?.type)
    }

    @Test
    fun `temporary parameter is boolean type`() {
        assertEquals("boolean", tool.parameters.properties["temporary"]?.type)
    }

    @Test
    fun `allowedWorkers includes CODER only`() {
        assertEquals(setOf(WorkerType.CODER), tool.allowedWorkers)
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("add_breakpoint", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
        assertEquals(5, def.function.parameters.properties.size)
        assertEquals(listOf("file", "line"), def.function.parameters.required)
    }

    @Test
    fun `execute returns error when file param is missing`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("line", 10)
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing required parameter: file"))
    }

    @Test
    fun `execute returns error when line param is missing`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("file", "src/Main.kt")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing or invalid required parameter: line"))
    }

    @Test
    fun `execute returns error when line is less than 1`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("file", "src/Main.kt")
            put("line", 0)
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Line number must be >= 1"))
    }

    @Test
    fun `execute returns error when project basePath is null for relative path`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns null

        val params = buildJsonObject {
            put("file", "src/Main.kt")
            put("line", 10)
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("project base path not available"))
    }

    @Test
    fun `execute handles missing XDebuggerManager gracefully`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.basePath } returns "/tmp/test-project"

        val params = buildJsonObject {
            put("file", "/tmp/test-project/src/Main.kt")
            put("line", 10)
        }

        val result = tool.execute(params, project)

        // Without a running IDE, XDebuggerManager.getInstance() will throw
        assertTrue(result.isError)
        assertTrue(result.content.contains("Error"))
    }

    @Test
    fun `execute handles negative line number`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("file", "src/Main.kt")
            put("line", -5)
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Line number must be >= 1"))
    }

    @Test
    fun `description mentions all breakpoint types`() {
        assertTrue(tool.description.contains("conditional"))
        assertTrue(tool.description.contains("log"))
        assertTrue(tool.description.contains("temporary"))
    }
}
