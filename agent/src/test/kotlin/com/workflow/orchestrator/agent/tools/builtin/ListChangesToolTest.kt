package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ListChangesToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = ListChangesTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("list_changes", tool.name)
        assertTrue(tool.parameters.required.isEmpty())
        assertTrue(tool.parameters.properties.containsKey("file"))
        assertTrue(tool.parameters.properties.containsKey("iteration"))
    }

    @Test
    fun `allowed for coder reviewer and analyzer`() {
        assertEquals(
            setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER),
            tool.allowedWorkers
        )
    }

    @Test
    fun `returns error when agent service unavailable`() = runTest {
        // Project mock has no AgentService registered, so getInstance will fail
        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError || result.content.contains("No change ledger") || result.content.contains("Error"))
    }

    @Test
    fun `accepts optional file parameter`() {
        assertFalse(tool.parameters.required.contains("file"))
    }

    @Test
    fun `accepts optional iteration parameter`() {
        assertFalse(tool.parameters.required.contains("iteration"))
    }
}
