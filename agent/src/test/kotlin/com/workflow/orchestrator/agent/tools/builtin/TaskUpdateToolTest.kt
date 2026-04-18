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
    fun `returns error when task store is unavailable`() = runTest {
        val tool = TaskUpdateTool { null }
        val project = mockk<Project>(relaxed = true)
        val result = tool.execute(
            buildJsonObject {
                put("taskId", "t-1")
            },
            project,
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("store", ignoreCase = true))
    }

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

    // ── Issue: LLM drifted to id="1" when task_create returned a UUID. When that
    // task_update lands on an unknown id, the bare "Task not found: 1" response gives
    // the LLM nothing to recover with. List available tasks in the error so the LLM
    // can pick the correct id on the next attempt.
    @Test
    fun `unknown taskId error lists available tasks with their ids`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        store.addTask(Task(id = "1", subject = "Fix auth bug", description = "login"))
        store.addTask(Task(id = "2", subject = "Add rate limit", description = "auth"))
        val tool = TaskUpdateTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(
            buildJsonObject {
                put("taskId", "99")
                put("status", "completed")
            },
            project,
        )

        assertTrue(result.isError, "expected error for unknown id")
        assertTrue(
            result.content.contains("Fix auth bug") && result.content.contains("Add rate limit"),
            "error must surface available task subjects so the LLM can recover; got: ${result.content}"
        )
        assertTrue(
            result.content.contains("\"1\"") || result.content.contains("id=1") ||
                Regex("""\b1\b""").containsMatchIn(result.content),
            "error must surface available task ids so the LLM can recover; got: ${result.content}"
        )
    }

    @Test
    fun `unknown taskId error in empty store suggests task_create`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "s1")
        val tool = TaskUpdateTool { store }
        val project = mockk<Project>(relaxed = true)

        val result = tool.execute(
            buildJsonObject {
                put("taskId", "1")
                put("status", "completed")
            },
            project,
        )

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("task_create", ignoreCase = true) ||
                result.content.contains("no tasks", ignoreCase = true),
            "empty-store miss should steer the LLM toward task_create; got: ${result.content}"
        )
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
