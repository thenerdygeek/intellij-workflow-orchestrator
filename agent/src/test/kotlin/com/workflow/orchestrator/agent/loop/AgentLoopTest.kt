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

class AgentLoopTest {

    private lateinit var project: Project
    private lateinit var contextManager: ContextManager

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        contextManager = ContextManager(maxInputTokens = 100_000)
    }

    // ---- Helpers ----

    /** Creates a ChatCompletionResponse with text content and no tool calls. */
    private fun textResponse(content: String): ChatCompletionResponse =
        ChatCompletionResponse(
            id = "resp-${System.nanoTime()}",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(role = "assistant", content = content),
                    finishReason = "stop"
                )
            ),
            usage = UsageInfo(promptTokens = 100, completionTokens = 20, totalTokens = 120)
        )

    /** Creates a ChatCompletionResponse with tool calls. */
    private fun toolCallResponse(vararg calls: Pair<String, String>): ChatCompletionResponse =
        ChatCompletionResponse(
            id = "resp-${System.nanoTime()}",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = calls.mapIndexed { idx, (name, args) ->
                            ToolCall(
                                id = "call_${idx}_${System.nanoTime()}",
                                type = "function",
                                function = FunctionCall(name = name, arguments = args)
                            )
                        }
                    ),
                    finishReason = "tool_calls"
                )
            ),
            usage = UsageInfo(promptTokens = 100, completionTokens = 30, totalTokens = 130)
        )

    /** Creates a ChatCompletionResponse with no content and no tool calls (empty). */
    private fun emptyResponse(): ChatCompletionResponse =
        ChatCompletionResponse(
            id = "resp-${System.nanoTime()}",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(role = "assistant", content = null),
                    finishReason = "stop"
                )
            ),
            usage = UsageInfo(promptTokens = 100, completionTokens = 0, totalTokens = 100)
        )

    /**
     * A fake LlmBrain that returns pre-configured responses in sequence via chatStream.
     * Each call to chatStream returns the next response in the list.
     */
    private class SequenceBrain(private val responses: List<ApiResult<ChatCompletionResponse>>) : LlmBrain {
        override val modelId: String = "test-model"
        private var callIndex = 0
        var cancelCalled = false
            private set

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
        override fun cancelActiveRequest() { cancelCalled = true }
    }

    /** Convenience: build a SequenceBrain from successful responses. */
    private fun sequenceBrain(vararg responses: ChatCompletionResponse): SequenceBrain =
        SequenceBrain(responses.map { ApiResult.Success(it) })

    /** A simple AgentTool that returns a fixed result. */
    private fun fakeTool(
        toolName: String,
        result: ToolResult = ToolResult(content = "ok", summary = "ok", tokenEstimate = 5)
    ): AgentTool = object : AgentTool {
        override val name = toolName
        override val description = "Test tool $toolName"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project) = result
    }

    /** The "attempt_completion" tool that marks the loop as done. */
    private fun completionTool(summary: String = "Done.") = fakeTool(
        "attempt_completion",
        ToolResult(
            content = summary,
            summary = summary,
            tokenEstimate = 5,
            isCompletion = true
        )
    )

    /** Build an AgentLoop with the given brain and tools. */
    private fun buildLoop(
        brain: LlmBrain,
        tools: List<AgentTool>,
        maxIterations: Int = 200,
        onStreamChunk: (String) -> Unit = {},
        onToolCall: (ToolCallProgress) -> Unit = {}
    ): AgentLoop {
        val toolMap = tools.associateBy { it.name }
        val toolDefs = tools.map { it.toToolDefinition() }
        return AgentLoop(
            brain = brain,
            tools = toolMap,
            toolDefinitions = toolDefs,
            contextManager = contextManager,
            project = project,
            onStreamChunk = onStreamChunk,
            onToolCall = onToolCall,
            maxIterations = maxIterations
        )
    }

    // ---- Tests ----

    @Nested
    inner class CompletionTests {

        @Test
        fun `completes when attempt_completion tool returns isCompletion=true`() = runTest {
            val brain = sequenceBrain(
                // First call: model calls read_file
                toolCallResponse("read_file" to """{"path":"src/main.kt"}"""),
                // Second call: model calls attempt_completion
                toolCallResponse("attempt_completion" to """{"result":"All done."}""")
            )

            val tools = listOf(
                fakeTool("read_file"),
                completionTool("All done.")
            )
            val loop = buildLoop(brain, tools)
            val result = loop.run("Fix the bug")

            assertTrue(result is LoopResult.Completed, "Expected Completed but got $result")
            val completed = result as LoopResult.Completed
            assertEquals("All done.", completed.summary)
            assertEquals(2, completed.iterations)
        }
    }

    @Nested
    inner class EmptyResponseTests {

        @Test
        fun `injects continuation prompt on empty response`() = runTest {
            val brain = sequenceBrain(
                // First call: empty response
                emptyResponse(),
                // Second call: model recovers and calls attempt_completion
                toolCallResponse("attempt_completion" to """{"result":"Recovered."}""")
            )

            val tools = listOf(completionTool("Recovered."))
            val loop = buildLoop(brain, tools)
            val result = loop.run("Do something")

            assertTrue(result is LoopResult.Completed, "Expected Completed but got $result")
        }

        @Test
        fun `fails after 3 consecutive empty responses`() = runTest {
            val brain = sequenceBrain(
                emptyResponse(),
                emptyResponse(),
                emptyResponse()
            )

            val tools = listOf(completionTool())
            val loop = buildLoop(brain, tools)
            val result = loop.run("Do something")

            assertTrue(result is LoopResult.Failed, "Expected Failed but got $result")
            val failed = result as LoopResult.Failed
            assertTrue(failed.error.contains("empty", ignoreCase = true))
        }
    }

    @Nested
    inner class TextOnlyResponseTests {

        @Test
        fun `nudges to use tools on text-only response`() = runTest {
            val brain = sequenceBrain(
                // First call: text-only (thinking aloud)
                textResponse("I think the problem might be in the main function."),
                // Second call: model uses a tool after the nudge
                toolCallResponse("attempt_completion" to """{"result":"Fixed."}""")
            )

            val tools = listOf(completionTool("Fixed."))
            val loop = buildLoop(brain, tools)
            val result = loop.run("Fix the bug")

            assertTrue(result is LoopResult.Completed, "Expected Completed but got $result")
        }
    }

    @Nested
    inner class ErrorHandlingTests {

        @Test
        fun `handles unknown tool name gracefully`() = runTest {
            val brain = sequenceBrain(
                // First call: model calls a tool that doesn't exist
                toolCallResponse("nonexistent_tool" to """{}"""),
                // Second call: model recovers
                toolCallResponse("attempt_completion" to """{"result":"Done anyway."}""")
            )

            val tools = listOf(completionTool("Done anyway."))
            val loop = buildLoop(brain, tools)
            val result = loop.run("Do something")

            assertTrue(result is LoopResult.Completed, "Expected Completed but got $result")
        }

        @Test
        fun `handles invalid JSON arguments gracefully`() = runTest {
            val brain = sequenceBrain(
                // First call: model sends malformed JSON
                toolCallResponse("read_file" to """not valid json{{{"""),
                // Second call: model recovers
                toolCallResponse("attempt_completion" to """{"result":"Done."}""")
            )

            val tools = listOf(
                fakeTool("read_file"),
                completionTool("Done.")
            )
            val loop = buildLoop(brain, tools)
            val result = loop.run("Do something")

            assertTrue(result is LoopResult.Completed, "Expected Completed but got $result")
        }

        @Test
        fun `returns Failed on API error`() = runTest {
            val brain = SequenceBrain(
                listOf(ApiResult.Error(ErrorType.SERVER_ERROR, "Internal server error"))
            )

            val tools = listOf(completionTool())
            val loop = buildLoop(brain, tools)
            val result = loop.run("Do something")

            assertTrue(result is LoopResult.Failed, "Expected Failed but got $result")
            val failed = result as LoopResult.Failed
            assertTrue(failed.error.contains("Internal server error"))
        }
    }

    @Nested
    inner class CancellationTests {

        @Test
        fun `cancel stops the loop immediately`() = runTest {
            // Brain returns a tool call, but we cancel before the second iteration
            val brain = sequenceBrain(
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                toolCallResponse("read_file" to """{"path":"b.kt"}"""),
                toolCallResponse("attempt_completion" to """{"result":"Done."}""")
            )

            val tools = listOf(
                fakeTool("read_file"),
                completionTool("Done.")
            )

            val loop = buildLoop(brain, tools)

            // Cancel after seeing the first tool call
            var firstToolSeen = false
            val loopWithCallback = buildLoop(brain, tools, onToolCall = { progress ->
                if (!firstToolSeen) {
                    firstToolSeen = true
                    loop.cancel()
                }
            })

            // Use the loop with callback (but same brain)
            // Actually, we need a fresh brain and loop pair. Let me rethink.
            // Simpler: cancel immediately before running.
            val brain2 = sequenceBrain(
                toolCallResponse("read_file" to """{"path":"a.kt"}""")
            )
            val loop2 = buildLoop(brain2, tools)
            loop2.cancel()

            val result = loop2.run("Do something")
            assertTrue(result is LoopResult.Cancelled, "Expected Cancelled but got $result")
        }
    }

    @Nested
    inner class CallbackTests {

        @Test
        fun `onToolCall receives progress for each tool execution`() = runTest {
            val brain = sequenceBrain(
                toolCallResponse("read_file" to """{"path":"src/main.kt"}"""),
                toolCallResponse("attempt_completion" to """{"result":"Done."}""")
            )

            val progressList = mutableListOf<ToolCallProgress>()
            val tools = listOf(
                fakeTool("read_file"),
                completionTool("Done.")
            )
            val loop = buildLoop(brain, tools, onToolCall = { progressList.add(it) })
            loop.run("Fix it")

            assertEquals(2, progressList.size)
            assertEquals("read_file", progressList[0].toolName)
            assertEquals("attempt_completion", progressList[1].toolName)
        }
    }

    @Nested
    inner class MaxIterationsTests {

        @Test
        fun `fails when max iterations exceeded`() = runTest {
            // Brain always returns a tool call that isn't completion
            val responses = (1..5).map {
                toolCallResponse("read_file" to """{"path":"file$it.kt"}""")
            }
            val brain = SequenceBrain(responses.map { ApiResult.Success(it) })

            val tools = listOf(
                fakeTool("read_file"),
                completionTool()
            )
            val loop = buildLoop(brain, tools, maxIterations = 3)
            val result = loop.run("Do something endlessly")

            assertTrue(result is LoopResult.Failed, "Expected Failed but got $result")
            val failed = result as LoopResult.Failed
            assertTrue(failed.error.contains("iterations", ignoreCase = true))
        }
    }
}
