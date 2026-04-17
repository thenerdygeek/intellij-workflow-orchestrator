package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.Task
import com.workflow.orchestrator.agent.loop.TaskStatus
import com.workflow.orchestrator.agent.session.TaskStore
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TaskUpdateToolTest {

    @Test
    fun `updates status to in_progress`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        store.addTask(Task(id = "t-1", subject = "A", description = "a"))
        val tool = TaskUpdateTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(
            buildJsonObject {
                put("taskId", "t-1")
                put("status", "in_progress")
            },
            project,
        )

        assertFalse(result.isError)
        assertEquals(TaskStatus.IN_PROGRESS, store.getTask("t-1")?.status)
    }

    @Test
    fun `addBlockedBy appends to existing list`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        store.addTask(Task(id = "A", subject = "A", description = "a"))
        store.addTask(Task(id = "B", subject = "B", description = "b"))
        store.addTask(Task(id = "C", subject = "C", description = "c"))
        val tool = TaskUpdateTool { store }
        val project = mockk<Project>(relaxed = true)

        tool.execute(
            buildJsonObject {
                put("taskId", "C")
                putJsonArray("addBlockedBy") { add("A"); add("B") }
            },
            project,
        )

        assertEquals(listOf("A", "B"), store.getTask("C")?.blockedBy)
    }

    @Test
    fun `unknown taskId returns error`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        val tool = TaskUpdateTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(
            buildJsonObject {
                put("taskId", "nonexistent")
                put("status", "completed")
            },
            project,
        )

        assertTrue(result.isError)
    }

    @Test
    fun `cycle-creating update is rejected with error toolresult`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        store.addTask(Task(id = "A", subject = "A", description = "a"))
        store.addTask(Task(id = "B", subject = "B", description = "b", blockedBy = listOf("A")))
        val tool = TaskUpdateTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(
            buildJsonObject {
                put("taskId", "A")
                putJsonArray("addBlockedBy") { add("B") }
            },
            project,
        )

        assertTrue(result.isError)
        assertTrue(result.content.contains("cycle", ignoreCase = true))
    }

    @Test
    fun `deleted status marks task deleted but keeps it in store`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        store.addTask(Task(id = "t-1", subject = "A", description = "a"))
        val tool = TaskUpdateTool { store }
        val project = mockk<Project>(relaxed = true)

        tool.execute(
            buildJsonObject {
                put("taskId", "t-1")
                put("status", "deleted")
            },
            project,
        )

        assertEquals(TaskStatus.DELETED, store.getTask("t-1")?.status)
    }
}
