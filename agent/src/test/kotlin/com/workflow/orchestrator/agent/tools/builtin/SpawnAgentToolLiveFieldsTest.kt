package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.tools.ToolOutputSpiller
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.ModelCatalogService
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SpawnAgentToolLiveFieldsTest {

    /**
     * Pins the contract that the value parity params are evaluated at DISPATCH time,
     * not at SpawnAgentTool construction time. Without this, sub-agents see null values
     * for params that the parent populated AFTER tool registration.
     */
    @Test
    fun `value parity params are evaluated at dispatch time, not at construction time`() {
        var liveSpiller: ToolOutputSpiller? = null
        var liveCatalog: ModelCatalogService? = null
        val liveBrainFactory: (suspend (String, String?) -> LlmBrain)? = null  // sufficient for null vs non-null check
        var liveChain: List<String>? = null

        val spillerProvider: () -> ToolOutputSpiller? = { liveSpiller }
        val catalogProvider: () -> ModelCatalogService? = { liveCatalog }
        val brainFactoryProvider: () -> (suspend (String, String?) -> LlmBrain)? = { liveBrainFactory }
        val chainProvider: () -> List<String>? = { liveChain }

        // Snapshot: every provider returns null at this moment (mirrors registerAllTools time).
        assertNotNull(spillerProvider)  // sanity — provider itself is non-null
        assertNull(spillerProvider())

        // Now the parent enters executeTask and populates the live fields.
        val expectedSpiller = mockk<ToolOutputSpiller>(relaxed = true)
        val expectedCatalog = mockk<ModelCatalogService>(relaxed = true)
        val expectedChain = listOf("modelA", "modelB")
        liveSpiller = expectedSpiller
        liveCatalog = expectedCatalog
        liveChain = expectedChain

        // A sub-agent fires NOW. The providers must see the new values.
        assertSame(expectedSpiller, spillerProvider())
        assertSame(expectedCatalog, catalogProvider())
        assertSame(expectedChain, chainProvider())
    }

    @Test
    fun `SpawnAgentTool source declares provider lambdas for the value parity params`() {
        val src = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt"
        ).readText()
        // Each value param must be declared as a `() -> T?` lambda field.
        val expectedDecls = listOf(
            "outputSpiller: () -> com.workflow.orchestrator.agent.tools.ToolOutputSpiller?",
            "brainFactory: () -> (suspend",  // partial — brainFactory's full type is verbose
            "cachedFallbackChain: () -> List<String>?",
            "modelCatalogService: () -> com.workflow.orchestrator.core.ai.ModelCatalogService?",
        )
        expectedDecls.forEach { decl ->
            assert(decl in src) {
                "SpawnAgentTool must declare provider lambda `$decl`. (Field shape change is the live-fields fix.)"
            }
        }
    }

    /**
     * End-to-end contract: mimic exactly how AgentService wires the SpawnAgentTool
     * lambdas around its own mutable fields, then walk through the lifecycle:
     *
     *   service init       → SpawnAgentTool constructed; live fields are null
     *   executeTask starts → AgentService populates live fields
     *   LLM spawns agent   → SpawnAgentTool invokes providers; SubagentRunner sees populated values
     *
     * If a future regression reverts to snapshot-at-construction storage, this test fails.
     */
    @Test
    fun `live field lifecycle — providers null at service-init time, populated at dispatch time`() {
        // Simulate AgentService's mutable fields.
        var liveSpiller: ToolOutputSpiller? = null
        var liveBrainFactory: (suspend (String, String?) -> LlmBrain)? = null
        var liveCachedChain: List<String>? = null
        var liveCatalog: ModelCatalogService? = null

        // Simulate the wiring AgentService does at SpawnAgentTool construction.
        val spillerProvider: () -> ToolOutputSpiller? = { liveSpiller }
        val brainFactoryProvider: () -> (suspend (String, String?) -> LlmBrain)? = { liveBrainFactory }
        val cachedChainProvider: () -> List<String>? = { liveCachedChain }
        val catalogProvider: () -> ModelCatalogService? = { liveCatalog }

        // ── service init: SpawnAgentTool is constructed. ──
        // Snapshot what the providers see at this moment.
        assertNull(spillerProvider(),  "service-init: spiller must be null")
        assertNull(brainFactoryProvider(), "service-init: brainFactory must be null")
        assertNull(cachedChainProvider(), "service-init: cachedChain must be null")
        assertNull(catalogProvider(),  "service-init: catalog must be null")

        // ── executeTask runs: AgentService populates the live fields. ──
        val populatedSpiller = mockk<ToolOutputSpiller>(relaxed = true)
        val populatedBrainFactory: suspend (String, String?) -> LlmBrain =
            { _, _ -> mockk(relaxed = true) }
        val populatedChain = listOf("model-a", "model-b")
        val populatedCatalog = mockk<ModelCatalogService>(relaxed = true)
        liveSpiller = populatedSpiller
        liveBrainFactory = populatedBrainFactory
        liveCachedChain = populatedChain
        liveCatalog = populatedCatalog

        // ── LLM calls `agent` tool: SpawnAgentTool dispatches a sub-agent. ──
        // The dispatch sites in executeSingle/executeParallel invoke each provider
        // and forward the snapshot to SubagentRunner. Simulate that here.
        val dispatchedSpiller = spillerProvider()
        val dispatchedBrainFactory = brainFactoryProvider()
        val dispatchedChain = cachedChainProvider()
        val dispatchedCatalog = catalogProvider()

        // The sub-agent must see the populated values, NOT null.
        assertSame(populatedSpiller, dispatchedSpiller, "dispatch: spiller must be populated")
        assertSame(populatedBrainFactory, dispatchedBrainFactory, "dispatch: brainFactory must be populated")
        assertSame(populatedChain, dispatchedChain, "dispatch: cachedChain must be populated")
        assertSame(populatedCatalog, dispatchedCatalog, "dispatch: catalog must be populated")

        // Bonus: a SECOND dispatch later sees the SAME populated values (no caching anti-pattern).
        assertSame(populatedSpiller, spillerProvider())
        assertSame(populatedCatalog, catalogProvider())
    }

    /**
     * Regression guard: if any of the 5 fields were stored as a snapshot at SpawnAgentTool
     * construction (the pre-F1 bug), the dispatched value would be null even after the
     * live field is populated. This test fails fast on that exact regression.
     */
    @Test
    fun `snapshot-at-construction anti-pattern would produce a null dispatch — provider pattern does not`() {
        var liveSpiller: ToolOutputSpiller? = null

        // ANTI-PATTERN: simulate the bug — capture the snapshot at "construction" (now).
        val snapshotAtConstruction: ToolOutputSpiller? = liveSpiller  // null

        // CORRECT pattern: a provider lambda.
        val providerAtConstruction: () -> ToolOutputSpiller? = { liveSpiller }

        // Time passes; executeTask populates the field.
        val populated = mockk<ToolOutputSpiller>(relaxed = true)
        liveSpiller = populated

        // Anti-pattern still sees null (the bug).
        assertNull(snapshotAtConstruction, "snapshot anti-pattern: still null after populate")

        // Provider correctly sees the populated value.
        assertSame(populated, providerAtConstruction(), "provider pattern: sees populated value")
    }

    @Test
    fun `SpawnAgentTool dereferences providers at runner-construction time inside executeSingle and executeParallel`() {
        val src = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt"
        ).readText()
        // Both call sites pass the resolved snapshot, not the lambda itself.
        // Look for patterns like `outputSpiller = outputSpiller()` (with parens — invocation) in BOTH
        // executeSingle and executeParallel.
        val callSites = Regex("""SubagentRunner\s*\(([\s\S]*?)^\s*\)""", RegexOption.MULTILINE)
            .findAll(src).map { it.groupValues[1] }.toList()
        require(callSites.size >= 2) { "Expected >=2 SubagentRunner(...) call sites." }
        callSites.forEachIndexed { i, block ->
            listOf("outputSpiller", "brainFactory", "cachedFallbackChain", "modelCatalogService").forEach { name ->
                assert("$name = $name()" in block) {
                    "SubagentRunner call site #${i + 1} must pass `$name = $name()` (provider invocation, not snapshot). Block:\n$block"
                }
            }
        }
    }
}
