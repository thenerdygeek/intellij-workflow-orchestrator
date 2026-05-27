package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AgentCefPanelAttachmentWiringTest {
    private val src = File("src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt").readText()

    @Test fun `pickAttachment bridge is registered and injected`() {
        assertTrue(src.contains("pickAttachmentQuery"), "query field declared")
        assertTrue(src.contains("window._pickAttachment"), "injected as window._pickAttachment")
    }

    @Test fun `addAttachmentChip and setDropActive push functions exist`() {
        assertTrue(src.contains("__addAttachmentChip") || src.contains("_addAttachmentChip"))
        assertTrue(src.contains("_setDropActive"))
    }

    @Test fun `AttachmentDropTarget is installed on the browser component`() {
        assertTrue(src.contains("AttachmentDropTarget"), "drop target installed in createBrowser")
    }
}
