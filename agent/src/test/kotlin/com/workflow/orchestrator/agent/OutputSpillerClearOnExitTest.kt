package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Source-text pin for the _outputSpiller / activeAttachmentStore finally-clear
 * introduced by agent-runtime:F-25.
 *
 * A stale ToolOutputSpiller pointing at a dead session's tool-output directory
 * would cause subsequent tool calls (between task-end and next resetForNewChat)
 * to cross-contaminate the previous session's disk storage.
 */
class OutputSpillerClearOnExitTest {

    private val src: String by lazy {
        (java.io.File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt")
            .takeIf { it.exists() }
            ?: java.io.File("agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt"))
            .readText()
    }

    @Test
    fun `_outputSpiller is cleared to null in the executeTask finally block (source-text pin)`() {
        // The pattern `_outputSpiller = null` must appear inside the finally { } block
        // of executeTask's launched coroutine, not only inside resetForNewChat.
        val text = src
        val finallyIdx = text.indexOf("} finally {")
        assertTrue(finallyIdx >= 0, "executeTask launched coroutine must have a finally block")
        // At least one nulling of _outputSpiller must appear AFTER the finally marker
        val spillerClearIdx = text.indexOf("_outputSpiller = null", finallyIdx)
        assertTrue(
            spillerClearIdx > finallyIdx,
            "_outputSpiller must be cleared inside the finally block (F-25)"
        )
    }

    @Test
    fun `activeAttachmentStore is cleared to null in the executeTask finally block (source-text pin)`() {
        val text = src
        val finallyIdx = text.indexOf("} finally {")
        assertTrue(finallyIdx >= 0)
        val storeClearIdx = text.indexOf("activeAttachmentStore = null", finallyIdx)
        assertTrue(
            storeClearIdx > finallyIdx,
            "activeAttachmentStore must be cleared inside the finally block (F-25)"
        )
    }
}
