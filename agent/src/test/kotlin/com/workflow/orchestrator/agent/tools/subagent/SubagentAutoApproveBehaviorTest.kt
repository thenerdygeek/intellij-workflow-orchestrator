// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.tools.subagent

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.loop.SessionCommandAllowlist
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.builtin.AttemptCompletionTool
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
import io.mockk.every
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

/**
 * BEHAVIORAL coverage for the run_command auto-approval feature on the SUB-AGENT path.
 *
 * Closes the gap left by source-text tests: those only assert that
 * [SubagentRunner] forwards `autoApproveSafeCommands` / `sessionCommandAllowlist`
 * as strings — they do NOT prove a sub-agent's `run_command` is actually
 * auto-approved (or correctly prompted) at runtime.
 *
 * Here we drive a REAL [SubagentRunner], which builds its own [com.workflow.orchestrator.agent.loop.AgentLoop],
 * and observe whether the sub-agent's `approvalGate` is invoked when the LLM emits
 * a `run_command` tool call. The recorder gate makes the auto-approve decision
 * directly observable:
 *   - gate NOT invoked  ⇒ the sub-agent loop auto-approved (Part A toggle or Part B allowlist)
 *   - gate IS invoked   ⇒ the sub-agent loop prompted (conservative default / structural guard)
 *
 * Harness mirrors [SubagentRunnerTest] (`ApprovalGateTests` nested class): a stubbed
 * `run_command` tool that returns a simple [ToolResult] WITHOUT calling
 * [AgentTool.requestApproval] (so the ONLY possible gate invocation is the loop-level
 * approval block), a [SequenceBrain] that emits one `run_command` call then completes,
 * and a recorder that captures every gate call.
 *
 * Note: `run_command` does not call `requestApproval` internally, so the
 * [com.workflow.orchestrator.agent.loop.AgentLoop] ApprovalGatedTool wrapper is never
 * exercised by these cases — the auto-approve decision lives entirely in AgentLoop's
 * `toolName == "run_command"` block. That is exactly the seam under test.
 */
class SubagentAutoApproveBehaviorTest {

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        every { project.name } returns "TestProject"
        every { project.basePath } returns "/tmp/test-project"
    }

    /** Mirrors SubagentRunnerTest: stop the lazily-started ModelPricingRegistry watcher. */
    @AfterEach
    fun stopModelPricingWatcher() {
        runCatching { ModelPricingRegistry.resetForTests() }
    }

    // ---- Helpers (mirrored from SubagentRunnerTest) ----

    /**
     * A stub `run_command` tool. Returns a trivial [ToolResult] and — critically —
     * does NOT call [AgentTool.requestApproval], so the only place the approval gate
     * can fire is AgentLoop's loop-level run_command approval block.
     */
    private fun runCommandTool(): AgentTool = object : AgentTool {
        override val name = "run_command"
        override val description = "Stub run_command tool"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        // Mirror real run_command: per-invocation approval (post-0b-3 the gate reads
        // requiresApproval, not the deleted name sets).
        override val requiresApproval = true
        override val allowSessionApproval = false
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "ran", summary = "ran", tokenEstimate = 5)
    }

    /** Records every approval-gate invocation; always APPROVES so the loop proceeds. */
    private class GateRecorder {
        val calls = mutableListOf<Triple<String, String, String>>() // (toolName, args, riskLevel)
        val gate: suspend (String, String, String, Boolean) -> ApprovalResult =
            { toolName, args, risk, _ ->
                calls += Triple(toolName, args, risk)
                ApprovalResult.APPROVED
            }
    }

    /**
     * A fake LlmBrain that returns pre-configured responses in sequence.
     * Same pattern as SubagentRunnerTest.SequenceBrain.
     */
    private class SequenceBrain(
        private val responses: List<ApiResult<ChatCompletionResponse>>,
    ) : LlmBrain {
        override val modelId: String = "test-subagent-brain"
        override var toolNameSet: Set<String> = emptySet()
        override var paramNameSet: Set<String> = emptySet()
        private var callIndex = 0

        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            toolChoice: JsonElement?
        ): ApiResult<ChatCompletionResponse> {
            throw UnsupportedOperationException("AgentLoop uses chatStream")
        }

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
        override fun cancelActiveRequest() { /* no-op */ }
    }

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

    /**
     * Drive a sub-agent that emits ONE run_command call then completes, with the given
     * auto-approve toggle + session prefix allowlist. Returns the recorder so the caller
     * can assert on whether/how the gate fired.
     */
    private suspend fun driveRunCommandSubagent(
        command: String,
        autoApproveSafeCommands: Boolean,
        allowlist: SessionCommandAllowlist = SessionCommandAllowlist(),
    ): GateRecorder {
        val tools = mapOf(
            "run_command" to runCommandTool(),
            "attempt_completion" to AttemptCompletionTool(),
        )
        val brain = SequenceBrain(
            listOf(
                ApiResult.Success(toolCallResponse("run_command" to """{"command":"$command"}""")),
                ApiResult.Success(toolCallResponse("attempt_completion" to """{"kind":"done","result":"done"}""")),
            )
        )
        val recorder = GateRecorder()

        val runner = SubagentRunner(
            brain = brain,
            coreTools = tools,
            systemPrompt = "test sub-agent",
            project = project,
            maxIterations = 10,
            planMode = false,
            contextBudget = 50_000,
            approvalGate = recorder.gate,
            sessionCommandAllowlist = allowlist,
            autoApproveSafeCommands = autoApproveSafeCommands,
        )

        val result = runner.run("run a command", "test-agent", "test (unit-test)") {}

        // Every case must reach completion; the run_command tool was dispatched in all of them.
        assertEquals(
            SubagentRunStatus.COMPLETED,
            result.status,
            "Sub-agent should complete (run_command + attempt_completion). error=${result.error}",
        )
        return recorder
    }

    // ---- Cases ----

    /**
     * Case 1 — toggle ON + a SAFE command. The sub-agent loop must AUTO-APPROVE
     * (Part A), so the approval gate is never invoked.
     */
    @Test
    fun `subagent auto-approves a safe command when autoApproveSafeCommands is ON`() = runTest {
        val recorder = driveRunCommandSubagent(
            command = "ls -la",
            autoApproveSafeCommands = true,
        )
        assertTrue(
            recorder.calls.isEmpty(),
            "Sub-agent must auto-approve a SAFE run_command with the toggle ON — " +
                "gate should NOT fire, but it was called with: ${recorder.calls}",
        )
    }

    /**
     * Case 2 — toggle OFF but the session prefix allowlist pre-approves "git pull".
     * The sub-agent loop must honor Part B and auto-approve `git pull origin`.
     */
    @Test
    fun `subagent honors session command allowlist prefix when toggle is OFF`() = runTest {
        val allowlist = SessionCommandAllowlist().apply { approve("git pull") }
        val recorder = driveRunCommandSubagent(
            command = "git pull origin",
            autoApproveSafeCommands = false,
            allowlist = allowlist,
        )
        assertTrue(
            recorder.calls.isEmpty(),
            "Sub-agent must auto-approve a run_command covered by a session-allowed prefix " +
                "(Part B) even with the toggle OFF — gate should NOT fire, but was called with: ${recorder.calls}",
        )
    }

    /**
     * Case 3 — toggle OFF, empty allowlist, a RISKY command. The conservative default
     * must still PROMPT inside the sub-agent loop, i.e. the gate IS invoked for run_command.
     */
    @Test
    fun `subagent still prompts for a risky command with toggle OFF and empty allowlist`() = runTest {
        val recorder = driveRunCommandSubagent(
            command = "git push",
            autoApproveSafeCommands = false,
        )
        assertEquals(
            1,
            recorder.calls.size,
            "Sub-agent must PROMPT (invoke the gate exactly once) for a risky run_command " +
                "when neither the toggle nor the allowlist auto-approves it. calls=${recorder.calls}",
        )
        assertEquals(
            "run_command",
            recorder.calls.single().first,
            "The gated tool must be run_command.",
        )
    }

    /**
     * Case 4 — toggle ON but the command is SAFE-by-name yet REDIRECTED (`>`),
     * which trips CommandShape's structural guard (`isAutoApprovable == false`).
     * The sub-agent loop must still PROMPT despite the toggle being ON.
     */
    @Test
    fun `subagent prompts for a safe-but-redirected command even with toggle ON`() = runTest {
        val recorder = driveRunCommandSubagent(
            command = "git status > out",
            autoApproveSafeCommands = true,
        )
        assertEquals(
            1,
            recorder.calls.size,
            "A redirected command is structurally NOT auto-approvable; the sub-agent must prompt " +
                "even with the SAFE toggle ON. calls=${recorder.calls}",
        )
        assertNotNull(recorder.calls.single())
        assertEquals("run_command", recorder.calls.single().first)
    }
}
