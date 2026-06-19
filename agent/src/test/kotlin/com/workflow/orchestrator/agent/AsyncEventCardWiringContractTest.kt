package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AsyncEventCardWiringContractTest {
    private val svc = File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt").readText()
    private val ctl = File("src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt").readText()
    private val bcc = File("src/main/kotlin/com/workflow/orchestrator/agent/tools/background/BackgroundCompletionCoordinator.kt").readText()

    @Test
    fun `appendAsyncEventCardToSession persists UI-only via active handler`() {
        val fn = svc.substringAfter("fun appendAsyncEventCardToSession").substringBefore("\n    fun ")
        assertTrue(fn.contains("activeMessageStateHandler"), "must use the active handler")
        assertTrue(fn.contains("addToClineMessages"), "must write ui_messages only")
        assertTrue(!fn.contains("addToApiConversationHistory"), "must NOT touch api history")
        assertTrue(fn.contains("UiSay.ASYNC_EVENT") || fn.contains("ASYNC_EVENT"))
    }

    @Test
    fun `controller pushAsyncEventCard persists then live-pushes only for the viewed session`() {
        val fn = ctl.substringAfter("fun pushAsyncEventCard").substringBefore("\n    private fun ")
        assertTrue(fn.contains("appendAsyncEventCardToSession"), "persist via service")
        assertTrue(fn.contains("viewedSessionId"), "live push gated on viewed session")
        assertTrue(fn.contains("_pushAsyncEventCard") || fn.contains("pushAsyncEventCard"))
    }

    @Test
    fun `subscribeToBackgroundCompletions uses pushAsyncEventCard not appendStatus`() {
        val fn = ctl.substringAfter("fun subscribeToBackgroundCompletions").substringBefore("\n    private fun ")
        assertTrue(fn.contains("pushAsyncEventCard("), "must push a card, not a status bubble")
        assertTrue(!fn.contains("appendStatus("), "must NOT use the old appendStatus bubble")
    }

    @Test
    fun `BackgroundCompletionCoordinator meta contains card key`() {
        assertTrue(bcc.contains("\"card\" to"), "coordinator QueuedMessage meta must carry 'card' for resume synthesis")
    }
}
