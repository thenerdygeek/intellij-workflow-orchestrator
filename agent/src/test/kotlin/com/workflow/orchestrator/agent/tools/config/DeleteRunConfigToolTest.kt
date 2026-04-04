package com.workflow.orchestrator.agent.tools.config

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DeleteRunConfigToolTest {
    private val tool = DeleteRunConfigTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("delete_run_config", tool.name)
        assertTrue(tool.description.contains("Delete"))
        assertTrue(tool.description.contains("[Agent]"))
    }

    @Test
    fun `required parameters are name and description`() {
        assertEquals(listOf("name", "description"), tool.parameters.required)
    }

    @Test
    fun `has name and description parameters`() {
        val props = tool.parameters.properties
        assertEquals(2, props.size)
        assertTrue(props.containsKey("name"))
        assertTrue(props.containsKey("description"))
        assertEquals("string", props["name"]?.type)
        assertEquals("string", props["description"]?.type)
    }

    @Test
    fun `allowedWorkers includes CODER only`() {
        assertEquals(setOf(WorkerType.CODER), tool.allowedWorkers)
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("delete_run_config", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
        assertEquals(2, def.function.parameters.properties.size)
        assertEquals(listOf("name", "description"), def.function.parameters.required)
    }

    @Test
    fun `execute returns error when name is missing`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {}

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing required parameter: name"))
    }

    @Test
    fun `execute rejects deletion of non-agent config`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("name", "MyUserConfig")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Cannot delete"))
        assertTrue(result.content.contains("safety constraint"))
    }

    @Test
    fun `execute rejects config without Agent prefix`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("name", "Spring Boot App")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("[Agent]"))
    }

    @Test
    fun `execute allows deletion of agent-prefixed config`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("name", "[Agent] MyConfig")
        }

        val result = tool.execute(params, project)

        // Without a running IDE, RunManager.getInstance() will throw — but it should
        // NOT fail with the safety check error
        assertTrue(result.isError) // Error from missing RunManager, not safety
        assertFalse(result.content.contains("Cannot delete"))
        assertTrue(result.content.contains("Error"))
    }

    @Test
    fun `execute handles missing RunManager gracefully`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("name", "[Agent] TestConfig")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Error"))
    }

    @Test
    fun `description mentions agent-created only`() {
        assertTrue(tool.description.contains("agent-created"))
    }

    @Test
    fun `description mentions only constraint`() {
        assertTrue(tool.description.lowercase().contains("only"))
    }
}
