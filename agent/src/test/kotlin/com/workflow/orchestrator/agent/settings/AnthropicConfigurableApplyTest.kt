package com.workflow.orchestrator.agent.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for the native (Anthropic-direct) provider wiring added in Phase 4a Task 13:
 *  - the settings page ([AgentParentConfigurable]) persists `llmProvider` / `anthropicModel` /
 *    `anthropicEffort` / `anthropicThinkingEnabled`, and
 *  - the in-chat model picker ([com.workflow.orchestrator.agent.ui.AgentController.loadModelList])
 *    branches on the native provider and drives `anthropicModel`.
 *
 * **Why no `BasePlatformTestCase` here.** `AgentParentConfigurable.apply()` writes into
 * `AgentSettings.getInstance(project)` / `ConnectionSettings.getInstance()` / `CredentialStore`,
 * all of which require live IntelliJ platform services. The `:agent` module already documents
 * (agent/CLAUDE.md → "BasePlatformTestCase infra") that a SECOND `BasePlatformTestCase` class in
 * this test JVM collides with the existing `EditFilePersistenceFixtureTest` on the headless
 * "Indexing timeout" (issue #51) and fails CI — which is exactly why the sibling Phase 4a tests
 * ([com.workflow.orchestrator.agent.brain.BrainFactoryProviderBranchTest]) use the source-text
 * pattern. We follow that precedent: the field-persistence assertions run against the pure
 * [applyAnthropicProviderSettings] seam (which `apply()` delegates to), and the picker-branching
 * assertions are source-text contracts. The task brief explicitly permits the source-text contract.
 */
class AnthropicConfigurableApplyTest {

    private val controllerSrc: String by lazy {
        File("src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt").readText()
    }

    // ── apply() persistence (pure seam) ───────────────────────────────────────

    @Test
    fun `apply persists provider model and effort`() {
        val state = AgentSettings.State()
        applyAnthropicProviderSettings(
            state,
            AnthropicProviderSettingsSnapshot(
                llmProvider = "anthropic",
                anthropicModel = "claude-sonnet-4-6",
                anthropicEffort = "xhigh",
                anthropicThinkingEnabled = true,
            )
        )
        assertEquals("anthropic", state.llmProvider)
        assertEquals("claude-sonnet-4-6", state.anthropicModel)
        assertEquals("xhigh", state.anthropicEffort)
    }

    @Test
    fun `apply persists the thinking toggle in both directions`() {
        val off = AgentSettings.State()
        applyAnthropicProviderSettings(
            off,
            AnthropicProviderSettingsSnapshot("anthropic", "claude-opus-4-8", "high", anthropicThinkingEnabled = false)
        )
        assertFalse(off.anthropicThinkingEnabled, "thinking toggle OFF must persist")

        val on = AgentSettings.State()
        applyAnthropicProviderSettings(
            on,
            AnthropicProviderSettingsSnapshot("anthropic", "claude-opus-4-8", "high", anthropicThinkingEnabled = true)
        )
        assertTrue(on.anthropicThinkingEnabled, "thinking toggle ON must persist")
    }

    @Test
    fun `apply can switch back to the sourcegraph provider`() {
        val state = AgentSettings.State()
        applyAnthropicProviderSettings(
            state,
            AnthropicProviderSettingsSnapshot("sourcegraph", "claude-opus-4-8", "high", anthropicThinkingEnabled = true)
        )
        assertEquals("sourcegraph", state.llmProvider)
    }

    // ── in-chat picker branch (source-text contract) ──────────────────────────

    @Test
    fun `loadModelList branches on the anthropic provider`() {
        assertTrue(
            controllerSrc.contains("loadModelList"),
            "AgentController must define loadModelList"
        )
        assertTrue(
            controllerSrc.contains("llmProvider == \"anthropic\""),
            "loadModelList must branch on llmProvider == \"anthropic\" before the Sourcegraph fetch"
        )
        val branchIdx = controllerSrc.indexOf("loadAnthropicModelList()")
        val sgGuardIdx = controllerSrc.indexOf("sourcegraphUrl.isBlank()")
        assertTrue(
            branchIdx in 0 until sgGuardIdx,
            "the native branch must dispatch to loadAnthropicModelList() BEFORE the blank-Sourcegraph-URL " +
                "guard (the native path must not require a Sourcegraph URL)"
        )
    }

    @Test
    fun `native picker is sourced from the AnthropicModelCatalog`() {
        assertTrue(
            controllerSrc.contains("AnthropicModelCatalog.MODELS"),
            "loadAnthropicModelList must populate the picker from AnthropicModelCatalog.MODELS, not a /models fetch"
        )
    }

    @Test
    fun `native picker drives anthropicModel not sourcegraphChatModel`() {
        // changeModel and selectedModelId must read/write anthropicModel on the native path.
        assertTrue(
            controllerSrc.contains("state.anthropicModel = model"),
            "changeModel must write the selected id to anthropicModel on the native path"
        )
        assertTrue(
            controllerSrc.contains("state.llmProvider == \"anthropic\""),
            "changeModel / selectedModelId must branch on the native provider"
        )
    }
}
