package com.workflow.orchestrator.agent.tools.config

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CreateRunConfigToolTest {
    private val tool = CreateRunConfigTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("create_run_config", tool.name)
        assertTrue(tool.description.contains("run/debug configuration"))
        assertTrue(tool.description.contains("[Agent]"))
    }

    @Test
    fun `required parameters are name and type`() {
        assertEquals(listOf("name", "type"), tool.parameters.required)
    }

    @Test
    fun `has all twelve parameters`() {
        val props = tool.parameters.properties
        assertEquals(12, props.size)
        assertTrue(props.containsKey("name"))
        assertTrue(props.containsKey("type"))
        assertTrue(props.containsKey("main_class"))
        assertTrue(props.containsKey("test_class"))
        assertTrue(props.containsKey("test_method"))
        assertTrue(props.containsKey("module"))
        assertTrue(props.containsKey("env_vars"))
        assertTrue(props.containsKey("vm_options"))
        assertTrue(props.containsKey("program_args"))
        assertTrue(props.containsKey("working_dir"))
        assertTrue(props.containsKey("active_profiles"))
        assertTrue(props.containsKey("port"))
    }

    @Test
    fun `type parameter has correct enum values`() {
        val enumValues = tool.parameters.properties["type"]?.enumValues
        assertNotNull(enumValues)
        assertEquals(
            listOf("application", "spring_boot", "junit", "gradle", "remote_debug"),
            enumValues
        )
    }

    @Test
    fun `name parameter is string type`() {
        assertEquals("string", tool.parameters.properties["name"]?.type)
    }

    @Test
    fun `port parameter is integer type`() {
        assertEquals("integer", tool.parameters.properties["port"]?.type)
    }

    @Test
    fun `env_vars parameter is object type`() {
        assertEquals("object", tool.parameters.properties["env_vars"]?.type)
    }

    @Test
    fun `allowedWorkers includes CODER only`() {
        assertEquals(setOf(WorkerType.CODER), tool.allowedWorkers)
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("create_run_config", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
        assertEquals(12, def.function.parameters.properties.size)
        assertEquals(listOf("name", "type"), def.function.parameters.required)
    }

    @Test
    fun `execute returns error when name is missing`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("type", "application")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing required parameter: name"))
    }

    @Test
    fun `execute returns error when type is missing`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("name", "MyApp")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing required parameter: type"))
    }

    @Test
    fun `execute returns error for invalid type`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("name", "MyApp")
            put("type", "invalid_type")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Invalid type 'invalid_type'"))
    }

    @Test
    fun `execute returns error when main_class missing for application type`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("name", "MyApp")
            put("type", "application")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("main_class"))
        assertTrue(result.content.contains("required"))
    }

    @Test
    fun `execute returns error when main_class missing for spring_boot type`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("name", "MyApp")
            put("type", "spring_boot")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("main_class"))
        assertTrue(result.content.contains("required"))
    }

    @Test
    fun `execute returns error when test_class missing for junit type`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("name", "MyTest")
            put("type", "junit")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("test_class"))
        assertTrue(result.content.contains("required"))
    }

    @Test
    fun `execute handles missing RunManager gracefully`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("name", "MyApp")
            put("type", "application")
            put("main_class", "com.example.Main")
        }

        val result = tool.execute(params, project)

        // Without a running IDE, RunManager.getInstance() will throw
        assertTrue(result.isError)
        assertTrue(result.content.contains("Error"))
    }

    @Test
    fun `AGENT_PREFIX constant is correct`() {
        assertEquals("[Agent] ", CreateRunConfigTool.AGENT_PREFIX)
    }

    @Test
    fun `description mentions IntelliJ`() {
        assertTrue(tool.description.contains("IntelliJ"))
    }

    @Test
    fun `remote_debug does not require main_class or test_class`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("name", "RemoteDebug")
            put("type", "remote_debug")
        }

        val result = tool.execute(params, project)

        // Should NOT fail with missing main_class/test_class — it will fail at RunManager level
        assertFalse(result.content.contains("main_class"))
        assertFalse(result.content.contains("test_class"))
    }

    @Test
    fun `gradle does not require main_class or test_class`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("name", "GradleBuild")
            put("type", "gradle")
        }

        val result = tool.execute(params, project)

        // Should NOT fail with missing main_class/test_class
        assertFalse(result.content.contains("main_class"))
        assertFalse(result.content.contains("test_class"))
    }
}
