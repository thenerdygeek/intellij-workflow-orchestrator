package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AgentServiceToolRegistrationWiringContractTest {
    private val src = File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt").readText()

    @Test
    fun `AgentService delegates contributor EP iteration to ToolRegistrationService`() {
        assertTrue(
            src.contains("ToolRegistrationService") && src.contains("contributeExternalTools(registry)"),
            "AgentService must delegate the agentToolContributor EP iteration to ToolRegistrationService",
        )
    }

    @Test
    fun `AgentService no longer iterates the EP inline`() {
        assertFalse(src.contains(".EP_NAME.extensionList"),
            "the EP_NAME.extensionList iteration must live in ToolRegistrationService, not AgentService")
    }
}
