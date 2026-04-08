package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.*
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration test verifying that [AgentLoop] switches models via [ModelFallbackManager]
 * when the primary brain returns a network error, and completes successfully on a fallback brain.
 */
class ModelFallbackLoopTest {

    private lateinit var project: Project
    private lateinit var contextManager: ContextManager

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        contextManager = ContextManager(maxInputTokens = 100_000)
    }

    // ---- Helpers ----

    private fun successResponse(content: String? = null, toolCalls: List<ToolCall>? = null): ChatCompletionResponse =
        ChatCompletionResponse(
            id = "resp-${System.nanoTime()}",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(
                        role = "assistant",
                        content = content,
                        toolCalls = toolCalls
                    ),
                    finishReason = if (toolCalls != null) "tool_calls" else "stop"
                )
            ),
            usage = UsageInfo(promptTokens = 100, completionTokens = 20, totalTokens = 120)
        )

    private fun completionToolCallResponse(): ChatCompletionResponse =
        successResponse(
            toolCalls = listOf(
                ToolCall(
                    id = "call_completion_${System.nanoTime()}",
                    type = "function",
                    function = FunctionCall(name = "attempt_completion", arguments = """{"result":"Done."}""")
                )
            )
        )

    private fun completionTool(): AgentTool = object : AgentTool {
        override val name = "attempt_completion"
        override val description = "Signal task completion"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "Done.", summary = "Done.", tokenEstimate = 5, isCompletion = true)
    }

    /**
     * A brain that returns a fixed sequence of ApiResults.
     * Each call to chatStream pops the next result from the list.
     */
    private class SequenceBrain(
        override val modelId: String,
        private val responses: List<ApiResult<ChatCompletionResponse>>
    ) : LlmBrain {
        private var callIndex = 0

        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            toolChoice: JsonElement?
        ): ApiResult<ChatCompletionResponse> {
            throw UnsupportedOperationException("AgentLoop uses chatStream, not chat")
        }

        override suspend fun chatStream(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            onChunk: suspend (StreamChunk) -> Unit
        ): ApiResult<ChatCompletionResponse> {
            if (callIndex >= responses.size) {
                return ApiResult.Error(ErrorType.SERVER_ERROR, "No more scripted responses (call #$callIndex)")
            }
            return responses[callIndex++]
        }

        override fun estimateTokens(text: String): Int = text.toByteArray().size / 4
        override fun cancelActiveRequest() {}
    }

    // ---- Tests ----

    @Test
    fun `model fallback on network error then completes on fallback model`() = runTest {
        // Three-model chain: primary → fallback1 → fallback2
        val modelChain = listOf("model-primary", "model-fallback1", "model-fallback2")
        val fallbackManager = ModelFallbackManager(modelChain)

        // Primary brain: always returns NETWORK_ERROR
        val primaryBrain = SequenceBrain(
            modelId = "model-primary",
            responses = listOf(
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Connection refused")
            )
        )

        // Fallback brain: returns a successful attempt_completion
        val fallbackBrain = SequenceBrain(
            modelId = "model-fallback1",
            responses = listOf(
                ApiResult.Success(completionToolCallResponse())
            )
        )

        // Track model switch callback invocations
        val modelSwitches = mutableListOf<Triple<String, String, String>>()

        // Brain factory: returns the appropriate brain based on model ID.
        // Second arg (reason) is unused by the test — it's just diagnostic context
        // for the recycle marker file in production.
        val brainFactory: suspend (String, String?) -> LlmBrain = { modelId, _ ->
            when (modelId) {
                "model-primary" -> primaryBrain
                "model-fallback1" -> fallbackBrain
                else -> throw IllegalArgumentException("Unexpected model: $modelId")
            }
        }

        val tools = listOf(completionTool())
        val toolMap = tools.associateBy { it.name }
        val toolDefs = tools.map { it.toToolDefinition() }

        val loop = AgentLoop(
            brain = primaryBrain,
            tools = toolMap,
            toolDefinitions = toolDefs,
            contextManager = contextManager,
            project = project,
            fallbackManager = fallbackManager,
            brainFactory = brainFactory,
            onModelSwitch = { from, to, reason ->
                modelSwitches.add(Triple(from, to, reason))
            }
        )

        val result = loop.run("Fix the bug")

        // The loop should complete successfully on the fallback model
        assertTrue(result is LoopResult.Completed, "Expected Completed but got $result")
        assertEquals("Done.", (result as LoopResult.Completed).summary)

        // The onModelSwitch callback should have fired with the fallback model
        assertTrue(modelSwitches.isNotEmpty(), "Expected at least one model switch")
        val (from, to, _) = modelSwitches[0]
        assertEquals("model-primary", from)
        assertEquals("model-fallback1", to)

        // The fallback manager should now be on the fallback model
        assertEquals("model-fallback1", fallbackManager.getCurrentModelId())
        assertFalse(fallbackManager.isPrimary())
    }

    @Test
    fun `exhausts all fallback models then fails`() = runTest {
        // Two-model chain: primary → fallback1
        val modelChain = listOf("model-primary", "model-fallback1")
        val fallbackManager = ModelFallbackManager(modelChain)

        // Both brains return NETWORK_ERROR
        val primaryBrain = SequenceBrain(
            modelId = "model-primary",
            responses = List(3) { ApiResult.Error(ErrorType.NETWORK_ERROR, "Connection refused") }
        )

        val fallbackBrain = SequenceBrain(
            modelId = "model-fallback1",
            responses = List(3) { ApiResult.Error(ErrorType.NETWORK_ERROR, "Connection refused") }
        )

        val modelSwitches = mutableListOf<Triple<String, String, String>>()

        val brainFactory: suspend (String, String?) -> LlmBrain = { modelId, _ ->
            when (modelId) {
                "model-primary" -> primaryBrain
                "model-fallback1" -> fallbackBrain
                else -> throw IllegalArgumentException("Unexpected model: $modelId")
            }
        }

        val tools = listOf(completionTool())
        val toolMap = tools.associateBy { it.name }
        val toolDefs = tools.map { it.toToolDefinition() }

        val loop = AgentLoop(
            brain = primaryBrain,
            tools = toolMap,
            toolDefinitions = toolDefs,
            contextManager = contextManager,
            project = project,
            fallbackManager = fallbackManager,
            brainFactory = brainFactory,
            onModelSwitch = { from, to, reason ->
                modelSwitches.add(Triple(from, to, reason))
            }
        )

        val result = loop.run("Fix the bug")

        // After exhausting retries on both models, the loop should fail
        assertTrue(result is LoopResult.Failed, "Expected Failed but got $result")
        assertTrue((result as LoopResult.Failed).error.contains("Connection refused"))
    }

}
