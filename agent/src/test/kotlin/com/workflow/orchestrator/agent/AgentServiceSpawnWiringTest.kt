package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Test

class AgentServiceSpawnWiringTest {

    @Test
    fun `AgentService passes parity params to SpawnAgentTool construction`() {
        val src = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt"
        ).readText()
        // Find each SpawnAgentTool(...) construction block and verify it passes
        // all 9 parity params.
        val blocks = Regex("""SpawnAgentTool\s*\(([\s\S]*?)^\s*\)""", RegexOption.MULTILINE)
            .findAll(src)
            .map { it.groupValues[1] }
            .toList()
        require(blocks.isNotEmpty()) { "Expected at least one SpawnAgentTool(...) construction site." }
        listOf(
            "outputSpiller",
            "attachmentStoreProvider",
            "onCompactionState",
            "fallbackManager",
            "brainFactory",
            "cachedFallbackChain",
            "onRetry",
            "onModelSwitch",
            "modelCatalogService",
        ).forEach { name ->
            blocks.forEachIndexed { i, block ->
                assert("$name = " in block) {
                    "AgentService construction site #${i + 1} must pass `$name = ...` to SpawnAgentTool. Block:\n$block"
                }
            }
        }
    }
}
