package com.workflow.orchestrator.agent.session

import com.workflow.orchestrator.agent.loop.Task
import com.workflow.orchestrator.agent.loop.TaskStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TaskStorePersistenceTest {

    @Test
    fun `addTask writes tasks json to session dir`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "sess-1")
        store.addTask(Task(id = "t-1", subject = "Write tests", description = "Add coverage"))

        val jsonFile = File(tmp, "sessions/sess-1/tasks.json")
        assertTrue(jsonFile.exists(), "tasks.json must exist after addTask")
        assertTrue(jsonFile.readText().contains("Write tests"))
    }

    @Test
    fun `tasks reload from disk via loadFromDisk`(@TempDir tmp: File) = runTest {
        val first = TaskStore(baseDir = tmp, sessionId = "sess-1")
        first.addTask(Task(id = "t-1", subject = "A", description = "a"))
        first.addTask(Task(id = "t-2", subject = "B", description = "b"))

        val second = TaskStore(baseDir = tmp, sessionId = "sess-1")
        second.loadFromDisk()

        val tasks = second.listTasks()
        assertEquals(2, tasks.size)
        assertEquals(setOf("t-1", "t-2"), tasks.map { it.id }.toSet())
    }

    @Test
    fun `updateTask mutates status and persists`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "sess-1")
        store.addTask(Task(id = "t-1", subject = "A", description = "a"))

        store.updateTask("t-1") { it.copy(status = TaskStatus.IN_PROGRESS) }

        val updated = store.getTask("t-1")
        assertEquals(TaskStatus.IN_PROGRESS, updated?.status)

        val reloaded = TaskStore(baseDir = tmp, sessionId = "sess-1").also { it.loadFromDisk() }
        assertEquals(TaskStatus.IN_PROGRESS, reloaded.getTask("t-1")?.status)
    }

    @Test
    fun `getTask returns null for unknown id`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "sess-1")
        assertNull(store.getTask("nonexistent"))
    }

    @Test
    fun `deleted tasks remain in list`(@TempDir tmp: File) = runTest {
        val store = TaskStore(baseDir = tmp, sessionId = "sess-1")
        store.addTask(Task(id = "t-1", subject = "A", description = "a"))
        store.updateTask("t-1") { it.copy(status = TaskStatus.DELETED) }

        val all = store.listTasks()
        assertEquals(1, all.size)
        assertEquals(TaskStatus.DELETED, all[0].status)
    }
}
