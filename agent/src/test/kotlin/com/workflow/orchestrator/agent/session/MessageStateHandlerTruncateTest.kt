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
}
