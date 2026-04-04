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
 * Tests for LoopResult.SessionHandoff in AgentLoop.
 * Verifies that the new_task tool integration works correctly end-to-end.
 */
class SessionHandoffLoopTest {

    private lateinit var project: Project
    private lateinit var contextManager: ContextManager

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        contextManager = ContextManager(maxInputTokens = 100_000)
    }

    // ---- Helpers ----

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

    private class SequenceBrain(private val responses: List<ApiResult<ChatCompletionResponse>>) : LlmBrain {
        override val modelId: String = "test-model"
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

    private fun newTaskTool(context: String = "Handoff context"): AgentTool = fakeTool(
        "new_task",
        ToolResult(
            content = context,
            summary = "Session handoff: context preserved (${context.length} chars)",
            tokenEstimate = context.length / 4,
            isSessionHandoff = true,
            handoffContext = context
        )
    )

    private fun buildLoop(
        brain: LlmBrain,
        tools: List<AgentTool>,
        maxIterations: Int = 200
    ): AgentLoop {
        val toolMap = tools.associateBy { it.name }
        val toolDefs = tools.map { it.toToolDefinition() }
        return AgentLoop(
            brain = brain,
            tools = toolMap,
            toolDefinitions = toolDefs,
            contextManager = contextManager,
            project = project,
            maxIterations = maxIterations
        )
    }

    // ---- Tests ----

    @Test
    fun `SessionHandoff returned when new_task tool sets isSessionHandoff=true`() = runTest {
        val handoffContext = "1. Current Work: implementing auth\n2. Pending: add refresh tokens"

        val brain = sequenceBrain(
            toolCallResponse("new_task" to """{"context":"$handoffContext"}""")
        )

        val tools = listOf(newTaskTool(handoffContext))
        val loop = buildLoop(brain, tools)
        val result = loop.run("Implement authentication")

        assertTrue(result is LoopResult.SessionHandoff, "Expected SessionHandoff but got $result")
        val handoff = result as LoopResult.SessionHandoff
        assertEquals(handoffContext, handoff.context)
        assertEquals(1, handoff.iterations)
    }

    @Test
    fun `SessionHandoff carries token counts`() = runTest {
        val brain = sequenceBrain(
            toolCallResponse("new_task" to """{"context":"test context"}""")
        )

        val tools = listOf(newTaskTool("test context"))
        val loop = buildLoop(brain, tools)
        val result = loop.run("Do work") as LoopResult.SessionHandoff

        // Token counts from usage info (100 prompt + 30 completion = 130 total)
        assertEquals(130, result.tokensUsed)
        assertEquals(100, result.inputTokens)
        assertEquals(30, result.outputTokens)
    }

    @Test
    fun `SessionHandoff after some tool calls preserves correct iteration count`() = runTest {
        val brain = sequenceBrain(
            toolCallResponse("read_file" to """{"path":"src/main.kt"}"""),
            toolCallResponse("edit_file" to """{"path":"src/main.kt","old_string":"old","new_string":"new","description":"fix"}"""),
            toolCallResponse("new_task" to """{"context":"handoff after 2 tools"}""")
        )

        val tools = listOf(
            fakeTool("read_file"),
            fakeTool("edit_file"),
            newTaskTool("handoff after 2 tools")
        )
        val loop = buildLoop(brain, tools)
        val result = loop.run("Fix the bug") as LoopResult.SessionHandoff

        assertEquals(3, result.iterations)
        assertEquals("handoff after 2 tools", result.context)
    }

    @Test
    fun `onToolCall callback fires for new_task tool`() = runTest {
        val brain = sequenceBrain(
            toolCallResponse("new_task" to """{"context":"callback test"}""")
        )

        val toolCallsReceived = mutableListOf<ToolCallProgress>()
        val tools = listOf(newTaskTool("callback test"))
        val toolMap = tools.associateBy { it.name }
        val toolDefs = tools.map { it.toToolDefinition() }

        val loop = AgentLoop(
            brain = brain,
            tools = toolMap,
            toolDefinitions = toolDefs,
            contextManager = contextManager,
            project = project,
            onToolCall = { toolCallsReceived.add(it) }
        )
        loop.run("Test")

        // Should have 2 callbacks: one start (empty result), one completion
        assertTrue(toolCallsReceived.size >= 2)
        val completionCallback = toolCallsReceived.last()
        assertEquals("new_task", completionCallback.toolName)
        assertFalse(completionCallback.isError)
    }
}
