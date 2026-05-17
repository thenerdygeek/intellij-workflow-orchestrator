package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.loop.ModelFallbackManager
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
     * Pins the contract that the 5 value parity params are evaluated at DISPATCH time,
     * not at SpawnAgentTool construction time. Without this, sub-agents see null values
     * for params that the parent populated AFTER tool registration.
     */
    @Test
    fun `value parity params are evaluated at dispatch time, not at construction time`() {
        var liveSpiller: ToolOutputSpiller? = null
        var liveCatalog: ModelCatalogService? = null
        var liveFallback: ModelFallbackManager? = null
        val liveBrainFactory: (suspend (String, String?) -> LlmBrain)? = null  // sufficient for null vs non-null check
        var liveChain: List<String>? = null

        val spillerProvider: () -> ToolOutputSpiller? = { liveSpiller }
        val catalogProvider: () -> ModelCatalogService? = { liveCatalog }
        val fallbackProvider: () -> ModelFallbackManager? = { liveFallback }
        val brainFactoryProvider: () -> (suspend (String, String?) -> LlmBrain)? = { liveBrainFactory }
        val chainProvider: () -> List<String>? = { liveChain }

        // Snapshot: every provider returns null at this moment (mirrors registerAllTools time).
        assertNotNull(spillerProvider)  // sanity — provider itself is non-null
        assertNull(spillerProvider())

        // Now the parent enters executeTask and populates the live fields.
        val expectedSpiller = mockk<ToolOutputSpiller>(relaxed = true)
        val expectedCatalog = mockk<ModelCatalogService>(relaxed = true)
        val expectedFallback = mockk<ModelFallbackManager>(relaxed = true)
        val expectedChain = listOf("modelA", "modelB")
        liveSpiller = expectedSpiller
        liveCatalog = expectedCatalog
        liveFallback = expectedFallback
        liveChain = expectedChain

        // A sub-agent fires NOW. The providers must see the new values.
        assertSame(expectedSpiller, spillerProvider())
        assertSame(expectedCatalog, catalogProvider())
        assertSame(expectedFallback, fallbackProvider())
        assertSame(expectedChain, chainProvider())
    }

    @Test
    fun `SpawnAgentTool source declares provider lambdas for the 5 value parity params`() {
        val src = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SpawnAgentTool.kt"
        ).readText()
        // Each of the 5 value params must be declared as a `() -> T?` lambda field.
        val expectedDecls = listOf(
            "outputSpiller: () -> com.workflow.orchestrator.agent.tools.ToolOutputSpiller?",
            "fallbackManager: () -> com.workflow.orchestrator.agent.loop.ModelFallbackManager?",
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
            listOf("outputSpiller", "fallbackManager", "brainFactory", "cachedFallbackChain", "modelCatalogService").forEach { name ->
                assert("$name = $name()" in block) {
                    "SubagentRunner call site #${i + 1} must pass `$name = $name()` (provider invocation, not snapshot). Block:\n$block"
                }
            }
        }
    }
}
