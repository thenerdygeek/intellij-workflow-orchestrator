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

    @Test
    fun `AgentService logs the agentToolContributor smoke oracle`() {
        // P0-3 oracle: the two-plugin runIde smoke greps the IDE log for the
        // "[agentToolContributor] … contributed tools: …" line to confirm B's CompanyBToolContributor
        // ran and its tool reached the registry. Pin both load-bearing literals.
        assertTrue(
            src.contains("[agentToolContributor]") && src.contains("contributed tools"),
            "AgentService must log the [agentToolContributor] … contributed tools oracle the smoke greps",
        )
    }

    @Test
    fun `AgentService logs the tool-registration count smoke oracle`() {
        // P0-5 oracle: the smoke greps the "registered N core + M deferred tools" summary to confirm
        // the registry populated (and contributed tools bumped the core count).
        assertTrue(
            src.contains("registered ") && src.contains("core") && src.contains("deferred tools"),
            "AgentService must log the \"registered N core + M deferred tools\" count oracle the smoke greps",
        )
    }
}
