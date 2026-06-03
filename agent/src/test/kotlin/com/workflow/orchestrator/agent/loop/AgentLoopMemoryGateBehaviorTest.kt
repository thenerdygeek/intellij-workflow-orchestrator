// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.Choice
import com.workflow.orchestrator.core.ai.dto.FunctionCall
import com.workflow.orchestrator.core.ai.dto.StreamChunk
import com.workflow.orchestrator.core.ai.dto.ToolCall
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.ai.dto.UsageInfo
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.ModelPricingRegistry
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicInteger

/**
 * Behavioral tests that drive a REAL [AgentLoop] with the completion-gate chain
 * actually enabled — the gap that [AgentLoopCompletionGateTest]'s source-text pins
 * and [com.workflow.orchestrator.agent.loop.completion.CompletionGateChainTest]'s pure
 * runner tests could not cover together. Mirrors the harness in [AgentLoopExitDrainTest]
 * (scripted [SequenceBrain] + fake completion tool), which is the sanctioned way to
 * exercise the loop without standing up the full IDE stack.
 *
 * Distinctive memory-dir path so the memory nudge is unambiguously identifiable in the
 * conversation history.
 */
class AgentLoopMemoryGateBehaviorTest {

    private val memoryDir = "/tmp/wf-test-proj/agent/memory"

    @AfterEach
    fun stopModelPricingWatcher() {
        runCatching { ModelPricingRegistry.resetForTests() }
    }

    private fun toolCallResponse(toolName: String, args: String): ChatCompletionResponse =
        ChatCompletionResponse(
            id = "resp-${System.nanoTime()}",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(
                        role = "assistant",
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "call_${System.nanoTime()}",
                                type = "function",
                                function = FunctionCall(name = toolName, arguments = args)
                            )
                        )
                    ),
                    finishReason = "tool_calls"
                )
            ),
            usage = UsageInfo(promptTokens = 100, completionTokens = 10, totalTokens = 110)
        )

    private class SequenceBrain(private val responses: List<ApiResult<ChatCompletionResponse>>) : LlmBrain {
        override val modelId: String = "test-model"
        val callIndex = AtomicInteger(0)

        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            toolChoice: JsonElement?
        ): ApiResult<ChatCompletionResponse> =
            throw UnsupportedOperationException("AgentLoop uses chatStream, not chat")

        override suspend fun chatStream(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            onChunk: suspend (StreamChunk) -> Unit
        ): ApiResult<ChatCompletionResponse> {
            val i = callIndex.getAndIncrement()
            return if (i >= responses.size) {
                ApiResult.Error(ErrorType.SERVER_ERROR, "No more scripted responses (call #$i)")
            } else {
                responses[i]
            }
        }

        override fun estimateTokens(text: String): Int = text.toByteArray().size / 4
        override fun cancelActiveRequest() {}
    }

    /** Fake `attempt_completion`: each call returns a completion result with distinct content. */
    private fun completionTool(contents: List<String>): AgentTool = object : AgentTool {
        private val n = AtomicInteger(0)
        override val name = "attempt_completion"
        override val description = "Test completion"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project): ToolResult {
            val idx = n.getAndIncrement().coerceAtMost(contents.lastIndex)
            return ToolResult(content = contents[idx], summary = contents[idx], tokenEstimate = 5, isCompletion = true)
        }
    }

    /** Fake `feedback`: a normal (non-completion) tool result. */
    private fun feedbackTool(): AgentTool = object : AgentTool {
        override val name = "feedback"
        override val description = "Test feedback"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project): ToolResult =
            ToolResult(content = "noted", summary = "noted", tokenEstimate = 5)
    }

    private fun buildLoop(
        brain: LlmBrain,
        tools: List<AgentTool>,
        contextManager: ContextManager,
        proactiveMemoryUpdatesEnabled: Boolean,
        feedbackEnabled: Boolean,
    ): AgentLoop = AgentLoop(
        brain = brain,
        tools = tools.associateBy { it.name },
        toolDefinitions = tools.map { it.toToolDefinition() },
        contextManager = contextManager,
        project = mockk<Project>(relaxed = true),
        maxIterations = 10,
        feedbackEnabled = feedbackEnabled,
        proactiveMemoryUpdatesEnabled = proactiveMemoryUpdatesEnabled,
        memoryDirPath = memoryDir,
    )

    @Test
    fun `memory gate defers exit on first attempt_completion then finishes on re-completion`() = runTest {
        // proactive ON, feedback OFF. Iter 1 attempt_completion → memory nudge, continue.
        // Iter 2 attempt_completion → memory gate cleared by re-completion → exit.
        val contextManager = ContextManager(maxInputTokens = 100_000)
        val brain = SequenceBrain(listOf(
            ApiResult.Success(toolCallResponse("attempt_completion", """{"kind":"done","result":"first"}""")),
            ApiResult.Success(toolCallResponse("attempt_completion", """{"kind":"done","result":"second"}"""))
        ))
        val loop = buildLoop(
            brain = brain,
            tools = listOf(completionTool(listOf("first done", "second done"))),
            contextManager = contextManager,
            proactiveMemoryUpdatesEnabled = true,
            feedbackEnabled = false,
        )

        val result = loop.run("Do the thing")

        assertEquals(2, brain.callIndex.get()) {
            "Brain must be called twice: iter 1 (memory gate armed → continue), iter 2 (re-completion → exit). " +
                "If the gate were absent, the loop would exit on iter 1 with a single brain call."
        }
        assertTrue(result is LoopResult.Completed) { "Loop must complete once the memory gate clears; got $result" }
        assertEquals("first done", (result as LoopResult.Completed).summary) {
            "Returned completion must be the FIRST attempt_completion (pending captured once), not the re-completion."
        }
        val memoryNudged = contextManager.getMessages().any {
            it.role == "user" && it.content?.contains("file-based memory at $memoryDir") == true
        }
        assertTrue(memoryNudged) { "The memory-review nudge (naming the memory dir) must be injected into the conversation." }
    }

    @Test
    fun `with both gates on the memory nudge precedes the feedback nudge`() = runTest {
        // proactive ON + feedback ON. Iter 1 attempt_completion → memory nudge.
        // Iter 2 attempt_completion → memory cleared, feedback nudge. Iter 3 feedback → exit.
        val contextManager = ContextManager(maxInputTokens = 100_000)
        val brain = SequenceBrain(listOf(
            ApiResult.Success(toolCallResponse("attempt_completion", """{"kind":"done","result":"a"}""")),
            ApiResult.Success(toolCallResponse("attempt_completion", """{"kind":"done","result":"b"}""")),
            ApiResult.Success(toolCallResponse("feedback", """{"content":"no issues"}"""))
        ))
        val loop = buildLoop(
            brain = brain,
            tools = listOf(completionTool(listOf("all done")), feedbackTool()),
            contextManager = contextManager,
            proactiveMemoryUpdatesEnabled = true,
            feedbackEnabled = true,
        )

        val result = loop.run("Do the thing")

        assertEquals(3, brain.callIndex.get()) {
            "Brain must be called three times: memory nudge, feedback nudge, then the feedback tool clears the chain."
        }
        assertTrue(result is LoopResult.Completed) { "Loop must complete after both gates clear; got $result" }

        val messages = contextManager.getMessages()
        val memoryIdx = messages.indexOfFirst {
            it.role == "user" && it.content?.contains("file-based memory at $memoryDir") == true
        }
        val feedbackIdx = messages.indexOfFirst {
            it.role == "user" && it.content?.contains("Use the `feedback` tool to share any feedback") == true
        }
        assertTrue(memoryIdx >= 0) { "Memory nudge must be present." }
        assertTrue(feedbackIdx >= 0) { "Feedback nudge must be present." }
        assertTrue(memoryIdx < feedbackIdx) {
            "Memory nudge (index $memoryIdx) must precede the feedback nudge (index $feedbackIdx)."
        }
    }
}
