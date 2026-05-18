package com.workflow.orchestrator.agent.session

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MessageStateHandlerTruncateTest {

    @Test
    fun `truncateMessagesAtTs drops uiMessages and apiHistory at and after the target ts`(@TempDir tmp: java.nio.file.Path) = runTest {
        val baseDir = tmp.toFile()
        val handler = MessageStateHandler(baseDir = baseDir, sessionId = "sess-1", taskText = "test task")

        handler.addToClineMessages(UiMessage(ts = 100L, type = UiMessageType.SAY, say = UiSay.USER_MESSAGE, text = "u1"))
        handler.addToClineMessages(UiMessage(ts = 110L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "a1"))
        handler.addToClineMessages(UiMessage(ts = 200L, type = UiMessageType.SAY, say = UiSay.USER_MESSAGE, text = "u2"))
        handler.addToClineMessages(UiMessage(ts = 210L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "a2"))
        handler.addToApiConversationHistory(ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("u1-api"))))
        handler.addToApiConversationHistory(ApiMessage(role = ApiRole.ASSISTANT, content = listOf(ContentBlock.Text("a1-api"))))
        handler.addToApiConversationHistory(ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("u2-api"))))
        handler.addToApiConversationHistory(ApiMessage(role = ApiRole.ASSISTANT, content = listOf(ContentBlock.Text("a2-api"))))

        handler.truncateMessagesAtTs(targetMessageTs = 200L, droppedApiCount = 2)

        // Verify via the disk loaders (companion-object statics)
        val sessionDir = File(baseDir, "sessions/sess-1")
        val keptUi = MessageStateHandler.loadUiMessages(sessionDir)
        val keptApi = MessageStateHandler.loadApiHistory(sessionDir)

        assertEquals(2, keptUi.size, "UI messages with ts < 200 should remain")
        assertEquals(listOf(100L, 110L), keptUi.map { it.ts })
        assertEquals(2, keptApi.size, "Trailing 2 api history entries should be dropped")
    }

    @Test
    fun `truncateMessagesAtTs preserves correctness when STEERING_RECEIVED uiMessages interleave (steering must NOT add to apiHistory)`(@TempDir tmp: java.nio.file.Path) = runTest {
        // Simulates the post-steering state: assistant responses ARE persisted to apiHistory,
        // but steering messages are in-memory only (contextManager.addUserMessage). The user-side
        // STEERING_RECEIVED UiMessages exist for chat-history display but DON'T appear in apiHistory.
        //
        // If steering ever starts writing to apiHistory, this test will fail because the api-side
        // count will diverge from the user-message conversationHistoryIndex.
        val baseDir = tmp.toFile()
        val handler = MessageStateHandler(baseDir = baseDir, sessionId = "sess-steer", taskText = "test")

        // Turn 1: user → 1 api entry, then user UiMessage records index 0
        handler.addToApiConversationHistory(ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("turn 1"))))
        handler.addToClineMessages(UiMessage(ts = 100L, type = UiMessageType.SAY, say = UiSay.USER_MESSAGE, text = "turn 1"))
        // Turn 1: assistant → 2 api entries total
        handler.addToApiConversationHistory(ApiMessage(role = ApiRole.ASSISTANT, content = listOf(ContentBlock.Text("ack 1"))))
        handler.addToClineMessages(UiMessage(ts = 110L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "ack 1"))

        // Steering at ts=120 — only the UI message lands; apiHistory does NOT grow.
        handler.addToClineMessages(UiMessage(ts = 120L, type = UiMessageType.SAY, say = UiSay.STATUS, text = "steering"))

        // Turn 2: assistant addresses steering → 3 api entries total
        handler.addToApiConversationHistory(ApiMessage(role = ApiRole.ASSISTANT, content = listOf(ContentBlock.Text("ack 2"))))
        handler.addToClineMessages(UiMessage(ts = 125L, type = UiMessageType.SAY, say = UiSay.TEXT, text = "ack 2"))

        // Turn 3: user follow-up → 4 api entries total
        handler.addToApiConversationHistory(ApiMessage(role = ApiRole.USER, content = listOf(ContentBlock.Text("turn 3"))))
        handler.addToClineMessages(UiMessage(ts = 200L, type = UiMessageType.SAY, say = UiSay.USER_MESSAGE, text = "turn 3"))

        // Snapshot before truncate
        org.junit.jupiter.api.Assertions.assertEquals(4, handler.getApiConversationHistory().size)

        // Revert to turn 3 (ts 200). The turn-3 user UiMessage was added when apiHistory.size was 4
        // (immediately after addToApiConversationHistory(USER, "turn 3")), so its
        // conversationHistoryIndex is 3 (= apiHistory.size - 1 at that moment).
        // keepApiCount = 3, droppedApiCount = 4 - 3 = 1 → drop the turn-3 user api entry.
        handler.truncateMessagesAtTs(200L, droppedApiCount = 1)

        val sessionDir = File(baseDir, "sessions/sess-steer")
        val keptUi = MessageStateHandler.loadUiMessages(sessionDir)
        val keptApi = MessageStateHandler.loadApiHistory(sessionDir)

        org.junit.jupiter.api.Assertions.assertEquals(
            listOf(100L, 110L, 120L, 125L), keptUi.map { it.ts },
            "All UI messages BEFORE the turn-3 user message must remain — including STEERING_RECEIVED at ts 120"
        )
        org.junit.jupiter.api.Assertions.assertEquals(
            3, keptApi.size,
            "API history should keep the original user+assistant+assistant chain from turns 1 and 2 — steering did NOT add to apiHistory"
        )
    }
}
