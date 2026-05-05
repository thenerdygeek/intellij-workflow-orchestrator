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
import com.workflow.orchestrator.core.model.ModelPricingRegistry
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AgentLoopUpstreamTimeoutTest {

    private lateinit var project: Project
    private lateinit var contextManager: ContextManager

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        contextManager = ContextManager(maxInputTokens = 100_000)
    }

    @AfterEach
    fun tearDown() {
        // AgentLoop touches ModelPricingRegistry which starts a FileSystemWatcher;
        // shut it down so macOS ThreadLeakTracker doesn't trip on the watcher
        // thread after the test completes. Same pattern as AgentLoopVisionFallbackTest.
        runCatching { ModelPricingRegistry.resetForTests() }
    }

    private class SequenceBrain(private val responses: List<ApiResult<ChatCompletionResponse>>) : LlmBrain {
        override val modelId: String = "test-model"
        private var callIndex = 0
        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            toolChoice: JsonElement?
        ): ApiResult<ChatCompletionResponse> = throw UnsupportedOperationException()
        override suspend fun chatStream(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            onChunk: suspend (StreamChunk) -> Unit
        ): ApiResult<ChatCompletionResponse> {
            if (callIndex >= responses.size) {
                return ApiResult.Error(ErrorType.SERVER_ERROR, "No more scripted responses")
            }
            return responses[callIndex++]
        }
        override fun estimateTokens(text: String): Int = text.toByteArray().size / 4
        override fun cancelActiveRequest() {}
    }
    private fun sequenceBrain(vararg responses: ChatCompletionResponse) =
        SequenceBrain(responses.map { ApiResult.Success(it) })
    private fun fakeTool(toolName: String, result: ToolResult = ToolResult(content = "ok", summary = "ok", tokenEstimate = 5)): AgentTool =
        object : AgentTool {
            override val name = toolName
            override val description = "Test tool $toolName"
            override val parameters = FunctionParameters(properties = emptyMap())
            override val allowedWorkers = setOf(WorkerType.CODER)
            override suspend fun execute(params: JsonObject, project: Project) = result
        }
    private fun completionTool(summary: String = "Done.") = fakeTool(
        "attempt_completion",
        ToolResult(content = summary, summary = summary, tokenEstimate = 5, isCompletion = true)
    )
    private fun buildLoop(brain: LlmBrain, tools: List<AgentTool>) = AgentLoop(
        brain = brain,
        tools = tools.associateBy { it.name },
        toolDefinitions = tools.map { it.toToolDefinition() },
        contextManager = contextManager,
        project = project,
        onStreamChunk = {},
        onToolCall = {},
        maxIterations = 5,
        planMode = false,
        onCheckpoint = null
    )

    @Test
    fun `upstream_timeout with no tool call adds user-message nudge to chunk smaller`() = runTest {
        val partialContent = "<plan_mode_respond>\n<response>Step 1: read the file..."
        val truncated = ChatCompletionResponse(
            id = "resp-1",
            choices = listOf(Choice(
                index = 0,
                message = ChatMessage(role = "assistant", content = partialContent),
                finishReason = "upstream_timeout"
            )),
            usage = UsageInfo(promptTokens = 100, completionTokens = 50, totalTokens = 150)
        )
        val recovery = ChatCompletionResponse(
            id = "resp-2",
            choices = listOf(Choice(
                index = 0,
                message = ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(ToolCall(
                        id = "call_done",
                        type = "function",
                        function = FunctionCall(name = "attempt_completion", arguments = """{"result":"Recovered."}""")
                    ))
                ),
                finishReason = "tool_calls"
            )),
            usage = UsageInfo(promptTokens = 110, completionTokens = 10, totalTokens = 120)
        )

        val loop = buildLoop(sequenceBrain(truncated, recovery), listOf(completionTool("Recovered.")))
        val result = loop.run("write a long plan")

        assertTrue(result is LoopResult.Completed, "Expected Completed, got $result")

        val msgs = contextManager.getMessages()
        val assistantTurn = msgs.first { it.role == "assistant" && it.content == partialContent }
        assertNotNull(assistantTurn)
        assertTrue(!assistantTurn.content!!.contains("[TOOL_CALL_TRUNCATED]"),
            "Dead sentinel must not be appended any more")
        val partialIdx = msgs.indexOf(assistantTurn)
        val nudge = msgs[partialIdx + 1]
        assertEquals("user", nudge.role)
        assertTrue(nudge.content!!.contains("truncated by an upstream gateway timeout"),
            "Nudge missing timeout phrasing: ${nudge.content}")
        assertTrue(nudge.content!!.contains("smaller chunks"),
            "Nudge missing chunk-smaller copy: ${nudge.content}")
    }

    @Test
    fun `upstream_timeout with in-flight tool call adds tool-result error to chunk smaller`() = runTest {
        val truncated = ChatCompletionResponse(
            id = "resp-3",
            choices = listOf(Choice(
                index = 0,
                message = ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(ToolCall(
                        id = "call_truncated",
                        type = "function",
                        function = FunctionCall(name = "plan_mode_respond", arguments = """{"response":"Step 1..."}""")
                    ))
                ),
                finishReason = "upstream_timeout"
            )),
            usage = UsageInfo(promptTokens = 100, completionTokens = 50, totalTokens = 150)
        )
        val recovery = ChatCompletionResponse(
            id = "resp-4",
            choices = listOf(Choice(
                index = 0,
                message = ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(ToolCall(
                        id = "call_done",
                        type = "function",
                        function = FunctionCall(name = "attempt_completion", arguments = """{"result":"Recovered."}""")
                    ))
                ),
                finishReason = "tool_calls"
            )),
            usage = UsageInfo(promptTokens = 110, completionTokens = 10, totalTokens = 120)
        )

        var planModeExecuted = false
        val planModeTool = object : AgentTool {
            override val name = "plan_mode_respond"
            override val description = "test"
            override val parameters = FunctionParameters(properties = emptyMap())
            override val allowedWorkers = setOf(WorkerType.CODER)
            override suspend fun execute(params: JsonObject, project: Project): ToolResult {
                planModeExecuted = true
                return ToolResult(content = "x", summary = "x", tokenEstimate = 1)
            }
        }

        val loop = buildLoop(sequenceBrain(truncated, recovery), listOf(planModeTool, completionTool("Recovered.")))
        val result = loop.run("draft a plan")

        assertTrue(result is LoopResult.Completed, "Expected Completed, got $result")
        assertTrue(!planModeExecuted, "The truncated tool must not be executed; we synthesize a tool-result instead")

        val msgs = contextManager.getMessages()
        val toolResult = msgs.first { it.role == "tool" && it.toolCallId == "call_truncated" }
        assertNotNull(toolResult)
        val body = toolResult.content!!
        assertTrue(body.startsWith("[ERROR]"), "Tool result must be flagged isError=true: $body")
        assertTrue(body.contains("truncated by an upstream gateway timeout"),
            "Body missing timeout phrasing: $body")
        assertTrue(body.contains("smaller chunks"),
            "Body missing chunk-smaller copy: $body")
    }
}
