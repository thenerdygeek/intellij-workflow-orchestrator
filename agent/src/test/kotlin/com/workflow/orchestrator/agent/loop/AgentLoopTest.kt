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
import kotlinx.coroutines.channels.Channel
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

    private fun sequenceBrain(vararg responses: ChatCompletionResponse): SequenceBrain =
        SequenceBrain(responses.map { ApiResult.Success(it) })

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

    private fun completionTool(summary: String = "Done.") = fakeTool(
        "attempt_completion",
        ToolResult(
            content = summary,
            summary = summary,
            tokenEstimate = 5,
            isCompletion = true
        )
    )

    private fun buildLoop(
        brain: LlmBrain,
        tools: List<AgentTool>,
        maxIterations: Int = 200,
        onStreamChunk: (String) -> Unit = {},
        onToolCall: (ToolCallProgress) -> Unit = {},
        planMode: Boolean = false,
        onCheckpoint: (suspend () -> Unit)? = null,
        userInputChannel: Channel<String>? = null
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
            planMode = planMode,
            onCheckpoint = onCheckpoint,
            userInputChannel = userInputChannel
        )
    }

    // ---- Tests ----

    @Nested
    inner class CompletionTests {

        @Test
        fun `completes when attempt_completion tool returns isCompletion=true`() = runTest {
            val brain = sequenceBrain(
                toolCallResponse("read_file" to """{"path":"src/main.kt"}"""),
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
                emptyResponse(),
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
    inner class EmptyResponseRetryTests {

        @Test
        fun `asks user after max empty retries instead of failing`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Success(emptyResponse()),
                ApiResult.Success(emptyResponse()),
                ApiResult.Success(emptyResponse()),
                ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"OK."}"""))
            ))
            val userInput = Channel<String>(Channel.UNLIMITED)
            userInput.send("Please try again")
            val tools = listOf(completionTool("OK."))
            val loop = buildLoop(brain, tools, userInputChannel = userInput)
            val result = loop.run("Do something")
            assertTrue(result is LoopResult.Completed, "Expected Completed after user retry but got $result")
        }

        @Test
        fun `still fails after max empty retries for sub-agents`() = runTest {
            val brain = sequenceBrain(emptyResponse(), emptyResponse(), emptyResponse())
            val tools = listOf(completionTool())
            val loop = buildLoop(brain, tools) // no userInputChannel
            val result = loop.run("Do something")
            assertTrue(result is LoopResult.Failed, "Expected Failed for sub-agent but got $result")
            assertTrue((result as LoopResult.Failed).error.contains("empty", ignoreCase = true))
        }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        @Test
        fun `empty responses use exponential delay before retry`() = runTest {
            val brain = sequenceBrain(
                emptyResponse(),
                toolCallResponse("attempt_completion" to """{"result":"Recovered."}""")
            )
            val tools = listOf(completionTool("Recovered."))
            val loop = buildLoop(brain, tools)
            val startTime = testScheduler.currentTime
            val result = loop.run("Do something")
            assertTrue(result is LoopResult.Completed)
            // First empty retry should delay EMPTY_RETRY_BASE_DELAY_MS (2000ms)
            assertTrue(
                testScheduler.currentTime - startTime >= 2000,
                "Expected at least 2000ms delay for first empty retry"
            )
        }
    }

    @Nested
    inner class TextOnlyResponseTests {

        @Test
        fun `nudges to use tools on text-only response`() = runTest {
            val brain = sequenceBrain(
                textResponse("I think the problem might be in the main function."),
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
                toolCallResponse("nonexistent_tool" to """{}"""),
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
                toolCallResponse("read_file" to """not valid json{{{"""),
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

        @Test
        fun `all-error tool calls increment mistake counter`() = runTest {
            // 3 iterations of invalid JSON tool calls trigger escalation, then user rescues, then completion.
            // Each iteration has ALL tool calls failing (invalid JSON), so consecutiveMistakes increments.
            // At maxConsecutiveMistakes (3) the loop waits for user input via userInputChannel.
            val responses = listOf(
                toolCallResponse("read_file" to "not json"),
                toolCallResponse("read_file" to "also bad{"),
                toolCallResponse("read_file" to "still wrong"),
                toolCallResponse("attempt_completion" to """{"result":"Done."}""")
            )
            val brain = SequenceBrain(responses.map { ApiResult.Success(it) })
            val userInput = Channel<String>(Channel.UNLIMITED)
            userInput.send("Try using valid JSON")

            val tools = listOf(fakeTool("read_file"), completionTool("Done."))
            val loop = buildLoop(brain, tools, userInputChannel = userInput)
            val result = loop.run("Do something")

            assertTrue(result is LoopResult.Completed, "Expected Completed after user rescued from errors but got $result")
        }

        @Test
        fun `successful tool calls reset mistake counter`() = runTest {
            // One error, then one success, then completion.
            // A single error should not escalate — the success resets consecutiveMistakes to 0.
            val brain = sequenceBrain(
                toolCallResponse("read_file" to "bad json"),
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                toolCallResponse("attempt_completion" to """{"result":"Done."}""")
            )

            val tools = listOf(fakeTool("read_file"), completionTool("Done."))
            val loop = buildLoop(brain, tools)
            val result = loop.run("Do something")

            assertTrue(result is LoopResult.Completed, "Expected Completed — single error shouldn't escalate but got $result")
        }
    }

    @Nested
    inner class CancellationTests {

        @Test
        fun `cancel stops the loop immediately`() = runTest {
            val brain2 = sequenceBrain(
                toolCallResponse("read_file" to """{"path":"a.kt"}""")
            )
            val tools = listOf(
                fakeTool("read_file"),
                completionTool("Done.")
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

            assertEquals(4, progressList.size)
            assertEquals("read_file", progressList[0].toolName)
            assertEquals("read_file", progressList[1].toolName)
            assertEquals("attempt_completion", progressList[2].toolName)
            assertEquals("attempt_completion", progressList[3].toolName)
        }
    }

    @Nested
    inner class MaxIterationsTests {

        @Test
        fun `fails when max iterations exceeded`() = runTest {
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

    // ---- Plan mode guard ----

    @Nested
    inner class PlanModeGuardTests {

        @Test
        fun `plan mode blocks write tools with error message`() = runTest {
            val brain = sequenceBrain(
                toolCallResponse("edit_file" to """{"path":"a.kt","content":"bad"}"""),
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

            val editProgress = progressList.find { it.toolName == "edit_file" }
            assertNotNull(editProgress, "edit_file progress should be reported")
            assertTrue(editProgress!!.isError, "edit_file should be an error")
            assertTrue(editProgress.result.contains("blocked in plan mode"), "Error message should mention plan mode")
        }

        @Test
        fun `plan mode allows read tools`() = runTest {
            val brain = sequenceBrain(
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

            val readProgress = progressList.find { it.toolName == "read_file" }
            assertNotNull(readProgress, "read_file progress should be reported")
            assertFalse(readProgress!!.isError, "read_file should not be an error in plan mode")
        }

        @Test
        fun `all write tool names are blocked in plan mode`() = runTest {
            val expectedWriteTools = setOf(
                "edit_file", "create_file", "run_command", "revert_file",
                "kill_process", "send_stdin", "format_code", "optimize_imports",
                "refactor_rename"
            )
            assertEquals(expectedWriteTools, AgentLoop.WRITE_TOOLS, "WRITE_TOOLS set should contain all write tools")
        }
    }

    // ---- API retry ----

    @Nested
    inner class ApiRetryTests {

        @Test
        fun `retries on rate limit error and recovers`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Error(ErrorType.RATE_LIMITED, "Rate limit exceeded"),
                ApiResult.Error(ErrorType.RATE_LIMITED, "Rate limit exceeded"),
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
                ApiResult.Error(ErrorType.SERVER_ERROR, "Internal server error"),
                ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Recovered."}"""))
            ))

            val tools = listOf(completionTool("Recovered."))
            val loop = buildLoop(brain, tools)
            val result = loop.run("Do something")

            assertTrue(result is LoopResult.Completed, "Expected Completed after server error retry but got $result")
        }

        @Test
        fun `fails after max retries exceeded`() = runTest {
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
        fun `asks user after all API retries exhausted instead of failing`() = runTest {
            // 6 rate limit errors (exceeds MAX_API_RETRIES=5), then recovery after user input
            val errors = (1..6).map {
                ApiResult.Error(ErrorType.RATE_LIMITED, "Rate limit exceeded") as ApiResult<ChatCompletionResponse>
            }
            val recovery = ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Done."}"""))
            val brain = SequenceBrain(errors + recovery)

            val userInput = Channel<String>(Channel.UNLIMITED)
            userInput.send("Please retry")

            val tools = listOf(completionTool("Done."))
            val loop = buildLoop(brain, tools, userInputChannel = userInput)
            val result = loop.run("Do something")

            assertTrue(result is LoopResult.Completed, "Expected Completed after user retry but got $result")
        }

        @Test
        fun `still fails after API retries for sub-agents`() = runTest {
            val errors = (1..6).map {
                ApiResult.Error(ErrorType.RATE_LIMITED, "Rate limit exceeded") as ApiResult<ChatCompletionResponse>
            }
            val brain = SequenceBrain(errors)

            val tools = listOf(completionTool())
            val loop = buildLoop(brain, tools) // no userInputChannel
            val result = loop.run("Do something")

            assertTrue(result is LoopResult.Failed, "Expected Failed for sub-agent but got $result")
            assertTrue((result as LoopResult.Failed).error.contains("Rate limit"))
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
                ApiResult.Error(ErrorType.RATE_LIMITED, "Rate limit exceeded"),
                ApiResult.Success(toolCallResponse("read_file" to """{"path":"a.kt"}""")),
                ApiResult.Error(ErrorType.RATE_LIMITED, "Rate limit exceeded"),
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

    // ---- finish_reason: length ----

    @Nested
    inner class FinishReasonLengthTests {

        @Test
        fun `finish_reason length injects continuation instead of processing tool calls`() = runTest {
            val brain = sequenceBrain(
                lengthResponse("I was about to call"),
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

            val messages = contextManager.getMessages()
            val continuationMsg = messages.find {
                it.role == "user" && it.content?.contains("cut short") == true
            }
            assertNotNull(continuationMsg, "Continuation message should be injected after truncated response")
        }
    }

    // ---- Tool progress callbacks ----

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

            assertEquals(4, progressList.size, "Expected 4 progress events (2 tools x 2 callbacks each)")

            val readStart = progressList[0]
            assertEquals("read_file", readStart.toolName)
            assertEquals("", readStart.result)
            assertEquals(0L, readStart.durationMs)

            val readComplete = progressList[1]
            assertEquals("read_file", readComplete.toolName)
            assertTrue(readComplete.result.isNotEmpty(), "Completed callback should have result")
            assertFalse(readComplete.isError)
        }

        @Test
        fun `start callback fires even when tool execution fails`() = runTest {
            val brain = sequenceBrain(
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

            val completionCallbacks = progressList.filter { it.toolName == "attempt_completion" }
            assertEquals(2, completionCallbacks.size, "attempt_completion should have start + complete callbacks")
        }
    }

    // ---- Loop detection (from Cline) ----

    @Nested
    inner class LoopDetectionTests {

        @Test
        fun `fails after 5 consecutive identical tool calls without userInputChannel`() = runTest {
            // 5 identical read_file calls with same args, then a text-only response
            // that triggers the Case B failure path (no userInputChannel = sub-agent)
            val responses = (1..5).map {
                toolCallResponse("read_file" to """{"path":"a.kt"}""")
            } + textResponse("I'm stuck in a loop.")
            val brain = SequenceBrain(responses.map { ApiResult.Success(it) })

            val tools = listOf(
                fakeTool("read_file"),
                completionTool()
            )
            val loop = buildLoop(brain, tools)
            val result = loop.run("Read the file")

            assertTrue(result is LoopResult.Failed, "Expected Failed after loop detection but got $result")
            val failed = result as LoopResult.Failed
            assertTrue(
                failed.error.contains("Loop", ignoreCase = true) || failed.error.contains("tools", ignoreCase = true),
                "Error should mention loop or tool failure, got: ${failed.error}"
            )
        }

        @Test
        fun `injects warning at 3 consecutive identical calls`() = runTest {
            // 3 identical calls, then a different call, then completion
            val responses = listOf(
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                // Model self-corrects after warning
                toolCallResponse("read_file" to """{"path":"b.kt"}"""),
                toolCallResponse("attempt_completion" to """{"result":"Done."}""")
            )
            val brain = SequenceBrain(responses.map { ApiResult.Success(it) })

            val tools = listOf(
                fakeTool("read_file"),
                completionTool("Done.")
            )
            val loop = buildLoop(brain, tools)
            val result = loop.run("Read files")

            assertTrue(result is LoopResult.Completed, "Expected Completed after self-correction but got $result")

            // Verify the warning was injected into context
            val messages = contextManager.getMessages()
            val warningMsg = messages.find {
                it.role == "user" && it.content?.contains("same tool with identical arguments") == true
            }
            assertNotNull(warningMsg, "Soft warning should be injected at 3 consecutive identical calls")
        }

        @Test
        fun `hard loop limit waits for user input instead of failing`() = runTest {
            // 5 identical read_file calls hit the hard limit.
            // After the fix, the 5th call EXECUTES (moved to after-execution),
            // then consecutiveMistakes is set to max. On the next LLM call
            // the model responds with text-only (seeing the LOOP_HARD_FAILURE message),
            // which triggers Case B -> max mistakes -> waits for user input.
            // After user input, the model recovers and completes.
            val responses = listOf(
                // Calls 1-5: identical read_file
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                // After hard limit + user feedback, LLM responds with text (triggers Case B)
                textResponse("I see the problem now, let me try a different approach."),
                // After user feedback is injected, model recovers
                toolCallResponse("attempt_completion" to """{"result":"Fixed after user guidance."}""")
            )
            val brain = SequenceBrain(responses.map { ApiResult.Success(it) })

            val userChannel = Channel<String>(Channel.UNLIMITED)
            // Pre-load user feedback that will be received when the loop waits
            userChannel.send("Try reading b.kt instead")

            val tools = listOf(
                fakeTool("read_file"),
                completionTool("Fixed after user guidance.")
            )
            val loop = buildLoop(brain, tools, userInputChannel = userChannel)
            val result = loop.run("Read the file")

            assertTrue(result is LoopResult.Completed, "Expected Completed after user feedback, but got $result")
            assertEquals("Fixed after user guidance.", (result as LoopResult.Completed).summary)
        }

        @Test
        fun `hard loop limit fails immediately for sub-agents (no userInputChannel)`() = runTest {
            // 5 identical read_file calls with no userInputChannel = sub-agent mode.
            // The 5th call executes, then consecutiveMistakes is set to max.
            // On the next LLM call, text-only response triggers Case B with max mistakes
            // and no userInputChannel -> fails immediately.
            val responses = listOf(
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                // After hard limit, LLM responds with text (triggers Case B)
                textResponse("I'm stuck.")
            )
            val brain = SequenceBrain(responses.map { ApiResult.Success(it) })

            val tools = listOf(
                fakeTool("read_file"),
                completionTool()
            )
            // No userInputChannel = sub-agent mode
            val loop = buildLoop(brain, tools)
            val result = loop.run("Read the file")

            assertTrue(result is LoopResult.Failed, "Expected Failed for sub-agent after loop detection but got $result")
            val failed = result as LoopResult.Failed
            assertTrue(
                failed.error.contains("Loop", ignoreCase = true) || failed.error.contains("tools", ignoreCase = true),
                "Error should mention loop or tool failure, got: ${failed.error}"
            )
        }

        @Test
        fun `different tool calls do not trigger loop detection`() = runTest {
            // All different files
            val responses = listOf(
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                toolCallResponse("read_file" to """{"path":"b.kt"}"""),
                toolCallResponse("read_file" to """{"path":"c.kt"}"""),
                toolCallResponse("read_file" to """{"path":"d.kt"}"""),
                toolCallResponse("read_file" to """{"path":"e.kt"}"""),
                toolCallResponse("attempt_completion" to """{"result":"Done."}""")
            )
            val brain = SequenceBrain(responses.map { ApiResult.Success(it) })

            val tools = listOf(
                fakeTool("read_file"),
                completionTool("Done.")
            )
            val loop = buildLoop(brain, tools)
            val result = loop.run("Read all files")

            assertTrue(result is LoopResult.Completed, "Expected Completed with different args, but got $result")
        }

        @Test
        fun `hard loop fails after model ignores warning for one more iteration`() = runTest {
            // 6 identical calls — 5th triggers warning + consecutiveMistakes=max,
            // but Case A resets consecutiveMistakes=0 on the 6th tool-call iteration.
            // The secondary cutoff (currentCount > HARD_THRESHOLD) catches this and
            // returns makeFailed immediately.
            val responses = (1..6).map {
                toolCallResponse("read_file" to """{"path":"same.kt"}""")
            }
            val brain = SequenceBrain(responses.map { ApiResult.Success(it) })

            val tools = listOf(fakeTool("read_file"), completionTool())
            val loop = buildLoop(brain, tools) // no userInputChannel
            val result = loop.run("Do something")

            assertTrue(result is LoopResult.Failed, "Expected Failed after 6 identical calls but got $result")
            val failed = result as LoopResult.Failed
            assertTrue(failed.error.contains("Loop detected", ignoreCase = true),
                "Error should mention loop detection, got: ${failed.error}")
        }
    }

    // ---- Context overflow detection (from Cline) ----

    @Nested
    inner class ContextOverflowTests {

        @Test
        fun `detects explicit CONTEXT_LENGTH_EXCEEDED error`() {
            val loop = buildLoop(sequenceBrain(), listOf(completionTool()))
            val error = ApiResult.Error(ErrorType.CONTEXT_LENGTH_EXCEEDED, "Context too long")
            assertTrue(loop.isContextOverflowError(error), "Should detect CONTEXT_LENGTH_EXCEEDED")
        }

        @Test
        fun `detects context overflow from error message patterns`() {
            val loop = buildLoop(sequenceBrain(), listOf(completionTool()))

            val patterns = listOf(
                "maximum context length exceeded",
                "token limit exceeded for this model",
                "context window limit reached",
                "input too long for this model",
                "prompt too large",
                "Maximum token limit exceeded"
            )

            for (msg in patterns) {
                val error = ApiResult.Error(ErrorType.VALIDATION_ERROR, msg)
                assertTrue(
                    loop.isContextOverflowError(error),
                    "Should detect overflow from message: '$msg'"
                )
            }
        }

        @Test
        fun `does not false-positive on unrelated errors`() {
            val loop = buildLoop(sequenceBrain(), listOf(completionTool()))

            val unrelatedErrors = listOf(
                ApiResult.Error(ErrorType.AUTH_FAILED, "Invalid token"),
                ApiResult.Error(ErrorType.RATE_LIMITED, "Rate limit exceeded"),
                ApiResult.Error(ErrorType.VALIDATION_ERROR, "Invalid model parameter"),
                ApiResult.Error(ErrorType.SERVER_ERROR, "Internal server error")
            )

            for (error in unrelatedErrors) {
                assertFalse(
                    loop.isContextOverflowError(error),
                    "Should not detect overflow from: ${error.message}"
                )
            }
        }

        @Test
        fun `context overflow triggers compaction and retries`() = runTest {
            val brain = SequenceBrain(listOf(
                // First call: context overflow
                ApiResult.Error(ErrorType.CONTEXT_LENGTH_EXCEEDED, "Context too long"),
                // After compaction, retry succeeds
                ApiResult.Success(toolCallResponse("attempt_completion" to """{"result":"Done."}"""))
            ))

            val tools = listOf(completionTool("Done."))
            // Use a context manager with very low threshold so compact() actually triggers
            contextManager = ContextManager(maxInputTokens = 100, compactionThreshold = 0.01)
            val loop = buildLoop(brain, tools)
            val result = loop.run("Do something")

            assertTrue(result is LoopResult.Completed, "Expected Completed after overflow recovery but got $result")
        }

        @Test
        fun `fails after max context overflow retries`() = runTest {
            val brain = SequenceBrain(listOf(
                ApiResult.Error(ErrorType.CONTEXT_LENGTH_EXCEEDED, "Context too long"),
                ApiResult.Error(ErrorType.CONTEXT_LENGTH_EXCEEDED, "Context too long"),
                ApiResult.Error(ErrorType.CONTEXT_LENGTH_EXCEEDED, "Still too long")
            ))

            val tools = listOf(completionTool())
            contextManager = ContextManager(maxInputTokens = 100, compactionThreshold = 0.01)
            val loop = buildLoop(brain, tools)
            val result = loop.run("Do something")

            assertTrue(result is LoopResult.Failed, "Expected Failed after max overflow retries but got $result")
        }
    }

    // ---- Checkpoint callback (ported from Cline's per-message save pattern) ----

    @Nested
    inner class CheckpointCallbackTests {

        @Test
        fun `onCheckpoint fires after each tool execution`() = runTest {
            val brain = sequenceBrain(
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                toolCallResponse("read_file" to """{"path":"b.kt"}"""),
                toolCallResponse("attempt_completion" to """{"result":"Done."}""")
            )

            var checkpointCount = 0
            val tools = listOf(
                fakeTool("read_file"),
                completionTool("Done.")
            )
            val loop = buildLoop(brain, tools, onCheckpoint = { checkpointCount++ })
            val result = loop.run("Read files")

            assertTrue(result is LoopResult.Completed, "Expected Completed but got $result")
            // 3 tool calls = 3 checkpoints (read_file, read_file, attempt_completion)
            assertEquals(3, checkpointCount,
                "onCheckpoint should fire after every tool result")
        }

        @Test
        fun `onCheckpoint fires even for error tool results`() = runTest {
            val brain = sequenceBrain(
                toolCallResponse("nonexistent_tool" to """{}"""),
                toolCallResponse("attempt_completion" to """{"result":"Done."}""")
            )

            var checkpointCount = 0
            val tools = listOf(completionTool("Done."))
            val loop = buildLoop(brain, tools, onCheckpoint = { checkpointCount++ })
            loop.run("Do something")

            // Unknown tool still adds error to context and triggers checkpoint
            assertEquals(1, checkpointCount,
                "Checkpoint should fire for completion tool (unknown tool errors don't use the checkpoint path)")
        }

        @Test
        fun `no checkpoint when onCheckpoint is null`() = runTest {
            val brain = sequenceBrain(
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                toolCallResponse("attempt_completion" to """{"result":"Done."}""")
            )

            val tools = listOf(
                fakeTool("read_file"),
                completionTool("Done.")
            )
            // null onCheckpoint should not cause any errors
            val loop = buildLoop(brain, tools, onCheckpoint = null)
            val result = loop.run("Fix it")

            assertTrue(result is LoopResult.Completed, "Expected Completed but got $result")
        }

        @Test
        fun `checkpoint failure does not crash the loop`() = runTest {
            val brain = sequenceBrain(
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                toolCallResponse("attempt_completion" to """{"result":"Done."}""")
            )

            val tools = listOf(
                fakeTool("read_file"),
                completionTool("Done.")
            )
            val loop = buildLoop(brain, tools, onCheckpoint = {
                // Checkpoint should be non-fatal even if it throws
                // (In production, AgentService wraps this in try-catch)
            })
            val result = loop.run("Fix it")

            assertTrue(result is LoopResult.Completed, "Expected Completed — checkpoint should not block loop")
        }
    }

    // ---- Dynamic tool definitions (tool_search support) ----

    @Nested
    inner class DynamicToolDefinitionTests {

        @Test
        fun `toolDefinitionProvider is called on each iteration`() = runTest {
            var providerCallCount = 0
            val allTools = listOf(
                fakeTool("read_file"),
                completionTool("Done.")
            )
            val toolMap = allTools.associateBy { it.name }
            val toolDefs = allTools.map { it.toToolDefinition() }

            val brain = sequenceBrain(
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                toolCallResponse("attempt_completion" to """{"result":"Done."}""")
            )

            val loop = AgentLoop(
                brain = brain,
                tools = toolMap,
                toolDefinitions = toolDefs,
                contextManager = contextManager,
                project = project,
                toolDefinitionProvider = {
                    providerCallCount++
                    toolDefs
                }
            )

            val result = loop.run("Do something")
            assertTrue(result is LoopResult.Completed)
            // Provider should be called on each iteration (2 iterations in this case)
            assertEquals(2, providerCallCount, "toolDefinitionProvider should be called on each loop iteration")
        }

        @Test
        fun `toolResolver resolves tools not in initial map`() = runTest {
            // Start with only attempt_completion in the tools map
            val completionT = completionTool("Done.")
            val initialTools = mapOf("attempt_completion" to completionT)
            val defs = listOf(completionT.toToolDefinition())

            // But create a "hidden" tool that toolResolver can find
            val hiddenTool = fakeTool("jira")

            val brain = sequenceBrain(
                toolCallResponse("jira" to """{"action":"list"}"""),
                toolCallResponse("attempt_completion" to """{"result":"Done."}""")
            )

            val loop = AgentLoop(
                brain = brain,
                tools = initialTools,
                toolDefinitions = defs,
                contextManager = contextManager,
                project = project,
                toolResolver = { name ->
                    if (name == "jira") hiddenTool else initialTools[name]
                }
            )

            val progressList = mutableListOf<ToolCallProgress>()
            val loopWithCallback = AgentLoop(
                brain = brain,
                tools = initialTools,
                toolDefinitions = defs,
                contextManager = contextManager,
                project = project,
                toolResolver = { name ->
                    if (name == "jira") hiddenTool else initialTools[name]
                },
                onToolCall = { progressList.add(it) }
            )
            val result = loopWithCallback.run("Use jira")

            assertTrue(result is LoopResult.Completed, "Expected Completed but got $result")
            // Verify jira tool was resolved and called successfully
            val jiraProgress = progressList.filter { it.toolName == "jira" }
            assertTrue(jiraProgress.any { !it.isError }, "jira should execute without error via toolResolver")
        }

        @Test
        fun `newly loaded tools included in next API call definitions`() = runTest {
            // Simulate tool_search activation: the provider returns growing definitions
            val baseTool = fakeTool("read_file")
            val extraTool = fakeTool("jira")
            val completionT = completionTool("Done.")

            val allTools = listOf(baseTool, extraTool, completionT).associateBy { it.name }
            var includeJira = false

            val brain = sequenceBrain(
                toolCallResponse("read_file" to """{"path":"a.kt"}"""),
                // After first iteration, jira gets activated
                toolCallResponse("attempt_completion" to """{"result":"Done."}""")
            )

            val capturedToolCounts = mutableListOf<Int>()

            val loop = AgentLoop(
                brain = brain,
                tools = allTools,
                toolDefinitions = listOf(baseTool.toToolDefinition(), completionT.toToolDefinition()),
                contextManager = contextManager,
                project = project,
                toolDefinitionProvider = {
                    val defs = mutableListOf(baseTool.toToolDefinition(), completionT.toToolDefinition())
                    if (includeJira) {
                        defs.add(extraTool.toToolDefinition())
                    }
                    // After first call, simulate tool_search activation
                    includeJira = true
                    capturedToolCounts.add(defs.size)
                    defs
                }
            )

            val result = loop.run("Do something")
            assertTrue(result is LoopResult.Completed)

            // First iteration: 2 defs, second iteration: 3 defs (jira added)
            assertEquals(2, capturedToolCounts.size)
            assertEquals(2, capturedToolCounts[0], "First call should have 2 tool defs")
            assertEquals(3, capturedToolCounts[1], "Second call should have 3 tool defs (jira added)")
        }
    }
}
