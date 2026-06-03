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
import com.intellij.openapi.project.Project
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class AgentLoopMemoryApprovalTest {

    private val memoryDir = "/tmp/wf-approval-test/proj/agent/memory"

    @AfterEach
    fun stopModelPricingWatcher() { runCatching { ModelPricingRegistry.resetForTests() } }

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
                            ToolCall(id = "call_${System.nanoTime()}", type = "function",
                                function = FunctionCall(name = toolName, arguments = args))
                        )
                    ),
                    finishReason = "tool_calls"
                )
            ),
            usage = UsageInfo(promptTokens = 100, completionTokens = 10, totalTokens = 110)
        )

    private class SequenceBrain(private val responses: List<ApiResult<ChatCompletionResponse>>) : LlmBrain {
        override val modelId = "test-model"
        val callIndex = AtomicInteger(0)
        override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDefinition>?, maxTokens: Int?, toolChoice: JsonElement?) =
            throw UnsupportedOperationException()
        override suspend fun chatStream(messages: List<ChatMessage>, tools: List<ToolDefinition>?, maxTokens: Int?, onChunk: suspend (StreamChunk) -> Unit): ApiResult<ChatCompletionResponse> {
            val i = callIndex.getAndIncrement()
            return if (i >= responses.size) ApiResult.Error(ErrorType.SERVER_ERROR, "no more") else responses[i]
        }
        override fun estimateTokens(text: String) = text.length / 4
        override fun cancelActiveRequest() {}
    }

    private fun tool(toolName: String, completion: Boolean = false): AgentTool = object : AgentTool {
        override val name = toolName
        override val description = "test $toolName"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project): ToolResult =
            ToolResult(content = "ok", summary = "ok", tokenEstimate = 5, isCompletion = completion)
    }

    /** Records every approval-gate invocation. */
    private class GateRecorder {
        val calls = mutableListOf<Pair<String, Boolean>>()  // (toolName, allowSessionApproval)
        val gate: suspend (String, String, String, Boolean) -> ApprovalResult = { name, _, _, allowSession ->
            calls.add(name to allowSession)
            ApprovalResult.APPROVED
        }
    }

    private fun buildLoop(
        brain: LlmBrain,
        tools: List<AgentTool>,
        recorder: GateRecorder,
        sessionApprovalStore: SessionApprovalStore,
        autoApprove: Boolean,
    ): AgentLoop = AgentLoop(
        brain = brain,
        tools = tools.associateBy { it.name },
        toolDefinitions = tools.map { it.toToolDefinition() },
        contextManager = ContextManager(maxInputTokens = 100_000),
        project = mockk<Project>(relaxed = true),
        maxIterations = 10,
        approvalGate = recorder.gate,
        sessionApprovalStore = sessionApprovalStore,
        memoryDirPath = memoryDir,
        autoApproveMemoryOperations = autoApprove,
    )

    private fun editThenComplete(path: String) = SequenceBrain(listOf(
        ApiResult.Success(toolCallResponse("edit_file", """{"path":"$path","old_string":"a","new_string":"b"}""")),
        ApiResult.Success(toolCallResponse("attempt_completion", """{"kind":"done","result":"done"}"""))
    ))

    @Test
    fun `memory edit is gated even when edit_file is already approved for the session`() = runTest {
        val store = SessionApprovalStore().apply { approve("edit_file") }
        val recorder = GateRecorder()
        val loop = buildLoop(
            brain = editThenComplete("$memoryDir/user_prefs.md"),
            tools = listOf(tool("edit_file"), tool("attempt_completion", completion = true)),
            recorder = recorder,
            sessionApprovalStore = store,
            autoApprove = false,
        )
        loop.run("go")
        assertEquals(1, recorder.calls.size) { "Memory edit must hit the gate despite prior session approval; got ${recorder.calls}" }
        assertEquals("edit_file", recorder.calls[0].first)
        assertFalse(recorder.calls[0].second) { "Memory writes must be offered WITHOUT 'allow for session' (allowSessionApproval=false)" }
    }

    @Test
    fun `non-memory edit honors prior session approval and is NOT gated`() = runTest {
        val store = SessionApprovalStore().apply { approve("edit_file") }
        val recorder = GateRecorder()
        val loop = buildLoop(
            brain = editThenComplete("/tmp/wf-approval-test/proj/src/Main.kt"),
            tools = listOf(tool("edit_file"), tool("attempt_completion", completion = true)),
            recorder = recorder,
            sessionApprovalStore = store,
            autoApprove = false,
        )
        loop.run("go")
        assertEquals(0, recorder.calls.size) { "Non-memory edit with session approval must skip the gate; got ${recorder.calls}" }
    }

    @Test
    fun `autoApproveMemoryOperations bypasses the gate for memory writes`() = runTest {
        val recorder = GateRecorder()
        val loop = buildLoop(
            brain = editThenComplete("$memoryDir/user_prefs.md"),
            tools = listOf(tool("edit_file"), tool("attempt_completion", completion = true)),
            recorder = recorder,
            sessionApprovalStore = SessionApprovalStore(),
            autoApprove = true,
        )
        loop.run("go")
        assertEquals(0, recorder.calls.size) { "With auto-approve ON, memory writes must bypass the gate; got ${recorder.calls}" }
    }
}
