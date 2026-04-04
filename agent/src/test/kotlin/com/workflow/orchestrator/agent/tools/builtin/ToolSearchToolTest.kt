package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.FakeAgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolSearchToolTest {

    private val project = mockk<Project>(relaxed = true)
    private lateinit var registry: ToolRegistry
    private lateinit var tool: ToolSearchTool

    @BeforeEach
    fun setUp() {
        registry = ToolRegistry()
        // Register some core tools
        registry.registerCore(FakeAgentTool("read_file", description = "Read file contents"))
        registry.registerCore(FakeAgentTool("edit_file", description = "Edit file contents"))

        // Register deferred tools
        registry.registerDeferred(FakeAgentTool("jira", description = "Manage Jira tickets, sprints, worklogs"))
        registry.registerDeferred(FakeAgentTool("sonar", description = "SonarQube code quality analysis"))
        registry.registerDeferred(FakeAgentTool("bamboo_builds", description = "Bamboo CI/CD build management"))
        registry.registerDeferred(FakeAgentTool("debug_breakpoints", description = "Set and manage debug breakpoints"))
        registry.registerDeferred(FakeAgentTool("spring", description = "Spring Boot endpoints, beans, profiles"))

        tool = ToolSearchTool(registry)
    }

    @Test
    fun `tool metadata is correct`() {
        assertEquals("tool_search", tool.name)
        assertTrue(tool.parameters.required.contains("query"))
        assertTrue(tool.parameters.properties.containsKey("max_results"))
    }

    @Nested
    inner class KeywordSearchTests {

        @Test
        fun `keyword search returns matching tools`() = runTest {
            val params = buildJsonObject { put("query", "jira") }
            val result = tool.execute(params, project)

            assertFalse(result.isError)
            assertTrue(result.content.contains("jira"))
            assertTrue(result.content.contains("Loaded 1 tool"))
        }

        @Test
        fun `keyword search matches description`() = runTest {
            val params = buildJsonObject { put("query", "quality") }
            val result = tool.execute(params, project)

            assertFalse(result.isError)
            assertTrue(result.content.contains("sonar"))
        }

        @Test
        fun `keyword search with multiple terms`() = runTest {
            val params = buildJsonObject { put("query", "debug breakpoints") }
            val result = tool.execute(params, project)

            assertFalse(result.isError)
            assertTrue(result.content.contains("debug_breakpoints"))
        }

        @Test
        fun `keyword search respects max_results`() = runTest {
            // Add more deferred tools that match "tool"
            registry.registerDeferred(FakeAgentTool("build_tool", description = "Build tool management"))
            registry.registerDeferred(FakeAgentTool("coverage_tool", description = "Coverage tool reporting"))

            val params = buildJsonObject {
                put("query", "tool")
                put("max_results", 2)
            }
            val result = tool.execute(params, project)

            assertFalse(result.isError)
            assertTrue(result.content.contains("Loaded 2 tool"))
        }
    }

    @Nested
    inner class SelectPrefixTests {

        @Test
        fun `select prefix loads specific tools by name`() = runTest {
            val params = buildJsonObject { put("query", "select:jira,sonar") }
            val result = tool.execute(params, project)

            assertFalse(result.isError)
            assertTrue(result.content.contains("Loaded 2 tool"))
            assertTrue(result.content.contains("jira"))
            assertTrue(result.content.contains("sonar"))
        }

        @Test
        fun `select prefix with single tool`() = runTest {
            val params = buildJsonObject { put("query", "select:spring") }
            val result = tool.execute(params, project)

            assertFalse(result.isError)
            assertTrue(result.content.contains("spring"))
        }

        @Test
        fun `select prefix with nonexistent tool returns no results`() = runTest {
            val params = buildJsonObject { put("query", "select:nonexistent_tool") }
            val result = tool.execute(params, project)

            assertFalse(result.isError)
            assertTrue(result.content.contains("No tools found"))
        }
    }

    @Nested
    inner class NoMatchTests {

        @Test
        fun `no matches returns catalog listing`() = runTest {
            val params = buildJsonObject { put("query", "nonexistent_capability_xyz") }
            val result = tool.execute(params, project)

            assertFalse(result.isError)
            assertTrue(result.content.contains("No tools found"))
            assertTrue(result.content.contains("Available deferred tools"))
        }
    }

    @Nested
    inner class ActivationTests {

        @Test
        fun `loaded tools become available in active tools`() = runTest {
            // Before: jira not in active tools
            assertFalse(registry.getActiveTools().containsKey("jira"))

            val params = buildJsonObject { put("query", "jira") }
            tool.execute(params, project)

            // After: jira should be in active tools
            assertTrue(registry.getActiveTools().containsKey("jira"))
        }

        @Test
        fun `select prefix activates tools`() = runTest {
            assertFalse(registry.getActiveTools().containsKey("sonar"))

            val params = buildJsonObject { put("query", "select:sonar") }
            tool.execute(params, project)

            assertTrue(registry.getActiveTools().containsKey("sonar"))
        }

        @Test
        fun `activated tools appear in getActiveDefinitions`() = runTest {
            val defsBefore = registry.getActiveDefinitions()
            val namesBefore = defsBefore.map { it.function.name }
            assertFalse(namesBefore.contains("jira"))

            val params = buildJsonObject { put("query", "select:jira") }
            tool.execute(params, project)

            val defsAfter = registry.getActiveDefinitions()
            val namesAfter = defsAfter.map { it.function.name }
            assertTrue(namesAfter.contains("jira"))
        }
    }

    @Nested
    inner class ErrorHandlingTests {

        @Test
        fun `returns error when query parameter is missing`() = runTest {
            val result = tool.execute(buildJsonObject { }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("missing"))
        }
    }
}
