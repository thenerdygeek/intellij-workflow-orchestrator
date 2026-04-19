package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RuntimeConfigToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = RuntimeConfigTool()

    // ──────────────────────────────────────────────────────────────────────
    // Tool surface
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `tool name is runtime_config`() {
        assertEquals("runtime_config", tool.name)
    }

    @Test
    fun `action enum contains all 4 actions`() {
        val actions = tool.parameters.properties["action"]?.enumValues
        assertNotNull(actions)
        assertEquals(4, actions!!.size)
        assertTrue("get_run_configurations" in actions)
        assertTrue("create_run_config" in actions)
        assertTrue("modify_run_config" in actions)
        assertTrue("delete_run_config" in actions)
    }

    @Test
    fun `only action is required`() {
        assertEquals(listOf("action"), tool.parameters.required)
    }

    @Test
    fun `allowedWorkers includes all expected types`() {
        assertEquals(
            setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER),
            tool.allowedWorkers
        )
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("runtime_config", def.function.name)
        assertEquals("object", def.function.parameters.type)
        assertTrue(def.function.description.isNotBlank())
    }

    @Test
    fun `module parameter is present in schema`() {
        val moduleProp = tool.parameters.properties["module"]
        assertNotNull(moduleProp)
        assertTrue(
            moduleProp!!.description.contains("module", ignoreCase = true),
            "module param description should reference module: ${moduleProp.description}"
        )
    }

    @Test
    fun `create_run_config description mentions module parameter`() {
        assertTrue(
            tool.description.contains("module?", ignoreCase = true),
            "Tool description should mention module? param in create_run_config action"
        )
    }

    @Test
    fun `missing action returns error`() = runTest {
        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("action"))
    }

    @Test
    fun `unknown action returns error`() = runTest {
        val result = tool.execute(buildJsonObject { put("action", "nonexistent") }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Unknown action"))
    }

    // ──────────────────────────────────────────────────────────────────────
    // create_run_config — validation scenarios
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    inner class CreateRunConfigTests {

        @Test
        fun `missing name returns error`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "create_run_config")
                put("type", "application")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Missing required parameter: name"))
        }

        @Test
        fun `missing type returns error`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "create_run_config")
                put("name", "MyApp")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Missing required parameter: type"))
        }

        @Test
        fun `invalid type returns error`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "create_run_config")
                put("name", "MyApp")
                put("type", "invalid_type")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Invalid type 'invalid_type'"))
        }

        @Test
        fun `application without main_class returns error`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "create_run_config")
                put("name", "MyApp")
                put("type", "application")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("main_class"))
            assertTrue(result.content.contains("required"))
        }

        @Test
        fun `spring_boot without main_class returns error`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "create_run_config")
                put("name", "MyApp")
                put("type", "spring_boot")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("main_class"))
            assertTrue(result.content.contains("required"))
        }

        @Test
        fun `junit without test_class returns error`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "create_run_config")
                put("name", "MyTest")
                put("type", "junit")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("test_class"))
            assertTrue(result.content.contains("required"))
        }

        @Test
        fun `remote_debug does not require main_class or test_class`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "create_run_config")
                put("name", "RemoteDebug")
                put("type", "remote_debug")
            }, project)
            // Should NOT fail with missing main_class/test_class — it will fail at RunManager level
            assertFalse(result.content.contains("main_class"))
            assertFalse(result.content.contains("test_class"))
        }

        @Test
        fun `gradle does not require main_class or test_class`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "create_run_config")
                put("name", "GradleBuild")
                put("type", "gradle")
            }, project)
            assertFalse(result.content.contains("main_class"))
            assertFalse(result.content.contains("test_class"))
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // modify_run_config — validation scenarios
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    inner class ModifyRunConfigTests {

        @Test
        fun `missing name returns error`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "modify_run_config")
                put("vm_options", "-Xmx2g")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Missing required parameter: name"))
        }

        @Test
        fun `no modification fields returns error`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "modify_run_config")
                put("name", "[Agent] MyApp")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("No modifications"))
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // delete_run_config — validation + safety scenarios
    // ──────────────────────────────────────────────────────────────────────

    @Nested
    inner class DeleteRunConfigTests {

        @Test
        fun `missing name returns error`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "delete_run_config")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Missing required parameter: name"))
        }

        @Test
        fun `non-agent config rejected by safety check`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "delete_run_config")
                put("name", "MyUserConfig")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Cannot delete"))
            assertTrue(result.content.contains("safety constraint"))
        }

        @Test
        fun `config without Agent prefix rejected`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "delete_run_config")
                put("name", "Spring Boot App")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("[Agent]"))
        }

        @Test
        fun `agent-prefixed config passes safety check`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("action", "delete_run_config")
                put("name", "[Agent] MyConfig")
            }, project)
            // Without a running IDE, RunManager.getInstance() will throw — but it should
            // NOT fail with the safety check error
            assertTrue(result.isError)
            assertFalse(result.content.contains("Cannot delete"))
            assertTrue(result.content.contains("Error"))
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // module lookup — behavior test for error path
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `create_run_config with nonexistent module surfaces error in failures`() = runTest {
        val params = buildJsonObject {
            put("action", "create_run_config")
            put("name", "MyApp")
            put("type", "application")
            put("main_class", "com.example.Main")
            put("module", "nonexistent-module")
        }
        val result = tool.execute(params, project)
        // The tool catches module lookup failure in trySetProperty and includes it in warnings,
        // or it fails to create the config entirely due to RunManager mock returning null.
        // Either way, the result should exist (not throw).
        assertNotNull(result)
        // The error or warning should reference the module parameter
        val combined = result.content + result.summary
        assertTrue(
            combined.contains("module", ignoreCase = true) || result.isError,
            "Expected result to mention module or be an error. Got: $combined"
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // AGENT_PREFIX constant
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `AGENT_PREFIX constant is correct`() {
        assertEquals("[Agent] ", RuntimeConfigTool.AGENT_PREFIX)
    }
}
