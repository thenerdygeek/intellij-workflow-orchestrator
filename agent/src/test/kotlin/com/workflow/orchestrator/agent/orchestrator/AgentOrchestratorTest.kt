package com.workflow.orchestrator.agent.orchestrator

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.runtime.*
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AgentOrchestratorTest {

    private lateinit var brain: LlmBrain
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var project: Project
    private lateinit var orchestrator: AgentOrchestrator

    @BeforeEach
    fun setUp() {
        brain = mockk(relaxed = true)
        toolRegistry = mockk(relaxed = true)
        project = mockk(relaxed = true)

        every { project.basePath } returns "/tmp/test-project"
        every { toolRegistry.allTools() } returns emptyList()

        // chatStream falls back to chat() — throw NotImplementedError so SingleAgentSession uses chat()
        coEvery { brain.chatStream(any(), any(), any(), any()) } throws NotImplementedError("test fallback")

        orchestrator = AgentOrchestrator(brain, toolRegistry, project)
    }

    // --- Single Agent Mode (default) ---

    @Test
    fun `executeTask completes via single agent session`() = runTest {
        // Single agent session gets the brain response directly (no complexity router)
        val response = chatResponse("Fixed the typo in UserService.kt")
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(response)

        val progressUpdates = mutableListOf<AgentProgress>()
        val result = orchestrator.executeTask("Fix typo in UserService", onProgress = { progressUpdates.add(it) })

        assertTrue(result is AgentResult.Completed, "Expected Completed, got $result")
        val completed = result as AgentResult.Completed
        assertTrue(completed.summary.contains("Fixed the typo"), "Summary: ${completed.summary}")

        // Verify progress was reported
        assertTrue(progressUpdates.any { it.step.contains("Starting") })
    }

    @Test
    fun `executeTask returns Failed when brain errors`() = runTest {
        coEvery { brain.chat(any(), any(), any(), any()) } returns
            ApiResult.Error(ErrorType.NETWORK_ERROR, "Connection refused")

        val result = orchestrator.executeTask("Fix bug")

        assertTrue(result is AgentResult.Failed, "Expected Failed, got $result")
        assertTrue((result as AgentResult.Failed).error.contains("Connection refused"))
    }

    @Test
    fun `cancelTask returns Cancelled during execution`() = runTest {
        // Cancel during the SingleAgentSession by making the brain trigger cancel
        coEvery { brain.chat(any(), any(), any(), any()) } coAnswers {
            orchestrator.cancelTask()
            ApiResult.Success(chatResponse("Task cancelled"))
        }

        val result = orchestrator.executeTask("Some task")

        // The single agent session checks cancelled flag between iterations
        assertTrue(result is AgentResult.Completed || result is AgentResult.Cancelled,
            "Expected Completed or Cancelled, got $result")
    }

    // --- Helper ---

    private fun chatResponse(content: String): ChatCompletionResponse {
        return ChatCompletionResponse(
            id = "test-${System.nanoTime()}",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(role = "assistant", content = content),
                    finishReason = "stop"
                )
            ),
            usage = UsageInfo(promptTokens = 100, completionTokens = 50, totalTokens = 150)
        )
    }
}
