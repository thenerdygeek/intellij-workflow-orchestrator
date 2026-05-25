package com.workflow.orchestrator.agent.session

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Verifies the global sessions.json retention cap introduced by agent-runtime:F-16.
 *
 * The cap (MAX_GLOBAL_INDEX_SIZE) prevents unbounded growth of sessions.json for
 * users with very long IDE sessions.  Favourited sessions must always survive
 * pruning regardless of their position in the list.
 */
class SessionIndexPruneTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * Build a handler that will write its session ID into sessions.json when
     * any message is persisted.
     */
    private fun handler(sessionId: String, taskText: String = "task"): MessageStateHandler =
        MessageStateHandler(
            baseDir = tempDir.toFile(),
            sessionId = sessionId,
            taskText = taskText,
        )

    @Test
    fun `global index is pruned to MAX_GLOBAL_INDEX_SIZE when cap is exceeded`() = runTest {
        val cap = MessageStateHandler.MAX_GLOBAL_INDEX_SIZE
        // Write cap + 5 sessions.
        for (i in 1..(cap + 5)) {
            val h = handler("session-$i", "task $i")
            h.addToClineMessages(
                UiMessage(ts = i.toLong(), type = UiMessageType.SAY, say = UiSay.USER_MESSAGE, text = "msg $i")
            )
        }

        val items = MessageStateHandler.loadGlobalIndex(tempDir.toFile())
        assertTrue(items.size <= cap + 5, "sanity: at most cap+5 entries created")
        assertTrue(items.size <= cap, "after pruning, index must not exceed MAX_GLOBAL_INDEX_SIZE")
    }

    @Test
    fun `favourited sessions survive pruning`() = runTest {
        val cap = MessageStateHandler.MAX_GLOBAL_INDEX_SIZE
        // Write cap - 1 sessions so the index is just below the limit.
        for (i in 1..(cap - 1)) {
            val h = handler("session-$i", "task $i")
            h.addToClineMessages(
                UiMessage(ts = i.toLong(), type = UiMessageType.SAY, say = UiSay.USER_MESSAGE, text = "msg $i")
            )
        }
        // The oldest session in the list is "session-1" (added first, prepend puts it last).
        // Favourite it before the list overflows so it ends up in existingItems when pruning fires.
        val oldSessionId = "session-1"
        MessageStateHandler.toggleFavorite(tempDir.toFile(), oldSessionId)

        // Now push the list beyond the cap by writing 10 more sessions.
        for (i in cap..(cap + 9)) {
            val h = handler("session-$i", "task $i")
            h.addToClineMessages(
                UiMessage(ts = i.toLong(), type = UiMessageType.SAY, say = UiSay.USER_MESSAGE, text = "msg $i")
            )
        }

        val items = MessageStateHandler.loadGlobalIndex(tempDir.toFile())
        val ids = items.map { it.id }.toSet()
        assertTrue(oldSessionId in ids, "favourited session must survive pruning")
        assertTrue(items.first { it.id == oldSessionId }.isFavorited, "favourite flag must be preserved")
    }
}
