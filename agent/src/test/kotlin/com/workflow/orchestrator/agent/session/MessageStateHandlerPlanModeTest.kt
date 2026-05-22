package com.workflow.orchestrator.agent.session

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Regression tests for the plan-mode persistence fix (F1/F2 review findings).
 *
 * Specifically verifies that:
 * - [MessageStateHandler.updateSessionPlanMode] writes [HistoryItem.planModeEnabled]
 *   to the on-disk sessions.json.
 * - The written value survives a handler restart (simulating a JVM restart /
 *   IDE close-and-reopen) and is retrievable via [MessageStateHandler.findHistoryItem].
 * - [MessageStateHandler.findHistoryItem] returns null when the session does not exist.
 * - A session that was never updated defaults to planModeEnabled = false.
 */
class MessageStateHandlerPlanModeTest {

    @TempDir
    lateinit var tempDir: Path

    private fun makeHandler(sessionId: String = "test-session"): MessageStateHandler =
        MessageStateHandler(
            baseDir = tempDir.toFile(),
            sessionId = sessionId,
            taskText = "Test plan-mode persistence",
        )

    // Seed the handler with one UI message so updateGlobalIndex actually writes
    // the index entry (the index is only written when a message triggers a flush).
    private suspend fun seedHandler(handler: MessageStateHandler) {
        handler.addToClineMessages(
            UiMessage(ts = 1_000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "hello")
        )
    }

    @Test
    fun `updateSessionPlanMode writes planModeEnabled=true and survives restart`() = runTest {
        val sid = "plan-mode-session"
        val h1 = makeHandler(sid)
        seedHandler(h1)

        h1.updateSessionPlanMode(true)

        // Simulate restart by constructing a new handler against the same directory.
        val item = MessageStateHandler.findHistoryItem(tempDir.toFile(), sid)
        assertTrue(item?.planModeEnabled == true,
            "planModeEnabled should be true after updateSessionPlanMode(true)")
    }

    @Test
    fun `updateSessionPlanMode writes planModeEnabled=false and survives restart`() = runTest {
        val sid = "act-mode-session"
        val h1 = makeHandler(sid)
        seedHandler(h1)

        // Set true first, then flip back.
        h1.updateSessionPlanMode(true)
        h1.updateSessionPlanMode(false)

        val item = MessageStateHandler.findHistoryItem(tempDir.toFile(), sid)
        assertFalse(item?.planModeEnabled ?: true,
            "planModeEnabled should be false after updateSessionPlanMode(false)")
    }

    @Test
    fun `findHistoryItem returns null when session does not exist`() {
        val result = MessageStateHandler.findHistoryItem(tempDir.toFile(), "nonexistent-session")
        assertNull(result, "findHistoryItem should return null for a missing session")
    }

    @Test
    fun `session without explicit plan-mode update defaults to false`() = runTest {
        val sid = "default-session"
        val h = makeHandler(sid)
        seedHandler(h)
        // Do NOT call updateSessionPlanMode — verify the default is false.
        val item = MessageStateHandler.findHistoryItem(tempDir.toFile(), sid)
        assertFalse(item?.planModeEnabled ?: true,
            "planModeEnabled should default to false when not explicitly set")
    }

    @Test
    fun `planModeEnabled is preserved across regular global index updates`() = runTest {
        val sid = "preserved-session"
        val h = makeHandler(sid)
        seedHandler(h)

        h.updateSessionPlanMode(true)

        // Trigger another index update (e.g., a new UI message saves and flushes).
        h.addToClineMessages(
            UiMessage(ts = 2_000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "second")
        )
        h.saveBoth()

        // The planModeEnabled field must survive the subsequent index rewrite.
        val item = MessageStateHandler.findHistoryItem(tempDir.toFile(), sid)
        assertTrue(item?.planModeEnabled == true,
            "planModeEnabled should survive a subsequent global index update")
    }

    @Test
    fun `isFavorited is preserved when planModeEnabled is updated`() = runTest {
        val sid = "favorite-session"
        val h = makeHandler(sid)
        seedHandler(h)

        // Simulate toggleFavorite via the companion static helper.
        MessageStateHandler.toggleFavorite(tempDir.toFile(), sid)

        // Now update plan mode — isFavorited must not be clobbered.
        h.updateSessionPlanMode(true)

        val item = MessageStateHandler.findHistoryItem(tempDir.toFile(), sid)
        assertTrue(item?.isFavorited == true,
            "isFavorited must not be overwritten when planModeEnabled changes")
        assertTrue(item?.planModeEnabled == true,
            "planModeEnabled must be persisted after the update")
    }
}
