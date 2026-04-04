package com.workflow.orchestrator.agent.tools.vcs

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChangelistShelveToolTest {
    private val tool = ChangelistShelveTool()

    @Test
    fun `tool name is changelist_shelve`() {
        assertEquals("changelist_shelve", tool.name)
    }

    @Test
    fun `description mentions shelve and unshelve`() {
        assertTrue(tool.description.contains("shelve"))
        assertTrue(tool.description.contains("unshelve"))
        assertTrue(tool.description.contains("changelists"))
    }

    @Test
    fun `action is the only required parameter`() {
        assertEquals(listOf("action"), tool.parameters.required)
    }

    @Test
    fun `has four parameters`() {
        val props = tool.parameters.properties
        assertEquals(4, props.size)
        assertTrue(props.containsKey("action"))
        assertTrue(props.containsKey("name"))
        assertTrue(props.containsKey("comment"))
        assertTrue(props.containsKey("shelf_index"))
    }

    @Test
    fun `action parameter has correct enum values`() {
        val enumValues = tool.parameters.properties["action"]?.enumValues
        assertNotNull(enumValues)
        assertEquals(
            listOf("list", "list_shelves", "create", "shelve", "unshelve"),
            enumValues
        )
    }

    @Test
    fun `action parameter is string type`() {
        assertEquals("string", tool.parameters.properties["action"]?.type)
    }

    @Test
    fun `name parameter is string type`() {
        assertEquals("string", tool.parameters.properties["name"]?.type)
    }

    @Test
    fun `comment parameter is string type`() {
        assertEquals("string", tool.parameters.properties["comment"]?.type)
    }

    @Test
    fun `shelf_index parameter is integer type`() {
        assertEquals("integer", tool.parameters.properties["shelf_index"]?.type)
    }

    @Test
    fun `allowedWorkers includes CODER only`() {
        assertEquals(setOf(WorkerType.CODER), tool.allowedWorkers)
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("changelist_shelve", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
        assertEquals(4, def.function.parameters.properties.size)
        assertEquals(listOf("action"), def.function.parameters.required)
    }

    @Test
    fun `execute returns error when action is missing`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject { }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing required parameter: action"))
    }

    @Test
    fun `execute returns error for invalid action`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("action", "invalid_action")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Invalid action 'invalid_action'"))
    }

    @Test
    fun `create action returns error when name is missing`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("action", "create")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing required parameter: name"))
    }

    @Test
    fun `unshelve action returns error when shelf_index is missing`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("action", "unshelve")
        }

        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Missing required parameter: shelf_index"))
        assertTrue(result.content.contains("list_shelves"))
    }

    @Test
    fun `list action fails gracefully without IDE`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("action", "list")
        }

        val result = tool.execute(params, project)

        // Without a running IDE, ChangeListManager.getInstance() will throw
        assertTrue(result.isError)
        assertTrue(result.content.contains("Error"))
    }

    @Test
    fun `list_shelves action fails gracefully without IDE`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("action", "list_shelves")
        }

        val result = tool.execute(params, project)

        // Without a running IDE, ShelveChangesManager.getInstance() will throw
        assertTrue(result.isError)
        assertTrue(result.content.contains("Error"))
    }

    @Test
    fun `shelve action fails gracefully without IDE`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val params = buildJsonObject {
            put("action", "shelve")
            put("comment", "WIP changes")
        }

        val result = tool.execute(params, project)

        // Without a running IDE, ChangeListManager.getInstance() will throw
        assertTrue(result.isError)
        assertTrue(result.content.contains("Error"))
    }

    @Test
    fun `description mentions all five actions`() {
        assertTrue(tool.description.contains("list"))
        assertTrue(tool.description.contains("list_shelves"))
        assertTrue(tool.description.contains("create"))
        assertTrue(tool.description.contains("shelve"))
        assertTrue(tool.description.contains("unshelve"))
    }

    @Test
    fun `name parameter description mentions changelist`() {
        val desc = tool.parameters.properties["name"]?.description ?: ""
        assertTrue(desc.contains("changelist", ignoreCase = true))
    }

    @Test
    fun `shelf_index description mentions index`() {
        val desc = tool.parameters.properties["shelf_index"]?.description ?: ""
        assertTrue(desc.contains("index", ignoreCase = true))
    }
}
