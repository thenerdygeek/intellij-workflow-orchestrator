package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-text pins for BUG-TITLE-1 (header/History title divergence + raw-assistant
 * leak). The title flow lives in non-unit-instantiable code paths (AgentController's
 * JCEF-bound methods, AgentService.executeTask's session bootstrap), so the contract is
 * pinned by source text — consistent with the existing AgentController*SourceTest
 * precedent.
 */
class AgentControllerTitleSanitizeSourceTest {

    private val controllerSrc = java.io.File(
        "src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt"
    ).readText()

    private val serviceSrc = java.io.File(
        "src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt"
    ).readText()

    @Test
    fun `deriveInitialTitle sanitizes the provisional title`() {
        val fn = controllerSrc.substringAfter("private fun deriveInitialTitle(")
            .substringBefore("\n    private fun ")
        assertTrue(
            fn.contains("TitleSanitizer.sanitize(task)"),
            "deriveInitialTitle must run the user message through TitleSanitizer"
        )
    }

    @Test
    fun `completion-eval title is sanitized before header and persist use it`() {
        val fn = controllerSrc.substringAfter("private fun evaluateTitleOnCompletion(")
            .substringBefore("\n    private fun ")
        // The same sanitized value must feed BOTH the header and the persisted index.
        // Whitespace-robust: collapse all runs of whitespace before matching.
        val collapsed = fn.replace(Regex("\\s+"), " ")
        assertTrue(
            collapsed.contains("TitleSanitizer .sanitize(rawNewTitle)") ||
                collapsed.contains("TitleSanitizer.sanitize(rawNewTitle)"),
            "the Haiku result must be sanitized before use"
        )
        assertTrue(
            fn.contains("service.updateSessionTitle(it, newTitle)"),
            "the persist call must use the sanitized newTitle"
        )
        assertTrue(
            fn.contains("dashboard.setSessionTitleAnimated(newTitle)"),
            "the header must use the SAME sanitized newTitle as the persist"
        )
    }

    @Test
    fun `resolvedTaskText never seeds the title from the first assistant UiSay-TEXT message`() {
        // The leak source: deriving the index title from the first assistant prose
        // (UiSay.TEXT) carried raw <thinking>/code into the History card.
        assertFalse(
            serviceSrc.contains("existingUi.firstOrNull { it.say == UiSay.TEXT }?.text?.take(200)"),
            "resolvedTaskText must NOT pick the first assistant UiSay.TEXT message"
        )
    }

    @Test
    fun `resolvedTaskText seeds from the persisted title or the first USER message, sanitized`() {
        val region = serviceSrc.substringAfter("val resolvedTaskText = if (existingUi.isNotEmpty())")
            .substringBefore("} else {")
        assertTrue(
            region.contains("findHistoryItem(sessionBaseDir, sid)?.task"),
            "resolvedTaskText must prefer the title persisted by a prior turn"
        )
        assertTrue(
            region.contains("it.say == UiSay.USER_MESSAGE"),
            "resolvedTaskText must fall back to the first USER message, not the assistant message"
        )
        assertTrue(
            region.contains("TitleSanitizer.sanitize("),
            "resolvedTaskText must be sanitized at the boundary"
        )
    }

    @Test
    fun `live updateSessionTitle routes through the active handler so taskText stays in sync`() {
        val fn = serviceSrc.substringAfter("fun updateSessionTitle(sessionId: String, title: String) {")
            .substringBefore("\n    /**")
        assertTrue(
            fn.contains("handler.sessionId == sessionId") && fn.contains("handler.updateTitle(title)"),
            "the live session must route through MessageStateHandler.updateTitle"
        )
    }
}
