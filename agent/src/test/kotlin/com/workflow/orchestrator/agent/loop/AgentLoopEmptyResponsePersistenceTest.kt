package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.session.ApiRole
import com.workflow.orchestrator.agent.session.MessageStateHandler
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.*
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ModelPricingRegistry
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Scenario test: verifies that the Task-4 guard in MessageStateHandler.addToApiConversationHistory
 * prevents empty-assistant turns from ever reaching api_conversation_history.json.
 *
 * No production code change is needed — this test locks in the behavior at the loop level
 * so a future refactor cannot regress the guard.
 */
class AgentLoopEmptyResponsePersistenceTest {

    @TempDir
    lateinit var tempDir: File

    @AfterEach
    fun stopModelPricingWatcher() {
        runCatching { ModelPricingRegistry.resetForTests() }
    }

    // ---- Local helpers (inlined from AgentLoopTest private helpers) ----

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
                return ApiResult.Error(com.workflow.orchestrator.core.model.ErrorType.SERVER_ERROR, "No more scripted responses (call #$callIndex)")
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

    private fun completionTool(summary: String = "Done.") = fakeTool(
        "attempt_completion",
        ToolResult(
            content = summary,
            summary = summary,
            tokenEstimate = 5,
            isCompletion = true
        )
    )

    private fun newMessageStateHandler(sessionId: String = "test-session"): MessageStateHandler =
        MessageStateHandler(baseDir = tempDir, sessionId = sessionId, taskText = "test task")

    /**
     * Builds an AgentLoop wired to a real MessageStateHandler so persistence calls
     * go through the guard introduced in Task 4.
     */
    private fun buildLoopWithHandler(
        brain: LlmBrain,
        tools: List<AgentTool>,
        handler: MessageStateHandler,
        maxIterations: Int = 200,
    ): AgentLoop {
        val project = mockk<Project>(relaxed = true)
        val contextManager = ContextManager(maxInputTokens = 100_000)
        val toolMap = tools.associateBy { it.name }
        val toolDefs = tools.map { it.toToolDefinition() }
        return AgentLoop(
            brain = brain,
            tools = toolMap,
            toolDefinitions = toolDefs,
            contextManager = contextManager,
            project = project,
            maxIterations = maxIterations,
            messageStateHandler = handler,
        )
    }

    // ---- Scenario test ----

    @Test
    fun `three consecutive empty responses do not persist any empty assistant turns`() = runTest {
        val brain = sequenceBrain(
            emptyResponse(),
            emptyResponse(),
            emptyResponse(),
        )
        val handler = newMessageStateHandler()
        val loop = buildLoopWithHandler(
            brain = brain,
            tools = listOf(completionTool()),
            handler = handler,
        )

        val result = loop.run("Do something")

        assertTrue(result is LoopResult.Failed) { "Expected Failed, got $result" }
        assertEquals(FailureReason.EMPTY_RESPONSES, (result as LoopResult.Failed).reason)

        val persisted = handler.getApiConversationHistory()
        val emptyAssistantCount = persisted.count { msg ->
            msg.role == ApiRole.ASSISTANT && msg.content.isEmpty()
        }
        assertEquals(0, emptyAssistantCount) {
            "No empty-assistant turns should be persisted. History: $persisted"
        }
    }
}
