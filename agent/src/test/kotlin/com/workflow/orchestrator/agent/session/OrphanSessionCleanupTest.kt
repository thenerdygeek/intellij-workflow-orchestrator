package com.workflow.orchestrator.agent.session

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Verifies cleanupOrphanSessions timestamp-resolution logic (agent-runtime:F-23).
 *
 * The fix prefers the explicit `ts` from ui_messages.json over filesystem mtime,
 * which is unreliable under backup tools (Time Machine, rsync) and clock-skew.
 */
class OrphanSessionCleanupTest {

    @TempDir
    lateinit var tempDir: Path

    private val baseDir get() = tempDir.toFile()
    private val sessionsRoot get() = File(baseDir, "sessions")

    /** Create a minimal sessions.json so loadGlobalIndex succeeds. */
    private fun seedIndex(vararg knownIds: String) {
        baseDir.mkdirs()
        val items = knownIds.map { id ->
            HistoryItem(id = id, ts = System.currentTimeMillis(), task = "task $id")
        }
        AtomicFileWriter.write(
            File(baseDir, "sessions.json"),
            kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true
            }.encodeToString(kotlinx.serialization.builtins.ListSerializer(HistoryItem.serializer()), items)
        )
    }

    private fun makeOrphanDir(dirName: String): File {
        val dir = File(sessionsRoot, dirName)
        dir.mkdirs()
        return dir
    }

    // ── ui_messages.json timestamp takes precedence ───────────────────────

    @Test
    fun `session with recent ui_messages ts is kept despite old directory mtime`() = runTest {
        seedIndex("known-session")

        val orphanDir = makeOrphanDir("orphan-recent-ui")
        // Write a ui_messages.json with a very recent timestamp
        val recentTs = System.currentTimeMillis()
        val uiMsg = UiMessage(ts = recentTs, type = UiMessageType.SAY, say = UiSay.USER_MESSAGE, text = "hello")
        AtomicFileWriter.write(
            File(orphanDir, "ui_messages.json"),
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
                .encodeToString(kotlinx.serialization.builtins.ListSerializer(UiMessage.serializer()), listOf(uiMsg))
        )
        // Backdate the directory mtime to simulate a backup-touched dir
        orphanDir.setLastModified(System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000)

        val removed = MessageStateHandler.cleanupOrphanSessions(baseDir, olderThanMs = 30L * 24 * 60 * 60 * 1000)
        assertEquals(0, removed, "session with recent ui_messages ts must NOT be deleted despite old dir mtime")
        assertTrue(orphanDir.exists(), "orphan dir must still exist")
    }

    @Test
    fun `session with old ui_messages ts is removed`() = runTest {
        seedIndex("known-session")

        val orphanDir = makeOrphanDir("orphan-old-ui")
        val oldTs = System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000 // 60 days ago
        val uiMsg = UiMessage(ts = oldTs, type = UiMessageType.SAY, say = UiSay.USER_MESSAGE, text = "old")
        AtomicFileWriter.write(
            File(orphanDir, "ui_messages.json"),
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
                .encodeToString(kotlinx.serialization.builtins.ListSerializer(UiMessage.serializer()), listOf(uiMsg))
        )

        val removed = MessageStateHandler.cleanupOrphanSessions(baseDir, olderThanMs = 30L * 24 * 60 * 60 * 1000)
        assertEquals(1, removed, "session with 60-day-old ui_messages ts must be deleted")
        assertFalse(orphanDir.exists(), "orphan dir must be removed")
    }

    @Test
    fun `known session is never deleted`() = runTest {
        seedIndex("my-session")
        val knownDir = makeOrphanDir("my-session")
        // Even with a very old mtime it must not be deleted
        knownDir.setLastModified(System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000)

        val removed = MessageStateHandler.cleanupOrphanSessions(baseDir, olderThanMs = 30L * 24 * 60 * 60 * 1000)
        assertEquals(0, removed, "known (indexed) session must never be deleted")
        assertTrue(knownDir.exists())
    }
}
