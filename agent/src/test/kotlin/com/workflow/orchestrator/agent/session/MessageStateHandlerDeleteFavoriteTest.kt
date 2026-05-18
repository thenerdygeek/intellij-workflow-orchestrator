package com.workflow.orchestrator.agent.session

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
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

    // --- updateSessionTitle tests (cross-process-lock migration) ---

    @Test
    fun `updateSessionTitle rewrites task field`() {
        writeIndex(listOf(HistoryItem(id = "s1", ts = 1000, task = "Old title")))

        MessageStateHandler.updateSessionTitle(tempDir, "s1", "Fresh descriptive title")

        assertEquals("Fresh descriptive title", readIndex().single().task)
    }

    @Test
    fun `updateSessionTitle truncates to 200 chars`() {
        writeIndex(listOf(HistoryItem(id = "s1", ts = 1000, task = "Old title")))
        val longTitle = "x".repeat(500)

        MessageStateHandler.updateSessionTitle(tempDir, "s1", longTitle)

        assertEquals(200, readIndex().single().task.length)
    }

    @Test
    fun `updateSessionTitle is no-op for unknown session id`() {
        writeIndex(listOf(HistoryItem(id = "s1", ts = 1000, task = "Old title")))

        MessageStateHandler.updateSessionTitle(tempDir, "unknown", "New title")

        assertEquals("Old title", readIndex().single().task)
    }

    @Test
    fun `updateSessionTitle is no-op when index does not exist`() {
        assertDoesNotThrow {
            MessageStateHandler.updateSessionTitle(tempDir, "s1", "Some title")
        }
    }

    @Test
    fun `updateSessionTitle rejects unsafe session id`() {
        writeIndex(listOf(HistoryItem(id = "s1", ts = 1000, task = "Old title")))

        MessageStateHandler.updateSessionTitle(tempDir, "../escape", "Hacked")

        assertEquals("Old title", readIndex().single().task)
    }

    // --- cleanupOrphanSessions tests ---

    @Test
    fun `cleanupOrphanSessions removes dirs not in index when older than threshold`() {
        writeIndex(listOf(HistoryItem(id = "kept", ts = 1000, task = "Kept")))
        createSessionDir("kept")
        createSessionDir("orphan-old")
        // Age the orphan dir's mtime past the cutoff
        val orphan = File(tempDir, "sessions/orphan-old")
        orphan.setLastModified(System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000) // 60 days

        val removed = MessageStateHandler.cleanupOrphanSessions(tempDir)

        assertEquals(1, removed)
        assertTrue(sessionDirExists("kept"))
        assertFalse(sessionDirExists("orphan-old"))
    }

    @Test
    fun `cleanupOrphanSessions spares recently-modified orphans`() {
        writeIndex(emptyList())
        createSessionDir("recent-orphan")
        // Default mtime is "now" — well within the 30-day threshold

        val removed = MessageStateHandler.cleanupOrphanSessions(tempDir)

        assertEquals(0, removed)
        assertTrue(sessionDirExists("recent-orphan"))
    }

    @Test
    fun `cleanupOrphanSessions returns zero when sessions dir is missing`() {
        // No writeIndex, no createSessionDir
        val removed = MessageStateHandler.cleanupOrphanSessions(tempDir)

        assertEquals(0, removed)
    }

    @Test
    fun `cleanupOrphanSessions spares all entries listed in the index`() {
        writeIndex(listOf(
            HistoryItem(id = "s1", ts = 1000, task = "T1"),
            HistoryItem(id = "s2", ts = 2000, task = "T2"),
        ))
        createSessionDir("s1")
        createSessionDir("s2")
        File(tempDir, "sessions/s1").setLastModified(System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000)
        File(tempDir, "sessions/s2").setLastModified(System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000)

        val removed = MessageStateHandler.cleanupOrphanSessions(tempDir)

        assertEquals(0, removed)
        assertTrue(sessionDirExists("s1"))
        assertTrue(sessionDirExists("s2"))
    }

    @Test
    fun `cleanupOrphanSessions respects custom olderThanMs`() {
        writeIndex(emptyList())
        createSessionDir("orphan")
        File(tempDir, "sessions/orphan").setLastModified(System.currentTimeMillis() - 2000)

        // Threshold of 1 second — orphan is past it
        val removed = MessageStateHandler.cleanupOrphanSessions(tempDir, olderThanMs = 1000)

        assertEquals(1, removed)
        assertFalse(sessionDirExists("orphan"))
    }
}
