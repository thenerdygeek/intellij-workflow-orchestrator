package com.workflow.orchestrator.agent.session

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MessageStateHandlerDeleteFavoriteTest {

    @TempDir
    lateinit var tempDir: File

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun writeIndex(items: List<HistoryItem>) {
        File(tempDir, "sessions.json").writeText(json.encodeToString(items))
    }

    private fun readIndex(): List<HistoryItem> =
        MessageStateHandler.loadGlobalIndex(tempDir)

    private fun createSessionDir(sessionId: String) {
        val dir = File(tempDir, "sessions/$sessionId")
        dir.mkdirs()
        File(dir, "ui_messages.json").writeText("[]")
        File(dir, "api_conversation_history.json").writeText("[]")
    }

    private fun sessionDirExists(sessionId: String): Boolean =
        File(tempDir, "sessions/$sessionId").exists()

    // --- deleteSession tests ---

    @Test
    fun `deleteSession removes entry from global index`() {
        val items = listOf(
            HistoryItem(id = "s1", ts = 1000, task = "Task 1"),
            HistoryItem(id = "s2", ts = 2000, task = "Task 2"),
            HistoryItem(id = "s3", ts = 3000, task = "Task 3"),
        )
        writeIndex(items)
        createSessionDir("s2")

        MessageStateHandler.deleteSession(tempDir, "s2")

        val remaining = readIndex()
        assertEquals(2, remaining.size)
        assertTrue(remaining.none { it.id == "s2" })
        assertEquals("s1", remaining[0].id)
        assertEquals("s3", remaining[1].id)
    }

    @Test
    fun `deleteSession removes session directory from disk`() {
        writeIndex(listOf(HistoryItem(id = "s1", ts = 1000, task = "Task 1")))
        createSessionDir("s1")
        assertTrue(sessionDirExists("s1"))

        MessageStateHandler.deleteSession(tempDir, "s1")

        assertFalse(sessionDirExists("s1"))
    }

    @Test
    fun `deleteSession is no-op for unknown session id`() {
        val items = listOf(HistoryItem(id = "s1", ts = 1000, task = "Task 1"))
        writeIndex(items)

        MessageStateHandler.deleteSession(tempDir, "unknown")

        assertEquals(1, readIndex().size)
    }

    @Test
    fun `deleteSession works when sessions json does not exist`() {
        assertDoesNotThrow {
            MessageStateHandler.deleteSession(tempDir, "nonexistent")
        }
    }

    // --- toggleFavorite tests ---

    @Test
    fun `toggleFavorite flips false to true`() {
        writeIndex(listOf(HistoryItem(id = "s1", ts = 1000, task = "Task 1", isFavorited = false)))

        MessageStateHandler.toggleFavorite(tempDir, "s1")

        val item = readIndex().single()
        assertTrue(item.isFavorited)
    }

    @Test
    fun `toggleFavorite flips true to false`() {
        writeIndex(listOf(HistoryItem(id = "s1", ts = 1000, task = "Task 1", isFavorited = true)))

        MessageStateHandler.toggleFavorite(tempDir, "s1")

        val item = readIndex().single()
        assertFalse(item.isFavorited)
    }

    @Test
    fun `toggleFavorite only affects target session`() {
        writeIndex(listOf(
            HistoryItem(id = "s1", ts = 1000, task = "Task 1", isFavorited = false),
            HistoryItem(id = "s2", ts = 2000, task = "Task 2", isFavorited = true),
        ))

        MessageStateHandler.toggleFavorite(tempDir, "s1")

        val items = readIndex()
        assertTrue(items.first { it.id == "s1" }.isFavorited)
        assertTrue(items.first { it.id == "s2" }.isFavorited)
    }

    @Test
    fun `toggleFavorite is no-op for unknown session id`() {
        writeIndex(listOf(HistoryItem(id = "s1", ts = 1000, task = "Task 1", isFavorited = false)))

        MessageStateHandler.toggleFavorite(tempDir, "unknown")

        assertFalse(readIndex().single().isFavorited)
    }
}
