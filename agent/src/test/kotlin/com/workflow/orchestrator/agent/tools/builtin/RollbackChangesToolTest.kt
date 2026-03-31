package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RollbackChangesToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = RollbackChangesTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("rollback_changes", tool.name)
        assertTrue(tool.parameters.required.contains("checkpoint_id"))
        assertTrue(tool.parameters.required.contains("description"))
    }

    @Test
    fun `allowed only for coder`() {
        assertEquals(setOf(WorkerType.CODER), tool.allowedWorkers)
    }

    @Test
    fun `returns error when checkpoint_id missing`() = runTest {
        val params = buildJsonObject { put("description", "test rollback") }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("checkpoint_id"))
    }

    @Test
    fun `returns error when description missing`() = runTest {
        val params = buildJsonObject { put("checkpoint_id", "abc123") }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("description"))
    }

    @Test
    fun `returns error when agent service unavailable`() = runTest {
        val params = buildJsonObject {
            put("checkpoint_id", "abc123")
            put("description", "test rollback")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
    }
}
