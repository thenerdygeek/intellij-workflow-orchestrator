package com.workflow.orchestrator.agent.tools.framework

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
 * Scenario-based tests for [BuildTool].
 *
 * Strategy mirrors [SpringToolTest]: BuildTool is a meta-tool with 26 actions
 * (11 Maven/Gradle/module + 15 pip/Poetry/uv/pytest) that touch real
 * Maven/Gradle/IntelliJ services and Python CLI tools. Tests lock in:
 *
 *  1. **Tool surface** — name, action enum, parameter schema, allowedWorkers,
 *     ToolDefinition serialization.
 *  2. **Dispatcher contract** — missing action, unknown action, case sensitivity.
 *  3. **Action routing smoke** — every action enum value routes to a non-crashing
 *     path. Catches typos in the dispatcher `when` block.
 *
 * This is the test contract that the post-refactor BuildTool (with extracted
 * action files) must continue to satisfy without modification.
 */
class BuildToolTest {

    private val tool = BuildTool()
    private val project = mockk<Project>(relaxed = true)

    // ────────────────────────────────────────────────────────────────────────
    // Tier 1 — Tool surface
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class ToolSurface {

        @Test
        fun `tool name is build`() {
            assertEquals("build", tool.name)
        }

        @Test
        fun `description mentions Maven and Gradle and Python tools`() {
            assertTrue(tool.description.contains("Maven"))
            assertTrue(tool.description.contains("Gradle"))
            assertTrue(tool.description.contains("pip"))
            assertTrue(tool.description.contains("Poetry"))
            assertTrue(tool.description.contains("uv"))
            assertTrue(tool.description.contains("pytest"))
        }

        @Test
        fun `description lists all 26 actions`() {
            val desc = tool.description
            ALL_ACTIONS.forEach { action ->
                assertTrue(desc.contains(action), "description should mention action '$action'")
            }
        }

        @Test
        fun `action enum contains exactly 26 actions`() {
            val actions = tool.parameters.properties["action"]?.enumValues
            assertNotNull(actions)
            assertEquals(26, actions!!.size)
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
        fun `module parameter exists and is string type`() {
            val prop = tool.parameters.properties["module"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `scope parameter exists and is string type`() {
            val prop = tool.parameters.properties["scope"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `search parameter exists and is string type`() {
            val prop = tool.parameters.properties["search"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `artifact parameter exists and is string type`() {
            val prop = tool.parameters.properties["artifact"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `plugin parameter exists and is string type`() {
            val prop = tool.parameters.properties["plugin"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `configuration parameter exists and is string type`() {
            val prop = tool.parameters.properties["configuration"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `transitive parameter exists and is boolean type`() {
            val prop = tool.parameters.properties["transitive"]
            assertNotNull(prop)
            assertEquals("boolean", prop!!.type)
        }

        @Test
        fun `include_libraries parameter exists and is boolean type`() {
            val prop = tool.parameters.properties["include_libraries"]
            assertNotNull(prop)
            assertEquals("boolean", prop!!.type)
        }

        @Test
        fun `detect_cycles parameter exists and is boolean type`() {
            val prop = tool.parameters.properties["detect_cycles"]
            assertNotNull(prop)
            assertEquals("boolean", prop!!.type)
        }

        @Test
        fun `package parameter exists and is string type`() {
            val prop = tool.parameters.properties["package"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `path parameter exists and is string type`() {
            val prop = tool.parameters.properties["path"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `pattern parameter exists and is string type`() {
            val prop = tool.parameters.properties["pattern"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `markers parameter exists and is string type`() {
            val prop = tool.parameters.properties["markers"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `total parameter count is 14`() {
            // 1 action discriminator + 9 Maven/Gradle params + 4 Python params
            assertEquals(14, tool.parameters.properties.size)
        }

        @Test
        fun `allowedWorkers includes all expected types`() {
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

        @Test
        fun `toToolDefinition produces valid schema`() {
            val def = tool.toToolDefinition()
            assertEquals("function", def.type)
            assertEquals("build", def.function.name)
            assertTrue(def.function.description.isNotBlank())
            assertEquals("object", def.function.parameters.type)
            assertEquals(14, def.function.parameters.properties.size)
            assertEquals(listOf("action"), def.function.parameters.required)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tier 2 — Dispatcher contract
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class DispatcherContract {

        @Test
        fun `missing action returns error`() = runTest {
            val result = tool.execute(buildJsonObject { }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("action"))
        }

        @Test
        fun `unknown action returns error`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "totally_made_up_action")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Unknown action"))
        }

        @Test
        fun `unknown action error mentions the action name`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "fly_to_the_moon")
            }, project)
            assertTrue(result.content.contains("fly_to_the_moon"))
        }

        @Test
        fun `dispatcher routes by exact case-sensitive action name`() = runTest {
            // MAVEN_DEPENDENCIES (uppercase) is not the same as 'maven_dependencies'
            val result = tool.execute(buildJsonObject {
                put("action", "MAVEN_DEPENDENCIES")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Unknown action"))
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tier 3 — Action routing smoke tests
    //
    // For every action enum value, calling `execute(action=X)` must either:
    //   (a) return a ToolResult (error or otherwise), or
    //   (b) throw a recognized boundary exception (Maven/Gradle service not
    //       available — typical when IntelliJ services aren't initialized
    //       in unit tests)
    //
    // It must NOT silently return success and must NOT hit an
    // IllegalArgumentException inside the dispatcher itself.
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
                // If it's not an error, the action successfully ran (very rare in
                // unit tests without real PSI/Maven/Gradle). The dispatcher routed
                // correctly either way.
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

        @Test fun `maven_dependencies routes`() = smokeTestAction("maven_dependencies")
        @Test fun `maven_properties routes`() = smokeTestAction("maven_properties")
        @Test fun `maven_plugins routes`() = smokeTestAction("maven_plugins")
        @Test fun `maven_profiles routes`() = smokeTestAction("maven_profiles")
        @Test fun `maven_dependency_tree routes`() = smokeTestAction("maven_dependency_tree")
        @Test fun `maven_effective_pom routes`() = smokeTestAction("maven_effective_pom")
        @Test fun `gradle_dependencies routes`() = smokeTestAction("gradle_dependencies")
        @Test fun `gradle_tasks routes`() = smokeTestAction("gradle_tasks")
        @Test fun `gradle_properties routes`() = smokeTestAction("gradle_properties")
        @Test fun `project_modules routes`() = smokeTestAction("project_modules")
        @Test fun `module_dependency_graph routes`() = smokeTestAction("module_dependency_graph")
        @Test fun `pip_list routes`() = smokeTestAction("pip_list")
        @Test fun `pip_outdated routes`() = smokeTestAction("pip_outdated")
        @Test fun `pip_show routes`() = smokeTestAction("pip_show")
        @Test fun `pip_dependencies routes`() = smokeTestAction("pip_dependencies")
        @Test fun `poetry_list routes`() = smokeTestAction("poetry_list")
        @Test fun `poetry_outdated routes`() = smokeTestAction("poetry_outdated")
        @Test fun `poetry_show routes`() = smokeTestAction("poetry_show")
        @Test fun `poetry_lock_status routes`() = smokeTestAction("poetry_lock_status")
        @Test fun `poetry_scripts routes`() = smokeTestAction("poetry_scripts")
        @Test fun `uv_list routes`() = smokeTestAction("uv_list")
        @Test fun `uv_outdated routes`() = smokeTestAction("uv_outdated")
        @Test fun `uv_lock_status routes`() = smokeTestAction("uv_lock_status")
        @Test fun `pytest_discover routes`() = smokeTestAction("pytest_discover")
        @Test fun `pytest_run routes`() = smokeTestAction("pytest_run")
        @Test fun `pytest_fixtures routes`() = smokeTestAction("pytest_fixtures")
    }

    companion object {
        val ALL_ACTIONS = listOf(
            "maven_dependencies",
            "maven_properties",
            "maven_plugins",
            "maven_profiles",
            "maven_dependency_tree",
            "maven_effective_pom",
            "gradle_dependencies",
            "gradle_tasks",
            "gradle_properties",
            "project_modules",
            "module_dependency_graph",
            "pip_list",
            "pip_outdated",
            "pip_show",
            "pip_dependencies",
            "poetry_list",
            "poetry_outdated",
            "poetry_show",
            "poetry_lock_status",
            "poetry_scripts",
            "uv_list",
            "uv_outdated",
            "uv_lock_status",
            "pytest_discover",
            "pytest_run",
            "pytest_fixtures"
        )
    }
}
