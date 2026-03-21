package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class WorkerTranscriptStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var store: WorkerTranscriptStore

    @BeforeEach
    fun setUp() {
        store = WorkerTranscriptStore(tempDir.toFile())
    }

    @Test
    fun `saveMetadata and loadMetadata round-trip`() {
        val meta = WorkerTranscriptStore.WorkerMetadata(
            agentId = "abc123",
            subagentType = "coder",
            description = "fix auth bug"
        )
        store.saveMetadata(meta)
        val loaded = store.loadMetadata("abc123")
        assertNotNull(loaded)
        assertEquals("abc123", loaded!!.agentId)
        assertEquals("coder", loaded.subagentType)
        assertEquals("running", loaded.status)
    }

    @Test
    fun `appendMessage and loadTranscript work`() {
        store.appendMessage("abc123", WorkerTranscriptStore.TranscriptMessage(role = "system", content = "You are a coder"))
        store.appendMessage("abc123", WorkerTranscriptStore.TranscriptMessage(role = "user", content = "Fix the bug"))
        store.appendMessage("abc123", WorkerTranscriptStore.TranscriptMessage(role = "assistant", content = "I'll read the file"))
        val transcript = store.loadTranscript("abc123")
        assertEquals(3, transcript.size)
        assertEquals("system", transcript[0].role)
        assertEquals("Fix the bug", transcript[1].content)
    }

    @Test
    fun `updateStatus changes metadata`() {
        store.saveMetadata(WorkerTranscriptStore.WorkerMetadata(agentId = "abc123", subagentType = "coder", description = "test"))
        store.updateStatus("abc123", "completed", summary = "Fixed the bug", tokensUsed = 5000)
        val meta = store.loadMetadata("abc123")
        assertEquals("completed", meta!!.status)
        assertEquals("Fixed the bug", meta.summary)
        assertEquals(5000, meta.tokensUsed)
        assertNotNull(meta.completedAt)
    }

    @Test
    fun `listWorkers returns all workers sorted by recency`() {
        store.saveMetadata(WorkerTranscriptStore.WorkerMetadata(agentId = "old", subagentType = "coder", description = "old task", createdAt = 1000))
        store.saveMetadata(WorkerTranscriptStore.WorkerMetadata(agentId = "new", subagentType = "reviewer", description = "new task", createdAt = 2000))
        val workers = store.listWorkers()
        assertEquals(2, workers.size)
        assertEquals("new", workers[0].agentId)
    }

    @Test
    fun `toChatMessages converts transcript`() {
        val transcript = listOf(
            WorkerTranscriptStore.TranscriptMessage(role = "system", content = "prompt"),
            WorkerTranscriptStore.TranscriptMessage(role = "user", content = "task")
        )
        val messages = store.toChatMessages(transcript)
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].role)
    }

    @Test
    fun `loadMetadata returns null for missing agent`() {
        assertNull(store.loadMetadata("nonexistent"))
    }

    @Test
    fun `generateAgentId returns 12 char string`() {
        val id = WorkerTranscriptStore.generateAgentId()
        assertEquals(12, id.length)
    }

    @Test
    fun `loadTranscript returns empty for missing agent`() {
        assertTrue(store.loadTranscript("nonexistent").isEmpty())
    }

    @Test
    fun `updateStatus does nothing for missing agent`() {
        // Should not throw
        store.updateStatus("nonexistent", "completed")
    }
}
