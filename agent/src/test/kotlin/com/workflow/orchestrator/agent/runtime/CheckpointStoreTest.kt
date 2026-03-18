package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CheckpointStoreTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var store: CheckpointStore

    @BeforeEach
    fun setup() {
        store = CheckpointStore(tempDir)
    }

    @Test
    fun `save and load roundtrip`() {
        val checkpoint = createCheckpoint("task-1")
        store.save("task-1", checkpoint)

        val loaded = store.load("task-1")
        assertNotNull(loaded)
        assertEquals("task-1", loaded!!.taskId)
        assertEquals(checkpoint.timestamp, loaded.timestamp)
    }

    @Test
    fun `load returns null for non-existent checkpoint`() {
        assertNull(store.load("nonexistent"))
    }

    @Test
    fun `exists returns true for saved checkpoint`() {
        store.save("task-1", createCheckpoint("task-1"))
        assertTrue(store.exists("task-1"))
    }

    @Test
    fun `exists returns false for non-existent checkpoint`() {
        assertFalse(store.exists("nonexistent"))
    }

    @Test
    fun `delete removes a checkpoint`() {
        store.save("task-1", createCheckpoint("task-1"))
        assertTrue(store.exists("task-1"))

        val deleted = store.delete("task-1")
        assertTrue(deleted)
        assertFalse(store.exists("task-1"))
    }

    @Test
    fun `delete returns false for non-existent checkpoint`() {
        assertFalse(store.delete("nonexistent"))
    }

    @Test
    fun `save overwrites existing checkpoint`() {
        store.save("task-1", createCheckpoint("task-1", timestamp = 100))
        store.save("task-1", createCheckpoint("task-1", timestamp = 200))

        val loaded = store.load("task-1")
        assertEquals(200L, loaded?.timestamp)
    }

    @Test
    fun `listCheckpoints returns all saved IDs`() {
        store.save("task-1", createCheckpoint("task-1"))
        store.save("task-2", createCheckpoint("task-2"))
        store.save("task-3", createCheckpoint("task-3"))

        val checkpoints = store.listCheckpoints()
        assertEquals(3, checkpoints.size)
        assertTrue(checkpoints.containsAll(listOf("task-1", "task-2", "task-3")))
    }

    @Test
    fun `listCheckpoints returns empty list when no checkpoints`() {
        assertTrue(store.listCheckpoints().isEmpty())
    }

    @Test
    fun `save preserves task graph state`() {
        val tasks = listOf(
            AgentTask(
                id = "t1",
                description = "Analyze code",
                action = TaskAction.ANALYZE,
                target = "Main.kt",
                workerType = WorkerType.ANALYZER,
                status = TaskStatus.COMPLETED,
                resultSummary = "done"
            ),
            AgentTask(
                id = "t2",
                description = "Fix bug",
                action = TaskAction.CODE,
                target = "Bug.kt",
                workerType = WorkerType.CODER,
                status = TaskStatus.PENDING,
                dependsOn = listOf("t1")
            )
        )

        val checkpoint = AgentCheckpoint(
            taskId = "main",
            taskGraphState = tasks,
            completedSummaries = mapOf("t1" to "Analysis done"),
            timestamp = System.currentTimeMillis()
        )

        store.save("main", checkpoint)
        val loaded = store.load("main")!!

        assertEquals(2, loaded.taskGraphState.size)
        assertEquals(TaskStatus.COMPLETED, loaded.taskGraphState[0].status)
        assertEquals("done", loaded.taskGraphState[0].resultSummary)
        assertEquals(listOf("t1"), loaded.taskGraphState[1].dependsOn)
    }

    @Test
    fun `save creates directory if it does not exist`() {
        val nestedDir = File(tempDir, "nested/deep/dir")
        val nestedStore = CheckpointStore(nestedDir)

        nestedStore.save("task-1", createCheckpoint("task-1"))
        assertTrue(nestedStore.exists("task-1"))
    }

    private fun createCheckpoint(
        taskId: String,
        timestamp: Long = System.currentTimeMillis()
    ): AgentCheckpoint = AgentCheckpoint(
        taskId = taskId,
        taskGraphState = emptyList(),
        completedSummaries = emptyMap(),
        timestamp = timestamp
    )
}
