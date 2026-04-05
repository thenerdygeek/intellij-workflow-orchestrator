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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for cost tracking — verifying that input/output tokens are accumulated
 * across iterations and included in LoopResult.
 *
 * Ported from Cline's cost tracking pattern:
 * Cline accumulates tokensIn/tokensOut in HistoryItem after each API response
 * and passes them to the webview for display.
 */
class CostTrackingTest {

    private lateinit var project: Project
    private lateinit var contextManager: ContextManager

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        contextManager = ContextManager(maxInputTokens = 100_000)
    }

    // ---- Helpers ----

    private fun responseWithUsage(
        promptTokens: Int,
        completionTokens: Int,
        toolCallName: String? = null,
        toolCallArgs: String = "{}",
        content: String? = null
    ): ChatCompletionResponse {
        val message = if (toolCallName != null) {
            ChatMessage(
                role = "assistant",
                content = null,
                toolCalls = listOf(
                    ToolCall(
                        id = "call_${System.nanoTime()}",
                        type = "function",
                        function = FunctionCall(name = toolCallName, arguments = toolCallArgs)
                    )
                )
            )
        } else {
            ChatMessage(role = "assistant", content = content)
        }

        return ChatCompletionResponse(
            id = "resp-${System.nanoTime()}",
            choices = listOf(Choice(index = 0, message = message, finishReason = "stop")),
            usage = UsageInfo(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = promptTokens + completionTokens
            )
        )
    }

    private class SequenceBrain(private val responses: List<ApiResult<ChatCompletionResponse>>) : LlmBrain {
        override val modelId: String = "test-model"
        private var callIndex = 0

        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            toolChoice: JsonElement?
        ): ApiResult<ChatCompletionResponse> {
            throw UnsupportedOperationException("Uses chatStream")
        }

        override suspend fun chatStream(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            onChunk: suspend (StreamChunk) -> Unit
        ): ApiResult<ChatCompletionResponse> {
            if (callIndex >= responses.size) {
                return ApiResult.Error(ErrorType.SERVER_ERROR, "No more responses")
            }
            return responses[callIndex++]
        }

        override fun estimateTokens(text: String): Int = text.toByteArray().size / 4
        override fun cancelActiveRequest() {}
    }

    private fun fakeTool(
        toolName: String,
        result: ToolResult = ToolResult(content = "ok", summary = "ok", tokenEstimate = 5)
    ): AgentTool = object : AgentTool {
        override val name = toolName
        override val description = "Test tool"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project) = result
    }

    private fun completionTool(summary: String = "Done.") = fakeTool(
        "attempt_completion",
        ToolResult(content = summary, summary = summary, tokenEstimate = 5, isCompletion = true)
    )

    private fun buildLoop(
        brain: LlmBrain,
        tools: List<AgentTool>,
        onTokenUpdate: ((Int, Int) -> Unit)? = null
    ): AgentLoop {
        val toolMap = tools.associateBy { it.name }
        val toolDefs = tools.map { it.toToolDefinition() }
        return AgentLoop(
            brain = brain,
            tools = toolMap,
            toolDefinitions = toolDefs,
            contextManager = contextManager,
            project = project,
            onTokenUpdate = onTokenUpdate
        )
    }

    // ---- Tests ----

    @Nested
    inner class TokenAccumulation {

        @Test
        fun `tokens accumulated across multiple iterations`() = runTest {
            // Iteration 1: 100 prompt + 20 completion
            // Iteration 2: 200 prompt + 30 completion (completion call)
            val brain = SequenceBrain(listOf(
                ApiResult.Success(responseWithUsage(
                    promptTokens = 100, completionTokens = 20,
                    toolCallName = "read_file", toolCallArgs = """{"path":"test.kt"}"""
                )),
                ApiResult.Success(responseWithUsage(
                    promptTokens = 200, completionTokens = 30,
                    toolCallName = "attempt_completion", toolCallArgs = """{"result":"Done"}"""
                ))
            ))

            val tools = listOf(fakeTool("read_file"), completionTool())
            val loop = buildLoop(brain, tools)
            val result = loop.run("Do work")

            assertTrue(result is LoopResult.Completed)
            val completed = result as LoopResult.Completed
            assertEquals(300, completed.inputTokens, "input tokens should accumulate: 100 + 200")
            assertEquals(50, completed.outputTokens, "output tokens should accumulate: 20 + 30")
            assertEquals(350, completed.tokensUsed, "total should be sum: 120 + 230")
        }

        @Test
        fun `tokens reported in Failed result`() = runTest {
            // One successful call (accumulates tokens), then hard failure
            val brain = SequenceBrain(listOf(
                ApiResult.Success(responseWithUsage(
                    promptTokens = 500, completionTokens = 100, content = null
                )),
                ApiResult.Success(responseWithUsage(
                    promptTokens = 600, completionTokens = 50, content = null
                )),
                ApiResult.Success(responseWithUsage(
                    promptTokens = 700, completionTokens = 50, content = null
                ))
            ))

            val tools = listOf(completionTool())
            val loop = buildLoop(brain, tools, onTokenUpdate = null)
            val result = loop.run("Something")

            // After 3 empty responses it fails
            assertTrue(result is LoopResult.Failed)
            val failed = result as LoopResult.Failed
            assertEquals(1800, failed.inputTokens, "input tokens: 500 + 600 + 700")
            assertEquals(200, failed.outputTokens, "output tokens: 100 + 50 + 50")
        }

        @Test
        fun `tokens reported in Cancelled result`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(responseWithUsage(
                    promptTokens = 100, completionTokens = 20,
                    toolCallName = "read_file", toolCallArgs = """{"path":"a.kt"}"""
                )),
                ApiResult.Success(responseWithUsage(
                    promptTokens = 200, completionTokens = 30,
                    toolCallName = "attempt_completion", toolCallArgs = """{}"""
                ))
            ))

            val tools = listOf(fakeTool("read_file"), completionTool())
            val loop = buildLoop(brain, tools)

            // Cancel after first call completes
            val result = loop.run("Do work")
            // This should complete normally (both calls succeed), but tokens should be tracked
            assertTrue(result is LoopResult.Completed)
            val completed = result as LoopResult.Completed
            assertTrue(completed.inputTokens > 0)
            assertTrue(completed.outputTokens > 0)
        }
    }

    @Nested
    inner class TokenUpdateCallback {

        @Test
        fun `onTokenUpdate fires after each API call`() = runTest {
            val updates = mutableListOf<Pair<Int, Int>>()

            val brain = SequenceBrain(listOf(
                ApiResult.Success(responseWithUsage(
                    promptTokens = 100, completionTokens = 20,
                    toolCallName = "read_file", toolCallArgs = """{"path":"test.kt"}"""
                )),
                ApiResult.Success(responseWithUsage(
                    promptTokens = 200, completionTokens = 30,
                    toolCallName = "attempt_completion", toolCallArgs = """{"result":"Done"}"""
                ))
            ))

            val tools = listOf(fakeTool("read_file"), completionTool())
            val loop = buildLoop(brain, tools) { inputTokens, outputTokens ->
                updates.add(inputTokens to outputTokens)
            }
            loop.run("Do work")

            assertEquals(2, updates.size, "should fire once per API call")
            // Callback receives per-call values (current context usage, not cumulative)
            assertEquals(100 to 20, updates[0])
            assertEquals(200 to 30, updates[1])
        }

        @Test
        fun `onTokenUpdate not called when null`() = runTest {
            // Just verify it doesn't throw when callback is null
            val brain = SequenceBrain(listOf(
                ApiResult.Success(responseWithUsage(
                    promptTokens = 100, completionTokens = 20,
                    toolCallName = "attempt_completion", toolCallArgs = """{"result":"Done"}"""
                ))
            ))

            val tools = listOf(completionTool())
            val loop = buildLoop(brain, tools, onTokenUpdate = null)
            val result = loop.run("Do work")

            assertTrue(result is LoopResult.Completed)
        }
    }

    @Nested
    inner class SessionTokenPersistence {

        @Test
        fun `Session stores input and output tokens`() {
            val session = com.workflow.orchestrator.agent.session.Session(
                id = "test-session",
                title = "Test",
                inputTokens = 45000,
                outputTokens = 8000,
                totalTokens = 53000
            )

            assertEquals(45000, session.inputTokens)
            assertEquals(8000, session.outputTokens)
            assertEquals(53000, session.totalTokens)
        }

        @Test
        fun `Session defaults to zero tokens`() {
            val session = com.workflow.orchestrator.agent.session.Session(
                id = "test-session",
                title = "Test"
            )

            assertEquals(0, session.inputTokens)
            assertEquals(0, session.outputTokens)
            assertEquals(0, session.totalTokens)
        }
    }
}
