package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.Task
import com.workflow.orchestrator.agent.loop.TaskStatus
import com.workflow.orchestrator.agent.session.TaskStore
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TaskListToolTest {

    @Test
    fun `returns error when task store is unavailable`() = runTest {
        val tool = TaskListTool { null }
        val project = mockk<Project>(relaxed = true)
        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("store", ignoreCase = true))
    }

    @Test
    fun `list returns summary content with minimal fields`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        store.addTask(Task(id = "t-1", subject = "A", description = "a-long-description"))
        store.addTask(Task(id = "t-2", subject = "B", description = "b-long-description", status = TaskStatus.IN_PROGRESS))
        val tool = TaskListTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(buildJsonObject { }, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("t-1"))
        assertTrue(result.content.contains("A"))
        assertTrue(result.content.contains("B"))
        assertTrue(result.content.contains("in_progress"))
        assertFalse(
            result.content.contains("a-long-description"),
            "description must NOT be in list output (force task_get for details)",
        )
    }

    @Test
    fun `empty list returns explanatory message`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        val tool = TaskListTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(buildJsonObject { }, project)

        assertFalse(result.isError)
        assertTrue(result.content.contains("No tasks", ignoreCase = true))
    }
}
