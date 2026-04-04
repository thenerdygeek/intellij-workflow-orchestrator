package com.workflow.orchestrator.agent.hooks

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionCall
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ToolCall
import com.workflow.orchestrator.agent.loop.AgentLoop
import com.workflow.orchestrator.agent.loop.ContextManager
import com.workflow.orchestrator.agent.loop.LoopResult
import com.workflow.orchestrator.agent.loop.ToolCallProgress
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
 * Integration tests for hook dispatch within AgentLoop.
 *
 * Verifies that PRE_TOOL_USE and POST_TOOL_USE hooks are correctly
 * dispatched during tool execution in the agent loop.
 */
class AgentLoopHookIntegrationTest {

    private lateinit var project: Project
    private lateinit var contextManager: ContextManager

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        contextManager = ContextManager(maxInputTokens = 100_000)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun stubTool(name: String, result: ToolResult = ToolResult("ok", "ok", 1)) =
        object : AgentTool {
            override val name = name
            override val description = "Test tool $name"
            override val parameters = FunctionParameters(properties = emptyMap())
            override val allowedWorkers = setOf(WorkerType.CODER)
            override suspend fun execute(params: JsonObject, project: Project) = result
        }

    private fun completionTool() = stubTool(
        "attempt_completion",
        ToolResult("Done", "Done", 1, isCompletion = true)
    )

    private fun toolCallResponse(toolName: String, args: String = "{}"): ChatCompletionResponse =
        ChatCompletionResponse(
            id = "resp-${System.nanoTime()}",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(
                        role = "assistant",
                        content = "Calling $toolName",
                        toolCalls = listOf(
                            ToolCall(
                                id = "call-${System.nanoTime()}",
                                function = FunctionCall(
                                    name = toolName,
                                    arguments = args
                                )
                            )
                        )
                    ),
                    finishReason = "tool_calls"
                )
            ),
            usage = UsageInfo(promptTokens = 100, completionTokens = 50, totalTokens = 150)
        )

    /** LlmBrain that returns a sequence of scripted responses. */
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

    // ── PRE_TOOL_USE hook blocks tool execution ─────────────────────────

    @Test
    fun `PRE_TOOL_USE hook can block tool execution`() = runTest {
        val fakeRunner = FakeHookRunner()
        // First call: cancel (block edit_file), second call: proceed (allow attempt_completion)
        fakeRunner.resultSequence = mutableListOf(
            HookResult.Cancel(reason = "blocked by policy"),
            HookResult.Proceed()
        )

        val hookManager = HookManager(fakeRunner)
        hookManager.register(HookConfig(type = HookType.PRE_TOOL_USE, command = "check"))

        // Brain returns: call edit_file, then attempt_completion
        val brain = SequenceBrain(listOf(
            ApiResult.Success(toolCallResponse("edit_file")),
            ApiResult.Success(toolCallResponse("attempt_completion"))
        ))

        val toolCallResults = mutableListOf<ToolCallProgress>()

        val loop = AgentLoop(
            brain = brain,
            tools = mapOf(
                "edit_file" to stubTool("edit_file"),
                "attempt_completion" to completionTool()
            ),
            toolDefinitions = emptyList(),
            contextManager = contextManager,
            project = project,
            onToolCall = { toolCallResults.add(it) },
            hookManager = hookManager,
            sessionId = "test-session"
        )

        val result = loop.run("Edit a file")

        // First tool (edit_file) should be blocked by hook
        val editResults = toolCallResults.filter { it.toolName == "edit_file" && it.result.isNotEmpty() }
        assertTrue(editResults.any { it.isError && it.result.contains("blocked by") })

        // Loop should still complete (attempt_completion runs on second iteration)
        assertTrue(result is LoopResult.Completed)
    }

    // ── POST_TOOL_USE hook fires after execution ────────────────────────

    @Test
    fun `POST_TOOL_USE hook receives correct event data`() = runTest {
        val fakeRunner = FakeHookRunner()
        fakeRunner.nextResult = HookResult.Proceed()

        val hookManager = HookManager(fakeRunner)
        hookManager.register(HookConfig(type = HookType.POST_TOOL_USE, command = "audit"))

        val brain = SequenceBrain(listOf(
            ApiResult.Success(toolCallResponse("attempt_completion"))
        ))

        val loop = AgentLoop(
            brain = brain,
            tools = mapOf("attempt_completion" to completionTool()),
            toolDefinitions = emptyList(),
            contextManager = contextManager,
            project = project,
            hookManager = hookManager,
            sessionId = "test-session"
        )

        loop.run("Complete the task")

        // POST_TOOL_USE hook should have fired
        assertEquals(1, fakeRunner.executionCount)
        val event = fakeRunner.lastEvent!!
        assertEquals(HookType.POST_TOOL_USE, event.type)
        assertEquals("attempt_completion", event.data["toolName"])
        assertFalse(event.cancellable) // POST_TOOL_USE is observation-only
        assertEquals("test-session", event.data["sessionId"])
    }

    // ── No hooks = zero overhead ────────────────────────────────────────

    @Test
    fun `null hookManager means no hook overhead`() = runTest {
        val brain = SequenceBrain(listOf(
            ApiResult.Success(toolCallResponse("attempt_completion"))
        ))

        val loop = AgentLoop(
            brain = brain,
            tools = mapOf("attempt_completion" to completionTool()),
            toolDefinitions = emptyList(),
            contextManager = contextManager,
            project = project,
            hookManager = null // No hooks
        )

        val result = loop.run("Complete")
        assertTrue(result is LoopResult.Completed)
    }

    // ── PRE_TOOL_USE Proceed allows normal execution ────────────────────

    @Test
    fun `PRE_TOOL_USE Proceed allows tool execution`() = runTest {
        val fakeRunner = FakeHookRunner()
        fakeRunner.nextResult = HookResult.Proceed()

        val hookManager = HookManager(fakeRunner)
        hookManager.register(HookConfig(type = HookType.PRE_TOOL_USE, command = "allow"))

        val brain = SequenceBrain(listOf(
            ApiResult.Success(toolCallResponse("attempt_completion"))
        ))

        val loop = AgentLoop(
            brain = brain,
            tools = mapOf("attempt_completion" to completionTool()),
            toolDefinitions = emptyList(),
            contextManager = contextManager,
            project = project,
            hookManager = hookManager,
            sessionId = "test"
        )

        val result = loop.run("Complete task")
        assertTrue(result is LoopResult.Completed)
    }
}
