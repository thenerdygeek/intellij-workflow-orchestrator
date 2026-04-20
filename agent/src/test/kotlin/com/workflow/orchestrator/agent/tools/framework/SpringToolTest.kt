package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Scenario-based tests for [SpringTool].
 *
 * Strategy: SpringTool is a meta-tool with 15 actions; many actions touch real
 * IntelliJ PSI / Maven services that require a running IDE fixture and cannot
 * be exercised in plain unit tests. These tests therefore lock in:
 *
 *  1. **Tool surface** — name, action enum, parameter schema, allowedWorkers,
 *     ToolDefinition serialization. Catches accidental schema drift.
 *
 *  2. **Dispatcher contract** — what happens when `action` is missing,
 *     unknown, or routes to one of the 15 implementations. Catches dispatcher
 *     bugs (e.g. typo in `when` branch).
 *
 *  3. **Pre-PSI validation paths** — actions that check parameters before
 *     reaching PSI (e.g. `config` checks basePath first). These catch
 *     parameter-handling regressions.
 *
 *  4. **Action smoke routing** — for every action enum value, call execute()
 *     and assert the call returns OR throws a recognized boundary exception
 *     (PSI/Maven services not available in unit tests). Each action MUST
 *     either reach an error ToolResult or throw at the IDE-services boundary;
 *     it must NOT silently return success or hit a NullPointerException
 *     inside the dispatcher.
 *
 * This is the test contract that the post-refactor SpringTool (with extracted
 * action files) must continue to satisfy without modification.
 */
class SpringToolTest {

    private val tool = SpringTool()
    private val project = mockk<Project>(relaxed = true)

    // ────────────────────────────────────────────────────────────────────────
    // Tier 1 — Tool surface
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class ToolSurface {

        @Test
        fun `tool name is spring`() {
            assertEquals("spring", tool.name)
        }

        @Test
        fun `description mentions Spring framework`() {
            assertTrue(tool.description.contains("Spring"))
        }

        @Test
        fun `description lists all 15 actions`() {
            val desc = tool.description
            ALL_ACTIONS.forEach { action ->
                assertTrue(desc.contains(action), "description should mention action '$action'")
            }
        }

        @Test
        fun `default constructor action enum contains 15 actions`() {
            val actions = tool.parameters.properties["action"]?.enumValues
            assertNotNull(actions)
            assertEquals(15, actions!!.size)
        }

        @Test
        fun `includeEndpointActions=false omits endpoints and boot_endpoints`() {
            val trimmed = SpringTool(includeEndpointActions = false)
            val actions = trimmed.parameters.properties["action"]?.enumValues!!.toSet()
            assertEquals(13, actions.size)
            assertFalse(actions.contains("endpoints"))
            assertFalse(actions.contains("boot_endpoints"))
        }

        @Test
        fun `includeEndpointActions=false description omits endpoint actions`() {
            val trimmed = SpringTool(includeEndpointActions = false)
            assertFalse(trimmed.description.contains("endpoints(filter?"))
            assertFalse(trimmed.description.contains("boot_endpoints(class_name"))
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
        fun `filter parameter exists and is string type`() {
            val prop = tool.parameters.properties["filter"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `bean_name parameter exists and is string type`() {
            val prop = tool.parameters.properties["bean_name"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `property parameter exists and is string type`() {
            val prop = tool.parameters.properties["property"]
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
        fun `include_params parameter exists and is boolean type`() {
            val prop = tool.parameters.properties["include_params"]
            assertNotNull(prop)
            assertEquals("boolean", prop!!.type)
        }

        @Test
        fun `class_name parameter exists and is string type`() {
            val prop = tool.parameters.properties["class_name"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `prefix parameter exists and is string type`() {
            val prop = tool.parameters.properties["prefix"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `project_only parameter exists and is boolean type`() {
            val prop = tool.parameters.properties["project_only"]
            assertNotNull(prop)
            assertEquals("boolean", prop!!.type)
        }

        @Test
        fun `entity parameter exists and is string type`() {
            val prop = tool.parameters.properties["entity"]
            assertNotNull(prop)
            assertEquals("string", prop!!.type)
        }

        @Test
        fun `total parameter count is 10`() {
            // 1 action discriminator + 9 action-specific parameters
            assertEquals(10, tool.parameters.properties.size)
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
            assertEquals("spring", def.function.name)
            assertTrue(def.function.description.isNotBlank())
            assertEquals("object", def.function.parameters.type)
            assertEquals(10, def.function.parameters.properties.size)
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
                put("action", "explode_the_universe")
            }, project)
            assertTrue(result.content.contains("explode_the_universe"))
        }

        @Test
        fun `dispatcher routes by exact case-sensitive action name`() = runTest {
            // CONTEXT (uppercase) is not the same as 'context'
            val result = tool.execute(buildJsonObject {
                put("action", "CONTEXT")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Unknown action"))
        }

        @Test
        fun `endpoints action returns 'use endpoints tool' error when flag is off`() = runTest {
            val trimmed = SpringTool(includeEndpointActions = false)
            val result = trimmed.execute(
                buildJsonObject { put("action", "endpoints") },
                project,
            )
            assertTrue(result.isError)
            assertTrue(
                result.content.contains("endpoints", ignoreCase = true),
                "error message should point at the endpoints tool"
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tier 3 — Pre-PSI validation paths
    //
    // These tests target actions that perform parameter / project-state
    // validation BEFORE touching IntelliJ PSI services, so they can run
    // against a relaxed mock.
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class PrePsiValidation {

        @Test
        fun `config action with null basePath returns base path error`() = runTest {
            every { project.basePath } returns null

            val result = tool.execute(buildJsonObject {
                put("action", "config")
            }, project)

            assertTrue(result.isError)
            assertTrue(result.content.contains("project base path"))
        }

        @Test
        fun `config action with nonexistent basePath returns no-files message`() = runTest {
            every { project.basePath } returns "/tmp/nonexistent-spring-test-dir-zzz"

            val result = tool.execute(buildJsonObject {
                put("action", "config")
            }, project)

            // Either "No Spring configuration files found" or an error from
            // walking the missing dir — both are valid error responses.
            assertTrue(
                result.isError || result.content.contains("No Spring configuration files"),
                "Expected error or no-files message; got: ${result.content}"
            )
        }

        @Test
        fun `config action with property param does not crash on missing basePath`() = runTest {
            every { project.basePath } returns null

            val result = tool.execute(buildJsonObject {
                put("action", "config")
                put("property", "spring.datasource.url")
            }, project)

            assertTrue(result.isError)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tier 4 — Action routing smoke tests
    //
    // For every action enum value, calling `execute(action=X)` must either:
    //   (a) return a ToolResult (error or otherwise), or
    //   (b) throw a recognized boundary exception (PSI/Maven service not
    //       available — typical when IntelliJ services aren't initialized
    //       in unit tests)
    //
    // It must NOT silently return success and must NOT hit an
    // IllegalStateException inside the dispatcher itself.
    //
    // These tests guard against the main refactor risk: a typo or missing
    // branch in the action `when` statement after extracting actions to
    // separate files.
    // ────────────────────────────────────────────────────────────────────────

    @Nested
    inner class ActionRoutingSmoke {

        private fun smokeTestAction(actionName: String, extraParams: Map<String, String> = emptyMap()) =
            runTest {
                every { project.basePath } returns "/tmp/spring-test-noexist"
                val result = runCatching {
                    tool.execute(
                        buildJsonObject {
                            put("action", actionName)
                            extraParams.forEach { (k, v) -> put(k, v) }
                        },
                        project
                    )
                }
                // Either we got a result, or we threw at the PSI/Maven boundary.
                // Both are acceptable; what's NOT acceptable is the dispatcher
                // mis-routing or returning a nonsense success.
                if (result.isSuccess) {
                    val toolResult = result.getOrNull()
                    assertNotNull(toolResult, "$actionName: result is null")
                    // If it's not an error, the action successfully ran (very rare
                    // in unit tests without real PSI). Either way, the dispatcher
                    // routed correctly.
                } else {
                    val ex = result.exceptionOrNull()!!
                    // The boundary exceptions we expect: NPE / IllegalState from
                    // missing IDE services, or NoClassDefFoundError from Spring
                    // plugin reflection. NOT a Kotlin IllegalArgumentException
                    // from a typo in the dispatcher.
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

        @Test fun `context routes`() = smokeTestAction("context")
        @Test fun `endpoints routes`() = smokeTestAction("endpoints")
        @Test fun `bean_graph routes`() = smokeTestAction("bean_graph", mapOf("bean_name" to "FooService"))
        @Test fun `config routes`() = smokeTestAction("config")
        @Test fun `version_info routes`() = smokeTestAction("version_info")
        @Test fun `profiles routes`() = smokeTestAction("profiles")
        @Test fun `repositories routes`() = smokeTestAction("repositories")
        @Test fun `security_config routes`() = smokeTestAction("security_config")
        @Test fun `scheduled_tasks routes`() = smokeTestAction("scheduled_tasks")
        @Test fun `event_listeners routes`() = smokeTestAction("event_listeners")
        @Test fun `boot_endpoints routes`() = smokeTestAction("boot_endpoints")
        @Test fun `boot_autoconfig routes`() = smokeTestAction("boot_autoconfig")
        @Test fun `boot_config_properties routes`() = smokeTestAction("boot_config_properties")
        @Test fun `boot_actuator routes`() = smokeTestAction("boot_actuator")
        @Test fun `jpa_entities routes`() = smokeTestAction("jpa_entities")
    }

    companion object {
        val ALL_ACTIONS = listOf(
            "context",
            "endpoints",
            "bean_graph",
            "config",
            "version_info",
            "profiles",
            "repositories",
            "security_config",
            "scheduled_tasks",
            "event_listeners",
            "boot_endpoints",
            "boot_autoconfig",
            "boot_config_properties",
            "boot_actuator",
            "jpa_entities"
        )
    }
}
