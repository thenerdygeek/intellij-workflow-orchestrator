package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.Task
import com.workflow.orchestrator.agent.session.TaskStore
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TaskGetToolTest {

    @Test
    fun `returns full task details including description`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        store.addTask(Task(id = "t-1", subject = "A", description = "detailed description here"))
        val tool = TaskGetTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(buildJsonObject { put("taskId", "t-1") }, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("detailed description here"))
        assertTrue(result.content.contains("t-1"))
        assertTrue(result.content.contains("A"))
    }

    @Test
    fun `unknown id returns error`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        val tool = TaskGetTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(buildJsonObject { put("taskId", "nope") }, project)

        assertTrue(result.isError)
    }
}
