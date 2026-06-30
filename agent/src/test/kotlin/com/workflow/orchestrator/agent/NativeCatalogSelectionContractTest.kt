package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text contract: `AgentService` must select the native Anthropic catalog
 * ([com.workflow.orchestrator.core.ai.AnthropicModelCatalogService]) at the
 * catalog-selection seams when `AgentSettings.llmProvider == "anthropic"`.
 *
 * Phase 4a Task 3 — without this branch, native runs key the Sourcegraph catalog on
 * a blank URL → null window → the 90K floor → `claude-opus-4-8` (1M) compacts ~11x
 * too early. Reads the live source (module dir is the test CWD), matching the pattern
 * used by [AgentServiceEffectiveWindowWiringTest].
 */
class NativeCatalogSelectionContractTest {

    private val src = File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt").readText()

    @Test
    fun `agent references the native Anthropic catalog service`() {
        assertTrue(
            src.contains("AnthropicModelCatalogService"),
            "AgentService must wire AnthropicModelCatalogService at the catalog-selection seams",
        )
    }

    @Test
    fun `agent branches on the anthropic provider literal`() {
        assertTrue(
            Regex("llmProvider\\s*==\\s*\"anthropic\"").containsMatchIn(src),
            "AgentService must gate native catalog selection on llmProvider == \"anthropic\"",
        )
    }

    @Test
    fun `effective context window lookup has a native branch`() {
        // The window-sizing path runs through EffectiveContextWindow.windowLookup first;
        // the native branch must live inside that lookup so compaction honors the 1M window.
        assertTrue(
            src.contains("windowLookup"),
            "AgentService must still construct the EffectiveContextWindow windowLookup",
        )
    }
}
