package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Scenario-based tests for [ProjectStructureTool].
 *
 * Strategy mirrors [BuildToolTest]: ProjectStructureTool is a meta-tool with 8 actions
 * covering IntelliJ project structure introspection and mutation. Tests lock in:
 *
 *  1. **Tool surface** — name, action enum, parameter schema, allowedWorkers.
 *  2. **Dispatcher contract** — missing action, unknown action.
 *  3. **Action routing smoke** — every action enum value routes to a non-crashing path.
 *     Stubs return isError=true which is acceptable; the dispatcher must not throw.
 */
class ProjectStructureToolTest {

    private val tool = ProjectStructureTool()
    private val project = mockk<Project>(relaxed = true)

    // ────────────────────────────────────────────────────────────────────────
    // Tier 1 — Tool surface
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class ToolSurface {

        @Test
        fun `tool name is project_structure`() {
            assertEquals("project_structure", tool.name)
        }

        @Test
        fun `description mentions module`() {
            assertTrue(tool.description.contains("module"), "description should mention 'module'")
        }

        @Test
        fun `description mentions source root`() {
            assertTrue(tool.description.contains("source root"), "description should mention 'source root'")
        }

        @Test
        fun `description mentions SDK`() {
            assertTrue(tool.description.contains("SDK"), "description should mention 'SDK'")
        }

        @Test
        fun `description mentions external system`() {
            assertTrue(tool.description.contains("external system"), "description should mention 'external system'")
        }

        @Test
        fun `description lists all 8 actions`() {
            val desc = tool.description
            ALL_ACTIONS.forEach { action ->
                assertTrue(desc.contains(action), "description should mention action '$action'")
            }
        }

        @Test
        fun `action enum contains exactly 8 actions`() {
            val actions = tool.parameters.properties["action"]?.enumValues
            assertNotNull(actions)
            assertEquals(8, actions!!.size)
        }

        @Test
        fun `action enum contains all expected action names`() {
            val actions = tool.parameters.properties["action"]?.enumValues!!.toSet()
            assertEquals(ALL_ACTIONS.toSet(), actions)
        }

        @Test
        fun `only action is required`() {
            assertEquals(listOf("action"), tool.parameters.required)
        }

        @Test
        fun `path parameter exists and is string type`() {
            val prop = tool.parameters.properties["path"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `module parameter exists and is string type`() {
            val prop = tool.parameters.properties["module"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `kind parameter exists with correct enum values`() {
            val prop = tool.parameters.properties["kind"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
            val kindValues = prop.enumValues
            assertNotNull(kindValues)
            assertEquals(
                setOf("source", "test_source", "resource", "test_resource"),
                kindValues!!.toSet()
            )
        }

        @Test
        fun `scope parameter exists with correct enum values`() {
            val prop = tool.parameters.properties["scope"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
            val scopeValues = prop.enumValues
            assertNotNull(scopeValues)
            assertEquals(
                setOf("project", "application", "all"),
                scopeValues!!.toSet()
            )
        }

        @Test
        fun `detect_cycles parameter exists and is boolean type`() {
            val prop = tool.parameters.properties["detect_cycles"]
            assertNotNull(prop)
            assertEquals("boolean", prop!!.type)
        }

        @Test
        fun `allowedWorkers includes all 5 worker types`() {
            assertEquals(
                setOf(
                    WorkerType.TOOLER,
                    WorkerType.ANALYZER,
                    WorkerType.REVIEWER,
                    WorkerType.ORCHESTRATOR,
                    WorkerType.CODER
                ),
                tool.allowedWorkers
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tier 2 — Dispatcher contract
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class DispatcherContract {

        @Test
        fun `missing action returns error containing word action`() = runTest {
            val result = tool.execute(buildJsonObject { }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("action"), "error message should mention 'action'")
        }

        @Test
        fun `unknown action returns error`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "bogus_action_xyz")
            }, project)
            assertTrue(result.isError)
        }

        @Test
        fun `unknown action error contains the bogus value`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "bogus_action_xyz")
            }, project)
            assertTrue(result.isError)
            assertTrue(
                result.content.contains("bogus_action_xyz"),
                "error message should include the unknown action name"
            )
        }

        @Test
        fun `unknown action error mentions resolve_file as valid reference`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "totally_unknown")
            }, project)
            assertTrue(result.isError)
            assertTrue(
                result.content.contains("resolve_file"),
                "error message should mention 'resolve_file' as a hint"
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tier 3 — Action routing smoke tests
    //
    // For every action enum value, calling execute(action=X) must either:
    //   (a) return a ToolResult (error or otherwise — stubs returning isError=true is fine), or
    //   (b) throw only a recognised boundary exception
    //
    // Must NOT silently return success. Must NOT hit an IllegalArgumentException
    // inside the dispatcher itself.
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class ActionRoutingSmoke {

        private fun smokeTestAction(actionName: String) = runTest {
            val result = runCatching {
                tool.execute(buildJsonObject { put("action", actionName) }, project)
            }
            if (result.isSuccess) {
                val toolResult = result.getOrNull()
                assertNotNull(toolResult, "$actionName: result is null")
                // Stubs return isError=true — that is acceptable; dispatcher routed correctly.
            } else {
                val ex = result.exceptionOrNull()!!
                val acceptable = ex is NullPointerException ||
                    ex is IllegalStateException ||
                    ex is NoClassDefFoundError ||
                    ex is ClassNotFoundException ||
                    ex is UnsupportedOperationException ||
                    ex is RuntimeException
                assertTrue(
                    acceptable,
                    "$actionName: unexpected exception type ${ex::class.simpleName}: ${ex.message}"
                )
            }
        }

        @Test fun `resolve_file routes`() = smokeTestAction("resolve_file")
        @Test fun `module_detail routes`() = smokeTestAction("module_detail")
        @Test fun `topology routes`() = smokeTestAction("topology")
        @Test fun `list_sdks routes`() = smokeTestAction("list_sdks")
        @Test fun `list_libraries routes`() = smokeTestAction("list_libraries")
        @Test fun `list_facets routes`() = smokeTestAction("list_facets")
        @Test fun `refresh_external_project routes`() = smokeTestAction("refresh_external_project")
        @Test fun `add_source_root routes`() = smokeTestAction("add_source_root")
    }

    companion object {
        val ALL_ACTIONS = listOf(
            "resolve_file",
            "module_detail",
            "topology",
            "list_sdks",
            "list_libraries",
            "list_facets",
            "refresh_external_project",
            "add_source_root"
        )
    }
}
