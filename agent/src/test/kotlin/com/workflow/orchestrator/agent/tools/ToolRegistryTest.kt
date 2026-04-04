package com.workflow.orchestrator.agent.tools

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolRegistryTest {

    private lateinit var registry: ToolRegistry

    @BeforeEach
    fun setUp() {
        registry = ToolRegistry()
    }

    // ── Legacy API backward compatibility ─────────────────────────────

    @Test
    fun `register and retrieve tool by name`() {
        val tool = FakeAgentTool(name = "read_file", allowedWorkers = setOf(WorkerType.CODER))
        registry.register(tool)
        assertEquals(tool, registry.getTool("read_file"))
    }

    @Test
    fun `getTool returns null for unregistered name`() {
        assertNull(registry.getTool("nonexistent"))
    }

    @Test
    fun `getToolsForWorker filters by worker type`() {
        registry.register(FakeAgentTool("read_file", setOf(WorkerType.CODER, WorkerType.REVIEWER)))
        registry.register(FakeAgentTool("edit_file", setOf(WorkerType.CODER)))
        registry.register(FakeAgentTool("jira_get", setOf(WorkerType.TOOLER)))

        val coderTools = registry.getToolsForWorker(WorkerType.CODER)
        assertEquals(2, coderTools.size)
        assertTrue(coderTools.any { it.name == "read_file" })
        assertTrue(coderTools.any { it.name == "edit_file" })

        val toolerTools = registry.getToolsForWorker(WorkerType.TOOLER)
        assertEquals(1, toolerTools.size)
        assertEquals("jira_get", toolerTools.first().name)
    }

    @Test
    fun `getToolsForWorker returns empty list when no tools match`() {
        registry.register(FakeAgentTool("edit_file", setOf(WorkerType.CODER)))

        val analyzerTools = registry.getToolsForWorker(WorkerType.ANALYZER)
        assertTrue(analyzerTools.isEmpty())
    }

    @Test
    fun `getToolDefinitionsForWorker returns OpenAI-compatible schemas`() {
        registry.register(FakeAgentTool("read_file", setOf(WorkerType.CODER)))

        val defs = registry.getToolDefinitionsForWorker(WorkerType.CODER)
        assertEquals(1, defs.size)
        assertEquals("function", defs.first().type)
        assertEquals("read_file", defs.first().function.name)
    }

    @Test
    fun `allTools returns all registered tools`() {
        registry.register(FakeAgentTool("tool_a", setOf(WorkerType.CODER)))
        registry.register(FakeAgentTool("tool_b", setOf(WorkerType.ANALYZER)))
        registry.register(FakeAgentTool("tool_c", setOf(WorkerType.TOOLER)))

        assertEquals(3, registry.allTools().size)
    }

    @Test
    fun `register replaces existing tool with same name`() {
        val tool1 = FakeAgentTool("read_file", setOf(WorkerType.CODER), description = "v1")
        val tool2 = FakeAgentTool("read_file", setOf(WorkerType.CODER), description = "v2")
        registry.register(tool1)
        registry.register(tool2)

        assertEquals("v2", registry.getTool("read_file")?.description)
        assertEquals(1, registry.allTools().size)
    }

    // ── Three-tier registry ───────────────────────────────────────────

    @Nested
    inner class CoreToolTests {

        @Test
        fun `core tools returned by getActiveTools`() {
            registry.registerCore(FakeAgentTool("read_file"))
            registry.registerCore(FakeAgentTool("edit_file"))

            val active = registry.getActiveTools()
            assertEquals(2, active.size)
            assertTrue(active.containsKey("read_file"))
            assertTrue(active.containsKey("edit_file"))
        }

        @Test
        fun `core tools included in getActiveDefinitions`() {
            registry.registerCore(FakeAgentTool("read_file"))
            val defs = registry.getActiveDefinitions()
            assertEquals(1, defs.size)
            assertEquals("read_file", defs.first().function.name)
        }
    }

    @Nested
    inner class DeferredToolTests {

        @Test
        fun `deferred tools NOT returned by getActiveTools`() {
            registry.registerCore(FakeAgentTool("read_file"))
            registry.registerDeferred(FakeAgentTool("jira"))
            registry.registerDeferred(FakeAgentTool("sonar"))

            val active = registry.getActiveTools()
            assertEquals(1, active.size)
            assertTrue(active.containsKey("read_file"))
            assertFalse(active.containsKey("jira"))
            assertFalse(active.containsKey("sonar"))
        }

        @Test
        fun `deferred tools accessible via get() for execution`() {
            registry.registerDeferred(FakeAgentTool("jira"))
            assertNotNull(registry.get("jira"))
        }

        @Test
        fun `deferred tools included in allTools()`() {
            registry.registerCore(FakeAgentTool("read_file"))
            registry.registerDeferred(FakeAgentTool("jira"))
            assertEquals(2, registry.allTools().size)
        }
    }

    @Nested
    inner class SearchDeferredTests {

        @Test
        fun `searchDeferred finds by keyword in name`() {
            registry.registerDeferred(FakeAgentTool("jira", description = "Manage tickets"))
            registry.registerDeferred(FakeAgentTool("sonar", description = "Code quality"))
            registry.registerDeferred(FakeAgentTool("bamboo_builds", description = "CI builds"))

            val results = registry.searchDeferred("jira")
            assertEquals(1, results.size)
            assertEquals("jira", results.first().name)
        }

        @Test
        fun `searchDeferred finds by keyword in description`() {
            registry.registerDeferred(FakeAgentTool("jira", description = "Manage tickets"))
            registry.registerDeferred(FakeAgentTool("sonar", description = "Code quality analysis"))
            registry.registerDeferred(FakeAgentTool("bamboo_builds", description = "CI builds"))

            val results = registry.searchDeferred("quality")
            assertEquals(1, results.size)
            assertEquals("sonar", results.first().name)
        }

        @Test
        fun `searchDeferred returns empty for no matches`() {
            registry.registerDeferred(FakeAgentTool("jira", description = "Manage tickets"))
            val results = registry.searchDeferred("nonexistent_term_xyz")
            assertTrue(results.isEmpty())
        }

        @Test
        fun `searchDeferred respects maxResults`() {
            registry.registerDeferred(FakeAgentTool("git_blame", description = "Git blame info"))
            registry.registerDeferred(FakeAgentTool("git_branches", description = "Git branch list"))
            registry.registerDeferred(FakeAgentTool("git_stash_list", description = "Git stash entries"))

            val results = registry.searchDeferred("git", maxResults = 2)
            assertEquals(2, results.size)
        }

        @Test
        fun `searchDeferred ranks by match count`() {
            registry.registerDeferred(FakeAgentTool("debug_step", description = "Step through debug session"))
            registry.registerDeferred(FakeAgentTool("debug_breakpoints", description = "Manage debug breakpoints"))
            registry.registerDeferred(FakeAgentTool("read_file", description = "Read file contents"))

            // "debug breakpoints" has 2 matching terms for debug_breakpoints
            val results = registry.searchDeferred("debug breakpoints")
            assertTrue(results.isNotEmpty())
            assertEquals("debug_breakpoints", results.first().name)
        }

        @Test
        fun `searchDeferred is case insensitive`() {
            registry.registerDeferred(FakeAgentTool("JiraTool", description = "JIRA TICKETS"))
            val results = registry.searchDeferred("jira")
            assertEquals(1, results.size)
        }
    }

    @Nested
    inner class ActivateDeferredTests {

        @Test
        fun `activateDeferred moves tool to active`() {
            registry.registerDeferred(FakeAgentTool("jira"))

            assertFalse(registry.getActiveTools().containsKey("jira"))

            val activated = registry.activateDeferred("jira")
            assertNotNull(activated)
            assertEquals("jira", activated!!.name)

            assertTrue(registry.getActiveTools().containsKey("jira"))
        }

        @Test
        fun `activateDeferred removes from deferred pool`() {
            registry.registerDeferred(FakeAgentTool("jira"))
            registry.registerDeferred(FakeAgentTool("sonar"))

            registry.activateDeferred("jira")

            // jira should no longer appear in deferred catalog
            val catalog = registry.getDeferredCatalog()
            assertFalse(catalog.any { it.first == "jira" })
            assertTrue(catalog.any { it.first == "sonar" })
        }

        @Test
        fun `activateDeferred returns null for nonexistent tool`() {
            val result = registry.activateDeferred("nonexistent")
            assertNull(result)
        }

        @Test
        fun `activateDeferred is idempotent for already-active tool`() {
            registry.registerDeferred(FakeAgentTool("jira"))
            registry.activateDeferred("jira")

            // Calling again should return the tool without error
            val result = registry.activateDeferred("jira")
            assertNotNull(result)
            assertEquals("jira", result!!.name)
        }

        @Test
        fun `activateDeferred returns core tool if name matches core`() {
            registry.registerCore(FakeAgentTool("read_file"))

            val result = registry.activateDeferred("read_file")
            assertNotNull(result)
            assertEquals("read_file", result!!.name)
        }
    }

    @Nested
    inner class ResetActiveDeferredTests {

        @Test
        fun `resetActiveDeferred clears active deferred`() {
            registry.registerDeferred(FakeAgentTool("jira"))
            registry.registerDeferred(FakeAgentTool("sonar"))
            registry.activateDeferred("jira")
            registry.activateDeferred("sonar")

            // Both should be active now
            assertEquals(2, registry.activeDeferredCount())

            registry.resetActiveDeferred()

            // After reset, active deferred should be empty
            assertEquals(0, registry.activeDeferredCount())
            // Tools should be back in deferred
            assertEquals(2, registry.deferredCount())
        }

        @Test
        fun `resetActiveDeferred does not affect core tools`() {
            registry.registerCore(FakeAgentTool("read_file"))
            registry.registerDeferred(FakeAgentTool("jira"))
            registry.activateDeferred("jira")

            registry.resetActiveDeferred()

            assertTrue(registry.getActiveTools().containsKey("read_file"))
            assertFalse(registry.getActiveTools().containsKey("jira"))
        }
    }

    @Nested
    inner class DeferredCatalogTests {

        @Test
        fun `getDeferredCatalog returns name and description pairs`() {
            registry.registerDeferred(FakeAgentTool("jira", description = "Manage Jira tickets"))
            registry.registerDeferred(FakeAgentTool("sonar", description = "SonarQube quality analysis"))

            val catalog = registry.getDeferredCatalog()
            assertEquals(2, catalog.size)

            val names = catalog.map { it.first }
            assertTrue(names.contains("jira"))
            assertTrue(names.contains("sonar"))
        }

        @Test
        fun `getDeferredCatalog excludes activated tools`() {
            registry.registerDeferred(FakeAgentTool("jira", description = "Jira tool"))
            registry.registerDeferred(FakeAgentTool("sonar", description = "Sonar tool"))

            registry.activateDeferred("jira")

            val catalog = registry.getDeferredCatalog()
            assertEquals(1, catalog.size)
            assertEquals("sonar", catalog.first().first)
        }

        @Test
        fun `getDeferredCatalog returns empty when no deferred tools`() {
            registry.registerCore(FakeAgentTool("read_file"))
            assertTrue(registry.getDeferredCatalog().isEmpty())
        }

        @Test
        fun `getDeferredCatalog uses first non-blank line of description`() {
            val multilineDesc = """

                Manage Jira tickets and sprints.
                Supports transitions, comments, and worklogs.
            """.trimIndent()
            registry.registerDeferred(FakeAgentTool("jira", description = multilineDesc))

            val catalog = registry.getDeferredCatalog()
            assertEquals("Manage Jira tickets and sprints.", catalog.first().second)
        }
    }

    @Nested
    inner class CountTests {

        @Test
        fun `count returns total across all tiers`() {
            registry.registerCore(FakeAgentTool("read_file"))
            registry.registerCore(FakeAgentTool("edit_file"))
            registry.registerDeferred(FakeAgentTool("jira"))
            registry.registerDeferred(FakeAgentTool("sonar"))
            registry.activateDeferred("jira")

            assertEquals(4, registry.count())
            assertEquals(2, registry.coreCount())
            assertEquals(1, registry.deferredCount()) // sonar only
            assertEquals(1, registry.activeDeferredCount()) // jira
        }
    }
}

class FakeAgentTool(
    override val name: String,
    override val allowedWorkers: Set<WorkerType> = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER),
    override val description: String = "Fake tool for testing",
    override val parameters: FunctionParameters = FunctionParameters(properties = emptyMap())
) : AgentTool {
    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        return ToolResult(content = "fake result", summary = "fake", tokenEstimate = 10)
    }
}
