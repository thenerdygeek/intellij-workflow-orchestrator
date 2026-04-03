package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RevertFileToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = RevertFileTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("revert_file", tool.name)
        assertTrue(tool.parameters.required.contains("file_path"))
        assertTrue(tool.parameters.required.contains("description"))
    }

    @Test
    fun `allowed only for coder`() {
        assertEquals(setOf(WorkerType.CODER), tool.allowedWorkers)
    }

    @Test
    fun `returns error when file_path missing`() = runTest {
        val params = buildJsonObject { put("description", "bad edit") }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("file_path"))
    }

    @Test
    fun `returns error when description missing`() = runTest {
        val params = buildJsonObject { put("file_path", "/src/A.kt") }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("description"))
    }

    @Test
    fun `returns error when agent service unavailable`() = runTest {
        val params = buildJsonObject {
            put("file_path", "/src/A.kt")
            put("description", "bad edit")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
    }
}
