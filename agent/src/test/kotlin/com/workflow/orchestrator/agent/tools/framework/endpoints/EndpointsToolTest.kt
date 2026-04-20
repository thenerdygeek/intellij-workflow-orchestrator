package com.workflow.orchestrator.agent.tools.framework.endpoints

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Surface + dispatcher contract tests for [EndpointsTool].
 *
 * Integration behaviour (actual microservices EP iteration) is covered by
 * running the plugin under a real IntelliJ fixture — plain JUnit cannot spin
 * up `EndpointsProvider`. These tests lock down schema and dispatcher routing.
 */
class EndpointsToolTest {

    private val tool = EndpointsTool()
    private val project = mockk<Project>(relaxed = true)

    @Nested
    inner class ToolSurface {

        @Test
        fun `tool name is endpoints`() {
            assertEquals("endpoints", tool.name)
        }

        @Test
        fun `action enum contains list, find_usages, export_openapi`() {
            val actions = tool.parameters.properties["action"]?.enumValues?.toSet()
            assertEquals(setOf("list", "find_usages", "export_openapi"), actions)
        }

        @Test
        fun `description mentions all three actions`() {
            val d = tool.description
            assertTrue(d.contains("list"))
            assertTrue(d.contains("find_usages"))
            assertTrue(d.contains("export_openapi"))
        }

        @Test
        fun `allowedWorkers includes tooler, analyzer, reviewer, orchestrator, coder`() {
            assertEquals(
                setOf(
                    WorkerType.TOOLER, WorkerType.ANALYZER, WorkerType.REVIEWER,
                    WorkerType.ORCHESTRATOR, WorkerType.CODER
                ),
                tool.allowedWorkers,
            )
        }

        @Test
        fun `only action is required`() {
            assertEquals(listOf("action"), tool.parameters.required)
        }
    }

    @Nested
    inner class DispatcherContract {

        @Test
        fun `missing action returns error result`() = runTest {
            val result = tool.execute(buildJsonObject {}, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("action", ignoreCase = true))
        }

        @Test
        fun `unknown action returns error result`() = runTest {
            val result = tool.execute(buildJsonObject { put("action", "banana") }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("banana") || result.content.contains("Unknown"))
        }

        @Test
        fun `find_usages without url parameter returns error result`() = runTest {
            val result = tool.execute(buildJsonObject { put("action", "find_usages") }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("url", ignoreCase = true))
        }
    }
}
