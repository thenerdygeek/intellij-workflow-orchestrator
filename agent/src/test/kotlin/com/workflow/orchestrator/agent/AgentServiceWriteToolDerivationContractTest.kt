package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class AgentServiceWriteToolDerivationContractTest {
    private val src = File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt").readText()

    @Test
    fun `writeToolNames is derived from the registry by isMutating`() {
        assertTrue(
            src.contains("registry.allTools()") && src.contains("isMutating"),
            "AgentService.writeToolNames must derive from registry.allTools() filtered by isMutating " +
                "so B-contributed write tools are schema-filtered in plan mode",
        )
    }
}
