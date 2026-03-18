package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ComplexityRouterTest {

    private val brain = mockk<LlmBrain>()

    @Test
    fun `route returns SIMPLE when tool call returns SIMPLE`() = runTest {
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
            toolCallResponse("SIMPLE")
        )

        val result = ComplexityRouter.route("Fix a typo in README", brain)
        assertEquals(TaskComplexity.SIMPLE, result)
    }

    @Test
    fun `route returns COMPLEX when tool call returns COMPLEX`() = runTest {
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
            toolCallResponse("COMPLEX")
        )

        val result = ComplexityRouter.route("Refactor authentication across 5 modules", brain)
        assertEquals(TaskComplexity.COMPLEX, result)
    }

    @Test
    fun `route returns COMPLEX when brain returns error`() = runTest {
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Error(
            type = ErrorType.NETWORK_ERROR,
            message = "Connection refused"
        )

        val result = ComplexityRouter.route("Fix a typo", brain)
        assertEquals(TaskComplexity.COMPLEX, result)
    }

    @Test
    fun `route returns COMPLEX when no tool calls in response`() = runTest {
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
            ChatCompletionResponse(
                id = "test-3",
                choices = listOf(
                    Choice(
                        index = 0,
                        message = ChatMessage(role = "assistant", content = "I'm not sure, maybe moderate?"),
                        finishReason = "stop"
                    )
                )
            )
        )

        val result = ComplexityRouter.route("Do something", brain)
        assertEquals(TaskComplexity.COMPLEX, result)
    }

    @Test
    fun `route returns COMPLEX for empty choices`() = runTest {
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
            ChatCompletionResponse(
                id = "test-5",
                choices = emptyList()
            )
        )

        val result = ComplexityRouter.route("Fix something", brain)
        assertEquals(TaskComplexity.COMPLEX, result)
    }

    @Test
    fun `route returns COMPLEX for malformed tool call arguments`() = runTest {
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
            ChatCompletionResponse(
                id = "test-6",
                choices = listOf(
                    Choice(
                        index = 0,
                        message = ChatMessage(
                            role = "assistant",
                            content = null,
                            toolCalls = listOf(
                                ToolCall(
                                    id = "call-1",
                                    function = FunctionCall(
                                        name = "classify_task",
                                        arguments = "not valid json"
                                    )
                                )
                            )
                        ),
                        finishReason = "tool_calls"
                    )
                )
            )
        )

        val result = ComplexityRouter.route("Do something", brain)
        assertEquals(TaskComplexity.COMPLEX, result)
    }

    // --- Helper ---

    private fun toolCallResponse(complexity: String): ChatCompletionResponse {
        return ChatCompletionResponse(
            id = "test-${System.nanoTime()}",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-classify",
                                function = FunctionCall(
                                    name = "classify_task",
                                    arguments = """{"complexity":"$complexity"}"""
                                )
                            )
                        )
                    ),
                    finishReason = "tool_calls"
                )
            )
        )
    }
}
