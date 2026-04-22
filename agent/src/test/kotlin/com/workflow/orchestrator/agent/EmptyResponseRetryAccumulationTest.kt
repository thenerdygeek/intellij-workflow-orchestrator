package com.workflow.orchestrator.agent

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.loop.AgentLoop
import com.workflow.orchestrator.agent.loop.ContextManager
import com.workflow.orchestrator.agent.loop.FailureReason
import com.workflow.orchestrator.agent.loop.LoopResult
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
 * End-to-end canary: the specific bug path the user reported.
 *
 * Before the fix: each retry appended its 3 empty-assistant turns to
 * api_conversation_history.json, and the LLM started mimicking the pattern.
 *
 * After the fix: no empty-assistant survives across retry cycles.
 */
class EmptyResponseRetryAccumulationTest {

    @TempDir
    lateinit var tempDir: File

    @AfterEach
    fun stopModelPricingWatcher() {
        runCatching { ModelPricingRegistry.resetForTests() }
    }

    // ---- Local helpers (inlined from AgentLoopTest / AgentLoopEmptyResponsePersistenceTest) ----

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

    // ---- End-to-end scenario test ----

    @Test
    fun `retry after three empties does not accumulate empty assistant turns on disk`() = runTest {
        val handler = newMessageStateHandler()

        // First loop run: 3 empties.
        val firstBrain = sequenceBrain(emptyResponse(), emptyResponse(), emptyResponse())
        val firstLoop = buildLoopWithHandler(brain = firstBrain, tools = listOf(completionTool()), handler = handler)
        val first = firstLoop.run("Do the thing")
        assertTrue(first is LoopResult.Failed) { "Expected Failed, got $first" }
        assertEquals(FailureReason.EMPTY_RESPONSES, (first as LoopResult.Failed).reason)

        // Simulate the retry cleanup path.
        val preCleanupEmpties = handler.getApiConversationHistory()
            .count { it.role == ApiRole.ASSISTANT && it.content.isEmpty() }
        assertEquals(0, preCleanupEmpties) { "Task 4 guard should have prevented any empty writes" }
        handler.pruneTrailingEmptyAssistants()  // no-op but exercises the code path

        // Second loop run: 3 more empties, same handler (shared session).
        val secondBrain = sequenceBrain(emptyResponse(), emptyResponse(), emptyResponse())
        val secondLoop = buildLoopWithHandler(brain = secondBrain, tools = listOf(completionTool()), handler = handler)
        val second = secondLoop.run("continue")
        assertTrue(second is LoopResult.Failed) { "Expected Failed on second run, got $second" }

        // Canary: even after two full retry cycles, zero empty assistants on disk.
        val persisted = handler.getApiConversationHistory()
        val finalEmpties = persisted.count { it.role == ApiRole.ASSISTANT && it.content.isEmpty() }
        assertEquals(0, finalEmpties) {
            "No empty-assistant turns should ever reach disk. History size: ${persisted.size}"
        }
    }
}
