package com.workflow.orchestrator.agent.tools.builtin

import org.junit.jupiter.api.Test

class SpawnAgentToolParityTest {

    @Test
    fun `SpawnAgentTool forwards parity params to every SubagentRunner construction`() {
        val src = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt"
        ).readText()

        val expectedFields = listOf(
            "outputSpiller", "attachmentStoreProvider", "onCompactionState",
            "brainFactory", "cachedFallbackChain",
            "onRetry", "onModelSwitch", "modelCatalogService",
        )

        // Each SubagentRunner(...) block in this file MUST forward every parity field.
        val runnerBlocks = Regex("""SubagentRunner\s*\(([\s\S]*?)^\s*\)""", RegexOption.MULTILINE)
            .findAll(src)
            .map { it.groupValues[1] }
            .toList()
        require(runnerBlocks.size >= 2) { "Expected at least 2 SubagentRunner(...) construction sites." }

        runnerBlocks.forEachIndexed { i, block ->
            expectedFields.forEach { field ->
                assert("$field = $field" in block) {
                    "SubagentRunner construction site #${i + 1} is missing `$field = $field`. Block:\n$block"
                }
            }
        }
    }
}
