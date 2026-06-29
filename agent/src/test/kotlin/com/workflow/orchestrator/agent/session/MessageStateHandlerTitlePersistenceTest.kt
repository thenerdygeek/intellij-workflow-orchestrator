package com.workflow.orchestrator.agent.session

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Regression tests for the header/History title divergence (BUG-TITLE-1).
 *
 * The chat header (in-memory in AgentController) showed the generated title, but the
 * History list reads [HistoryItem.task] from sessions.json — and a SECOND writer, the
 * periodic [MessageStateHandler.updateGlobalIndex] flush, kept rewriting that field
 * from the constructor `taskText`, reverting the generated title. The fix makes the
 * live [MessageStateHandler.updateTitle] the single source of truth: it updates the
 * in-memory `taskText` (now mutable) so subsequent index flushes carry the generated
 * title, and sanitizes it so raw assistant prose can never persist as a title.
 */
class MessageStateHandlerTitlePersistenceTest {

    @TempDir
    lateinit var tempDir: Path

    private fun makeHandler(sessionId: String = "s1", taskText: String = "original user message") =
        MessageStateHandler(
            baseDir = tempDir.toFile(),
            sessionId = sessionId,
            taskText = taskText,
        )

    private suspend fun seed(handler: MessageStateHandler) {
        handler.addToClineMessages(
            UiMessage(ts = 1_000L, type = UiMessageType.SAY, say = UiSay.USER_MESSAGE, text = "hello")
        )
    }

    private fun indexTask(sessionId: String): String? =
        MessageStateHandler.findHistoryItem(tempDir.toFile(), sessionId)?.task

    @Test
    fun `updateTitle persists the generated title to the History index`() = runTest {
        val h = makeHandler()
        seed(h)
        h.saveBoth() // flush the seed → index.task == original
        assertEquals("original user message", indexTask("s1"))

        h.updateTitle("Fix auth token expiry in LoginService")

        assertEquals("Fix auth token expiry in LoginService", indexTask("s1"))
    }

    @Test
    fun `generated title survives a subsequent global index flush (no revert)`() = runTest {
        val h = makeHandler()
        seed(h)

        h.updateTitle("Fix auth token expiry in LoginService")
        assertEquals("Fix auth token expiry in LoginService", indexTask("s1"))

        // Simulate the periodic flush that USED to revert the History card to the
        // original first-message text. With the fix, taskText now holds the generated
        // title, so the index keeps it.
        h.addToClineMessages(
            UiMessage(ts = 2_000L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "more work")
        )
        h.saveBoth()

        assertEquals(
            "Fix auth token expiry in LoginService",
            indexTask("s1"),
            "the generated title must NOT revert to the original first-message text",
        )
    }

    @Test
    fun `updateTitle sanitizes raw assistant prose at the persistence boundary`() = runTest {
        val h = makeHandler()
        seed(h)

        // The exact shape QA saw leak into a History card title.
        h.updateTitle(
            "<thinking>\nLet me analyze.\n</thinking> Here is the first analysis block: ```kotlin\nfun foo(){}\n```"
        )

        val task = indexTask("s1")!!
        assertEquals("Here is the first analysis block:", task)
        assertFalse(task.contains("<thinking>"), "thinking tag must never persist as a title")
        assertFalse(task.contains("```"), "code fence must never persist as a title")
    }

    @Test
    fun `updateTitle is a no-op when the sanitized title is blank`() = runTest {
        val h = makeHandler()
        seed(h)
        h.saveBoth()
        assertEquals("original user message", indexTask("s1"))

        h.updateTitle("```\n   \n```") // collapses to blank after sanitization

        assertEquals(
            "original user message",
            indexTask("s1"),
            "a blank sanitized title must keep the prior title",
        )
    }

    @Test
    fun `updateTitle truncates to 200 chars`() = runTest {
        val h = makeHandler()
        seed(h)

        h.updateTitle("x".repeat(500))

        assertEquals(200, indexTask("s1")!!.length)
    }

    @Test
    fun `updateTitle updates the in-memory taskText getter`() = runTest {
        val h = makeHandler()
        seed(h)

        h.updateTitle("Crisp descriptive title")

        assertEquals("Crisp descriptive title", h.taskText)
    }

    @Test
    fun `updateTitle preserves isFavorited`() = runTest {
        val h = makeHandler()
        seed(h)
        h.saveBoth()
        MessageStateHandler.toggleFavorite(tempDir.toFile(), "s1")

        h.updateTitle("New crisp title")

        val item = MessageStateHandler.findHistoryItem(tempDir.toFile(), "s1")
        assertTrue(item?.isFavorited == true, "isFavorited must survive a title update")
        assertEquals("New crisp title", item?.task)
    }
}
