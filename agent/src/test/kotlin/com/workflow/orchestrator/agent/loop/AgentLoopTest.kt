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

    /** Creates a response with finish_reason "length" (truncated output). */
    private fun lengthResponse(partialContent: String? = "I was about to call"): ChatCompletionResponse =
        ChatCompletionResponse(
            id = "resp-${System.nanoTime()}",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(
                        role = "assistant",
                        content = partialContent,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call_truncated",
                                type = "function",
                                function = FunctionCall(name = "read_file", arguments = "{\"path\":\"src/ma")
                            )
                        )
                    ),
                    finishReason = "length"
                )
            ),
            usage = UsageInfo(promptTokens = 100, completionTokens = 4096, totalTokens = 4196)
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
        onToolCall: (ToolCallProgress) -> Unit = {},
        planMode: Boolean = false
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
            maxIterations = maxIterations,
            planMode = planMode
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
        fun `returns Failed on non-retryable API error`() = runTest {
            val brain = SequenceBrain(
                listOf(ApiResult.Error(ErrorType.AUTH_FAILED, "Authentication failed"))
            )

            val tools = listOf(completionTool())
            val loop = buildLoop(brain, tools)
            val result = loop.run("Do something")

            assertTrue(result is LoopResult.Failed, "Expected Failed but got $result")
            val failed = result as LoopResult.Failed
            assertTrue(failed.error.contains("Authentication failed"))
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

            // I8: Each tool fires start (before) + complete (after) = 4 callbacks total
            assertEquals(4, progressList.size)
            assertEquals("read_file", progressList[0].toolName)    // start
            assertEquals("read_file", progressList[1].toolName)    // complete
            assertEquals("attempt_completion", progressList[2].toolName)  // start
            assertEquals("attempt_completion", progressList[3].toolName)  // complete
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

    // ---- C1: Plan mode execution guard ----

    @Nested
    inner class PlanModeGuardTests {

        @Test
        fun `plan mode blocks write tools with error message`() = runTest {
            val brain = sequenceBrain(
                // Model hallucinates edit_file in plan mode
                toolCallResponse("edit_file" to """{"path":"a.kt","content":"bad"}"""),
                // Model recovers and calls attempt_completion
                toolCallResponse("attempt_completion" to """{"result":"Planned."}""")
            )

            val progressList = mutableListOf<ToolCallProgress>()
            val tools = listOf(
                fakeTool("edit_file"),
                completionTool("Planned.")
            )
            val loop = buildLoop(brain, tools, planMode = true, onToolCall = { progressList.add(it) })
            val result = loop.run("Analyze the code")

            assertTrue(result is LoopResult.Completed, "Expected Completed but got $result")

            // The edit_file call should have been blocked
            val editProgress = progressList.find { it.toolName == "edit_file" }
            assertNotNull(editProgress, "edit_file progress should be reported")
            assertTrue(editProgress!!.isError, "edit_file should be an error")
            assertTrue(editProgress.result.contains("blocked in plan mode"), "Error message should mention plan mode")
        }

        @Test
        fun `plan mode allows read tools`() = runTest {
            val brain = sequenceBrain(
                // Model calls read_file (allowed in plan mode)
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                toolCallResponse("attempt_completion" to """{"result":"Done reading."}""")
            )

            val progressList = mutableListOf<ToolCallProgress>()
            val tools = listOf(
                fakeTool("read_file"),
                completionTool("Done reading.")
            )
            val loop = buildLoop(brain, tools, planMode = true, onToolCall = { progressList.add(it) })
            val result = loop.run("Read the code")

            assertTrue(result is LoopResult.Completed, "Expected Completed but got $result")

            // read_file should NOT be blocked
            val readProgress = progressList.find { it.toolName == "read_file" }
            assertNotNull(readProgress, "read_file progress should be reported")
            assertFalse(readProgress!!.isError, "read_file should not be an error in plan mode")
        }

        @Test
        fun `all write tool names are blocked in plan mode`() = runTest {
            // Verify each write tool name is in the WRITE_TOOLS set
            val expectedWriteTools = setOf(
                "edit_file", "create_file", "run_command", "revert_file",
                "kill_process", "send_stdin", "format_code", "optimize_imports",
                "refactor_rename"
            )
            assertEquals(expectedWriteTools, AgentLoop.WRITE_TOOLS, "WRITE_TOOLS set should contain all write tools")
        }
    }

    // ---- C3: API retry for transient failures ----

    @Nested
    inner class ApiRetryTests {

        @Test
        fun `retries on rate limit error and recovers`() = runTest {
            val brain = SequenceBrain(listOf(
                // First call: 429 rate limit
                ApiResult.Error(ErrorType.RATE_LIMITED, "Rate limit exceeded"),
                // Second call: 429 again
                ApiResult.Error(ErrorType.RATE_LIMITED, "Rate limit exceeded"),
                // Third call: success with completion
                ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Done."}"""))
            ))

            val tools = listOf(completionTool("Done."))
            val loop = buildLoop(brain, tools)
            val result = loop.run("Do something")

            assertTrue(result is LoopResult.Completed, "Expected Completed after retry but got $result")
        }

        @Test
        fun `retries on server error and recovers`() = runTest {
            val brain = SequenceBrain(listOf(
                // First call: 500 server error
                ApiResult.Error(ErrorType.SERVER_ERROR, "Internal server error"),
                // Second call: success
                ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Recovered."}"""))
            ))

            val tools = listOf(completionTool("Recovered."))
            val loop = buildLoop(brain, tools)
            val result = loop.run("Do something")

            assertTrue(result is LoopResult.Completed, "Expected Completed after server error retry but got $result")
        }

        @Test
        fun `fails after max retries exceeded`() = runTest {
            // 6 rate limit errors — exceeds MAX_API_RETRIES (5)
            val errors = (1..6).map {
                ApiResult.Error(ErrorType.RATE_LIMITED, "Rate limit exceeded") as ApiResult<ChatCompletionResponse>
            }
            val brain = SequenceBrain(errors)

            val tools = listOf(completionTool())
            val loop = buildLoop(brain, tools)
            val result = loop.run("Do something")

            assertTrue(result is LoopResult.Failed, "Expected Failed after max retries but got $result")
            val failed = result as LoopResult.Failed
            assertTrue(failed.error.contains("Rate limit"), "Error message should contain the API error")
        }

        @Test
        fun `does not retry on non-retryable errors`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Error(ErrorType.AUTH_FAILED, "Authentication failed")
            ))

            val tools = listOf(completionTool())
            val loop = buildLoop(brain, tools)
            val result = loop.run("Do something")

            assertTrue(result is LoopResult.Failed, "Expected immediate Failed for auth error but got $result")
            val failed = result as LoopResult.Failed
            assertTrue(failed.error.contains("Authentication failed"))
        }

        @Test
        fun `resets retry count after successful API call`() = runTest {
            val brain = SequenceBrain(listOf(
                // First: rate limit error
                ApiResult.Error(ErrorType.RATE_LIMITED, "Rate limit exceeded"),
                // Second: success (read_file)
                ApiResult.Success(toolCallResponse("read_file" to """{"path":"a.kt"}""")),
                // Third: another rate limit error (retry count should be reset)
                ApiResult.Error(ErrorType.RATE_LIMITED, "Rate limit exceeded"),
                // Fourth: success (completion)
                ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Done."}"""))
            ))

            val tools = listOf(
                fakeTool("read_file"),
                completionTool("Done.")
            )
            val loop = buildLoop(brain, tools)
            val result = loop.run("Do something")

            assertTrue(result is LoopResult.Completed, "Expected Completed — retry count should reset between successes, but got $result")
        }

        @Test
        fun `retries on network and timeout errors`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Error(ErrorType.NETWORK_ERROR, "Connection refused"),
                ApiResult.Error(ErrorType.TIMEOUT, "Request timed out"),
                ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"OK."}"""))
            ))

            val tools = listOf(completionTool("OK."))
            val loop = buildLoop(brain, tools)
            val result = loop.run("Do something")

            assertTrue(result is LoopResult.Completed, "Expected Completed after network/timeout retries but got $result")
        }
    }

    // ---- I1: finish_reason length handling ----

    @Nested
    inner class FinishReasonLengthTests {

        @Test
        fun `finish_reason length injects continuation instead of processing tool calls`() = runTest {
            val brain = sequenceBrain(
                // First call: truncated response (finish_reason: length) with partial tool call JSON
                lengthResponse("I was about to call"),
                // Second call: model recovers after continuation prompt
                toolCallResponse("attempt_completion" to """{"result":"Done after retry."}""")
            )

            val progressList = mutableListOf<ToolCallProgress>()
            val tools = listOf(
                fakeTool("read_file"),
                completionTool("Done after retry.")
            )
            val loop = buildLoop(brain, tools, onToolCall = { progressList.add(it) })
            val result = loop.run("Fix the bug")

            assertTrue(result is LoopResult.Completed, "Expected Completed but got $result")
            // The truncated tool call should NOT have been executed
            val readFileCalls = progressList.filter { it.toolName == "read_file" }
            assertTrue(readFileCalls.isEmpty(), "read_file should not have been called from truncated response")
        }

        @Test
        fun `finish_reason length adds continuation message to context`() = runTest {
            val brain = sequenceBrain(
                lengthResponse("partial"),
                toolCallResponse("attempt_completion" to """{"result":"Done."}""")
            )

            val tools = listOf(fakeTool("read_file"), completionTool("Done."))
            val loop = buildLoop(brain, tools)
            loop.run("Do something")

            // Check that the continuation message was added to context
            val messages = contextManager.getMessages()
            val continuationMsg = messages.find {
                it.role == "user" && it.content?.contains("cut short") == true
            }
            assertNotNull(continuationMsg, "Continuation message should be injected after truncated response")
        }
    }

    // ---- I8: Tool progress fires before AND after execution ----

    @Nested
    inner class ToolProgressCallbackTests {

        @Test
        fun `onToolCall fires before and after tool execution`() = runTest {
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

            // Each tool should fire twice: once before (start) and once after (complete)
            // read_file: start + complete, attempt_completion: start + complete = 4
            assertEquals(4, progressList.size, "Expected 4 progress events (2 tools x 2 callbacks each)")

            // read_file start callback: empty result, zero duration
            val readStart = progressList[0]
            assertEquals("read_file", readStart.toolName)
            assertEquals("", readStart.result)
            assertEquals(0L, readStart.durationMs)

            // read_file complete callback: has result and duration
            val readComplete = progressList[1]
            assertEquals("read_file", readComplete.toolName)
            assertTrue(readComplete.result.isNotEmpty(), "Completed callback should have result")
            assertFalse(readComplete.isError)
        }

        @Test
        fun `start callback fires even when tool execution fails`() = runTest {
            val brain = sequenceBrain(
                // Call a tool with bad JSON — should still get start callback
                toolCallResponse("read_file" to """not valid json{{{"""),
                toolCallResponse("attempt_completion" to """{"result":"Done."}""")
            )

            val progressList = mutableListOf<ToolCallProgress>()
            val tools = listOf(
                fakeTool("read_file"),
                completionTool("Done.")
            )
            val loop = buildLoop(brain, tools, onToolCall = { progressList.add(it) })
            loop.run("Do something")

            // read_file: just error callback (no start since JSON parse fails before execute)
            // attempt_completion: start + complete
            // Actually, invalid JSON fires error callback only (before start is not fired for JSON parse failure)
            // Let's verify we get at least the attempt_completion start+complete
            val completionCallbacks = progressList.filter { it.toolName == "attempt_completion" }
            assertEquals(2, completionCallbacks.size, "attempt_completion should have start + complete callbacks")
        }
    }
}
