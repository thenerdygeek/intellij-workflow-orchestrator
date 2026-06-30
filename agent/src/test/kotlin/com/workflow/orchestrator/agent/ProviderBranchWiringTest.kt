package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text contracts for Task 12: provider-branch wiring (C2 / I1 / I2 / I4).
 *
 * All four assertions use proximity regexes that tie the native Anthropic value to its guard
 * so a trivially-passing whole-file containsMatchIn cannot satisfy them without the actual
 * branch being present.
 *
 * These are source-text contracts — [AgentService] and [SpawnAgentTool] are not
 * unit-instantiable without the full IntelliJ platform, and a [BasePlatformTestCase]
 * second-class collision would cause CI "Indexing timeout" failures (documented trap in
 * agent/CLAUDE.md).
 */
class ProviderBranchWiringTest {

    private val spawn: String by lazy {
        File("src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt")
            .readText()
    }

    private val agent: String by lazy {
        File("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt").readText()
    }

    // ── C2: sub-agent default model ───────────────────────────────────────────

    /**
     * When native Anthropic is active, the sub-agent default model must come from the static
     * [AnthropicModelCatalog.defaultSubagentModel()] (returns a bare model id such as
     * "claude-sonnet-4-6"), not from [ModelCache.pickSonnetNonThinking] which produces a
     * Sourcegraph-formatted ref that 400s on api.anthropic.com.
     *
     * Commit 732fd3d59 moved the call OUT of SpawnAgentTool (to avoid a mock-Project trap in
     * SpawnAgentToolTest) INTO AgentService, which now pushes the resolved bare model id via
     * [spawnAgentTool.nativeSubagentDefaultModel]. Assertions are updated accordingly:
     *  - [AgentService] contains the catalog call guarded by the llmProvider check.
     *  - [SpawnAgentTool] references the pushed field [nativeSubagentDefaultModel].
     *
     * Proximity regex: the llmProvider guard must appear within 300 chars of the catalog call
     * in AgentService so removing the guard causes this assertion to fail.
     */
    @Test
    fun `subagent default model uses anthropic catalog on native`() {
        // The catalog call now lives in AgentService (pushed to SpawnAgentTool via nativeSubagentDefaultModel).
        assertTrue(
            agent.contains("AnthropicModelCatalog.defaultSubagentModel()"),
            "AgentService must call AnthropicModelCatalog.defaultSubagentModel() for the native Anthropic path"
        )
        // Site-specific proximity in AgentService: guard and catalog call are co-located.
        assertTrue(
            Regex("""llmProvider\s*==\s*"anthropic"[\s\S]{0,300}AnthropicModelCatalog\.defaultSubagentModel\(\)""")
                .containsMatchIn(agent),
            "AnthropicModelCatalog.defaultSubagentModel() must be guarded by " +
                "llmProvider == \"anthropic\" within 300 chars in AgentService — they cannot appear in disjoint locations"
        )
        // SpawnAgentTool must reference the field pushed by AgentService (not call the catalog directly).
        assertTrue(
            spawn.contains("nativeSubagentDefaultModel"),
            "SpawnAgentTool must reference nativeSubagentDefaultModel (the field pushed by AgentService)"
        )
    }

    // ── I1: OFFLINE probe URL ─────────────────────────────────────────────────

    /**
     * The OFFLINE probe URL must switch from the Sourcegraph URL to the Anthropic API URL when
     * running on the native provider so [NetworkStateService.checkNow] probes the right host.
     *
     * Proximity regex: [anthropicApiUrl] must appear within 500 chars of [llmProbeUrl] so the
     * test fails if the guard is placed somewhere else in the file and not at the assignment site.
     */
    @Test
    fun `llmProbeUrl branches to anthropicApiUrl on native`() {
        assertTrue(
            agent.contains("llmProbeUrl"),
            "AgentService must configure llmProbeUrl on the AgentLoop"
        )
        // Site-specific proximity: anthropicApiUrl must appear in the same llmProbeUrl block.
        assertTrue(
            Regex("""llmProbeUrl[\s\S]{0,500}anthropicApiUrl""").containsMatchIn(agent),
            "anthropicApiUrl must appear within 500 chars of llmProbeUrl — the native branch " +
                "of the probe URL must be adjacent to its assignment, not elsewhere in the file"
        )
    }

    // ── I2: L2 fallback chain ─────────────────────────────────────────────────

    /**
     * The L2 fallback chain must be sourced from [AnthropicModelCatalog.fallbackChain()] (bare
     * ids) when on the native provider. Using [ModelCache.buildFallbackChain] on the native path
     * produces Sourcegraph-formatted refs that are invalid on api.anthropic.com.
     *
     * Proximity regex: the catalog call must appear within 400 chars of [resolveFallbackChain]
     * so the test fails if the branch is placed elsewhere in the file.
     */
    @Test
    fun `L2 fallback chain uses anthropic catalog on native`() {
        assertTrue(
            agent.contains("AnthropicModelCatalog.fallbackChain()"),
            "AgentService must call AnthropicModelCatalog.fallbackChain() for the native L2 chain"
        )
        // Site-specific proximity: the catalog call must be inside the resolveFallbackChain block.
        assertTrue(
            Regex("""resolveFallbackChain[\s\S]{0,400}AnthropicModelCatalog\.fallbackChain\(\)""")
                .containsMatchIn(agent),
            "AnthropicModelCatalog.fallbackChain() must appear within 400 chars of " +
                "resolveFallbackChain — the native chain must be wired at the resolution site"
        )
    }

    // ── I4: availableModels ───────────────────────────────────────────────────

    /**
     * [SystemPrompt.availableModels] must NOT be sourced from [ModelCache.getCached()] on the
     * native provider — that cache is Sourcegraph-only and returns an empty or stale list on
     * api.anthropic.com. The native branch must use [AnthropicModelCatalog.MODELS] instead.
     *
     * Proximity regex from the task brief: the llmProvider guard must appear within 400 chars
     * of the AnthropicModelCatalog or MODELS reference so a disjoint guard fails the test.
     */
    @Test
    fun `availableModels does not feed Sourcegraph cache on native`() {
        assertTrue(
            Regex("""llmProvider\s*==\s*"anthropic"[\s\S]{0,400}(AnthropicModelCatalog|MODELS)""")
                .containsMatchIn(agent),
            "When llmProvider == \"anthropic\", availableModels must be sourced from " +
                "AnthropicModelCatalog (not ModelCache.getCached()) within 400 chars of the guard"
        )
    }
}
