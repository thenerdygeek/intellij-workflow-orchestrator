package com.workflow.orchestrator.agent.tools.subagent

import com.workflow.orchestrator.agent.loop.ModelFallbackManager
import com.workflow.orchestrator.agent.tools.ToolOutputSpiller
import com.workflow.orchestrator.agent.session.AttachmentStore
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.ModelCatalogService
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class SubagentRunnerWiringTest {

    @Test
    fun `SubagentRunner accepts outputSpiller attachmentStoreProvider onCompactionState fallbackManager brainFactory cachedFallbackChain onRetry onModelSwitch modelCatalogService as constructor params`() {
        val brain = mockk<LlmBrain>(relaxed = true)
        val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)
        val spiller = mockk<ToolOutputSpiller>(relaxed = true)
        val storeProvider: () -> AttachmentStore? = { null }
        val fallback = mockk<ModelFallbackManager>(relaxed = true)
        val catalog = mockk<ModelCatalogService>(relaxed = true)
        val brainFactory: suspend (String, String?) -> LlmBrain = { _, _ -> brain }

        val runner = SubagentRunner(
            brain = brain,
            coreTools = emptyMap(),
            systemPrompt = "test",
            project = project,
            maxIterations = 5,
            planMode = false,
            contextBudget = 1000,
            outputSpiller = spiller,
            attachmentStoreProvider = storeProvider,
            onCompactionState = { _, _ -> },
            fallbackManager = fallback,
            brainFactory = brainFactory,
            cachedFallbackChain = listOf("a", "b"),
            onRetry = { _, _, _, _ -> },
            onModelSwitch = { _, _, _ -> },
            modelCatalogService = catalog,
        )
        assertNotNull(runner)
    }

    @Test
    fun `SubagentProgressUpdate has thinkingDelta and thinkingEnd fields`() {
        val update = SubagentProgressUpdate(
            thinkingDelta = "foo",
            thinkingEnd = true,
        )
        org.junit.jupiter.api.Assertions.assertEquals("foo", update.thinkingDelta)
        org.junit.jupiter.api.Assertions.assertEquals(true, update.thinkingEnd)
    }

    @Test
    fun `SubagentRunner forwards the new params into the AgentLoop constructor call`() {
        val src = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunner.kt"
        ).readText()
        val agentLoopBlock = src.substringAfter("val loop = AgentLoop(")
            .substringBefore("\n            )")
        val mustContain = listOf(
            "outputSpiller = outputSpiller",
            "attachmentStoreProvider = attachmentStoreProvider",
            "onCompactionState = onCompactionState",
            "fallbackManager = fallbackManager",
            "brainFactory = brainFactory",
            "cachedFallbackChain = cachedFallbackChain",
            "onRetry = onRetry",
            "onModelSwitch = onModelSwitch",
            "modelCatalogService = modelCatalogService",
        )
        mustContain.forEach { line ->
            assert(line in agentLoopBlock) {
                "SubagentRunner.run() must forward `$line` into AgentLoop. Block:\n$agentLoopBlock"
            }
        }
    }
}
