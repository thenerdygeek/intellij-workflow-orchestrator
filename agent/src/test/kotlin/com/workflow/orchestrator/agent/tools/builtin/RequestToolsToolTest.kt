package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.settings.ToolPreferences
import com.workflow.orchestrator.agent.tools.ToolCategoryRegistry
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue

class RequestToolsToolTest {

    private val project = mockk<Project>(relaxed = true)
    private val tool = RequestToolsTool()

    @BeforeEach
    fun setup() {
        mockkObject(ToolCategoryRegistry)
        clearAllMocks()
    }

    @Test
    fun `tool metadata is correct`() {
        assertEquals("request_tools", tool.name)
        assertTrue(tool.parameters.required.contains("category"))
        assertTrue(tool.description.contains("Request additional tool categories"))
    }

    @Test
    fun `returns error when category is missing`() = runTest {
        val params = buildJsonObject { put("reason", "testing") }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("'category' required"))
    }

    @Test
    fun `returns error for unknown category`() = runTest {
        val params = buildJsonObject { put("category", "nonexistent") }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Unknown category"))
        assertTrue(result.content.contains("Available categories"))
    }

    @Test
    fun `returns error when all tools in category are disabled`() = runTest {
        val prefs = mockk<ToolPreferences>()
        every { prefs.isToolEnabled(any()) } returns false
        mockkStatic(ToolPreferences::class)
        every { ToolPreferences.getInstance(project) } returns prefs

        val agentService = mockk<AgentService>(relaxed = true)
        mockkStatic(AgentService::class)
        every { AgentService.getInstance(project) } returns agentService

        val params = buildJsonObject { put("category", "jira") }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("disabled by user preferences"))

        unmockkStatic(ToolPreferences::class, AgentService::class)
    }

    @Test
    fun `successfully activates tools from valid category`() = runTest {
        val prefs = mockk<ToolPreferences>()
        every { prefs.isToolEnabled(any()) } returns true
        mockkStatic(ToolPreferences::class)
        every { ToolPreferences.getInstance(project) } returns prefs

        val queue = ConcurrentLinkedQueue<String>()
        val agentService = mockk<AgentService>(relaxed = true)
        every { agentService.pendingToolActivations } returns queue
        mockkStatic(AgentService::class)
        every { AgentService.getInstance(project) } returns agentService

        val params = buildJsonObject {
            put("category", "jira")
            put("reason", "need ticket details")
        }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("Activated"))
        assertTrue(result.content.contains("Reason: need ticket details"))
        // Tools should be queued
        assertTrue(queue.isNotEmpty())
        assertTrue(queue.contains("jira"))

        unmockkStatic(ToolPreferences::class, AgentService::class)
    }

    @Test
    fun `filters disabled tools from activation`() = runTest {
        val prefs = mockk<ToolPreferences>()
        // Default: enable all tools, then selectively disable the runtime_debug category tools
        every { prefs.isToolEnabled(any()) } returns true
        every { prefs.isToolEnabled("debug") } returns false
        mockkStatic(ToolPreferences::class)
        every { ToolPreferences.getInstance(project) } returns prefs

        val queue = ConcurrentLinkedQueue<String>()
        val agentService = mockk<AgentService>(relaxed = true)
        every { agentService.pendingToolActivations } returns queue
        mockkStatic(AgentService::class)
        every { AgentService.getInstance(project) } returns agentService

        val params = buildJsonObject { put("category", "runtime_debug") }
        val result = tool.execute(params, project)

        assertFalse(result.isError)
        assertTrue(queue.contains("runtime"))
        assertFalse(queue.contains("debug"))

        unmockkStatic(ToolPreferences::class, AgentService::class)
    }

    @Test
    fun `returns error when agent service is unavailable`() = runTest {
        val prefs = mockk<ToolPreferences>()
        every { prefs.isToolEnabled(any()) } returns true
        mockkStatic(ToolPreferences::class)
        every { ToolPreferences.getInstance(project) } returns prefs

        mockkStatic(AgentService::class)
        every { AgentService.getInstance(project) } throws IllegalStateException("not available")

        val params = buildJsonObject { put("category", "jira") }
        val result = tool.execute(params, project)

        assertTrue(result.isError)
        assertTrue(result.content.contains("agent service not available"))

        unmockkStatic(ToolPreferences::class, AgentService::class)
    }

    @Test
    fun `description lists all activatable categories`() {
        val desc = tool.description
        for (cat in ToolCategoryRegistry.getActivatableCategories()) {
            assertTrue(desc.contains(cat.id), "Description missing category: ${cat.id}")
        }
        // Core is always-active, should NOT appear in activatable list
        assertFalse(desc.contains("'core':"))
    }
}
