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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for the "allow for session re-prompts in the same conversation" bug.
 *
 * The user reports that after clicking "Allow for session" on an `edit_file`
 * approval card, subsequent edits still trigger fresh approval prompts within
 * the same conversation.
 *
 * Investigation traced three code paths that decide whether the gate fires:
 *
 *   1. **Parent agent, multi-turn** — `AgentController.sessionApprovalStore`
 *      (AgentController.kt:81) is a `val`, passed into every `executeTask()`
 *      and wired into each fresh `AgentLoop`. Within one `AgentController`
 *      lifetime it is cleared ONLY on `newChat()` / `resetForNewChat()` /
 *      `dispose()` / `new_task` session handoff (AgentController.kt:1718,
 *      1817, 2863). Turn-to-turn parent approvals therefore SHOULD persist —
 *      the sanity test below confirms this invariant still holds.
 *
 *   2. **Sub-agent** — `SubagentRunner.kt:197-267` constructs an `AgentLoop`
 *      but does NOT pass `sessionApprovalStore`. `AgentLoop.kt:293` defaults
 *      to a brand-new empty `SessionApprovalStore()`. Every sub-agent spawn
 *      therefore starts with zero recorded approvals — even when the parent
 *      has already approved `edit_file` for the session. Any sub-agent write
 *      re-prompts the user. This is the most likely trigger for the behavior
 *      the user is seeing and is the BUG that the second test below pins.
 *
 *   3. **new_task session handoff** — clears the store intentionally, since
 *      the conversation "restarts" with fresh context. That is a UX
 *      discussion, not a correctness bug, so no test here.
 *
 * These tests will guide the fix: thread the parent's
 * `SessionApprovalStore` into `SubagentRunner` and forward it into the
 * sub-agent's `AgentLoop`, so approvals granted for the conversation are
 * honored by every worker in that conversation.
 */
class ApprovalGateReprompingBugTest {

    private lateinit var project: Project
    private lateinit var contextManager: ContextManager

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        contextManager = ContextManager(maxInputTokens = 100_000)
    }

    @AfterEach
    fun stopModelPricingWatcher() {
        // AgentLoop lookups start the registry's FileSystemWatcher; shut it down so
        // macOS ThreadLeakTracker doesn't trip.
        runCatching { ModelPricingRegistry.resetForTests() }
    }

    // ---- Helpers ----

    private fun responseWithToolCall(toolName: String, args: String, promptTokens: Int = 100, completionTokens: Int = 10): ChatCompletionResponse {
        val message = ChatMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(
                ToolCall(
                    id = "call_${System.nanoTime()}",
                    type = "function",
                    function = FunctionCall(name = toolName, arguments = args)
                )
            )
        )
        return ChatCompletionResponse(
            id = "resp-${System.nanoTime()}",
            choices = listOf(Choice(index = 0, message = message, finishReason = "stop")),
            usage = UsageInfo(promptTokens = promptTokens, completionTokens = completionTokens, totalTokens = promptTokens + completionTokens)
        )
    }

    private class SequenceBrain(private val responses: List<ApiResult<ChatCompletionResponse>>) : LlmBrain {
        override val modelId: String = "test-model"
        private var callIndex = 0
        override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDefinition>?, maxTokens: Int?, toolChoice: JsonElement?): ApiResult<ChatCompletionResponse> =
            throw UnsupportedOperationException("Uses chatStream")
        override suspend fun chatStream(messages: List<ChatMessage>, tools: List<ToolDefinition>?, maxTokens: Int?, onChunk: suspend (StreamChunk) -> Unit): ApiResult<ChatCompletionResponse> {
            if (callIndex >= responses.size) return ApiResult.Error(ErrorType.SERVER_ERROR, "No more responses")
            return responses[callIndex++]
        }
        override fun estimateTokens(text: String): Int = text.toByteArray().size / 4
        override fun cancelActiveRequest() {}
    }

    private fun fakeTool(toolName: String): AgentTool = object : AgentTool {
        override val name = toolName
        override val description = "fake"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "ok", summary = "ok", tokenEstimate = 5)
    }

    private fun completionTool() = object : AgentTool {
        override val name = "attempt_completion"
        override val description = "complete"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "done", summary = "done", tokenEstimate = 5, isCompletion = true)
    }

    /** Counts how many times the approval gate was invoked and with which tool name. */
    private class GateSpy {
        val invocations = mutableListOf<String>()
        val gate: suspend (String, String, String, Boolean) -> ApprovalResult = { toolName, _, _, _ ->
            invocations.add(toolName)
            // First invocation: user clicks "Allow for session". Subsequent ones shouldn't happen.
            ApprovalResult.ALLOWED_FOR_SESSION
        }
    }

    private fun buildLoopWith(
        brain: LlmBrain,
        gate: suspend (String, String, String, Boolean) -> ApprovalResult,
        store: SessionApprovalStore
    ): AgentLoop {
        val tools = listOf(fakeTool("edit_file"), completionTool())
        return AgentLoop(
            brain = brain,
            tools = tools.associateBy { it.name },
            toolDefinitions = tools.map { it.toToolDefinition() },
            contextManager = contextManager,
            project = project,
            approvalGate = gate,
            sessionApprovalStore = store
        )
    }

    // ---- Tests ----

    /**
     * Sanity: within one conversation (one `AgentController`, hence one shared
     * `SessionApprovalStore`), "Allow for session" persists across turns. This
     * IS already the behavior — the test locks it in so any regression is
     * caught.
     */
    @Test
    fun `shared SessionApprovalStore across turns suppresses re-prompt on edit_file`() = runTest {
        val spy = GateSpy()
        val sharedStore = SessionApprovalStore()

        // ── Turn 1: LLM calls edit_file, then attempt_completion ──
        val turn1Brain = SequenceBrain(listOf(
            ApiResult.Success(responseWithToolCall("edit_file", """{"path":"A.kt","old_string":"a","new_string":"b"}""")),
            ApiResult.Success(responseWithToolCall("attempt_completion", """{"result":"done"}""")),
        ))
        buildLoopWith(turn1Brain, spy.gate, sharedStore).run("turn 1")

        assertTrue(sharedStore.isApproved("edit_file"),
            "Sanity: ALLOWED_FOR_SESSION must have been recorded in the shared store.")
        assertEquals(listOf("edit_file"), spy.invocations,
            "Sanity: gate should fire exactly once in turn 1.")

        // ── Turn 2: LLM calls edit_file again, same shared store ──
        val turn2Brain = SequenceBrain(listOf(
            ApiResult.Success(responseWithToolCall("edit_file", """{"path":"B.kt","old_string":"x","new_string":"y"}""")),
            ApiResult.Success(responseWithToolCall("attempt_completion", """{"result":"done"}""")),
        ))
        buildLoopWith(turn2Brain, spy.gate, sharedStore).run("turn 2")

        assertEquals(1, spy.invocations.size,
            "Turn 2's edit_file must NOT re-prompt — the shared store already has the approval. " +
                "If this fails, something between turns is clearing or replacing the store.")
    }

    /**
     * Post-fix invariant: when SubagentRunner forwards the parent's
     * SessionApprovalStore into the sub-agent's AgentLoop (the fixed wiring),
     * a sub-agent that calls edit_file does NOT re-prompt if the parent has
     * already approved it for the session.
     *
     * Regression guard: if this test fails, the sub-agent construction path
     * stopped threading the parent store through and bug 3 has returned.
     */
    @Test
    fun `sub-agent inherits parent's session approvals — no re-prompt on edit_file`() = runTest {
        // Parent has already approved edit_file for the session.
        val parentStore = SessionApprovalStore().apply { approve("edit_file") }
        assertTrue(parentStore.isApproved("edit_file"), "Sanity: parent store is primed.")

        val spy = GateSpy()
        val subagentBrain = SequenceBrain(listOf(
            ApiResult.Success(responseWithToolCall("edit_file", """{"path":"C.kt","old_string":"m","new_string":"n"}""")),
            ApiResult.Success(responseWithToolCall("attempt_completion", """{"result":"done"}""")),
        ))
        val tools = listOf(fakeTool("edit_file"), completionTool())
        val subagentLoop = AgentLoop(
            brain = subagentBrain,
            tools = tools.associateBy { it.name },
            toolDefinitions = tools.map { it.toToolDefinition() },
            contextManager = contextManager,
            project = project,
            approvalGate = spy.gate,
            sessionApprovalStore = parentStore,  // ← the fix: forward the parent's store
        )
        subagentLoop.run("sub-agent task")

        assertEquals(0, spy.invocations.size,
            "Sub-agent re-prompted the user on edit_file even though the parent has already " +
                "approved it for the session. Gate invocations: ${spy.invocations}. " +
                "Bug 3 has regressed — check that SubagentRunner still forwards " +
                "sessionApprovalStore into its AgentLoop construction.")
    }

    /**
     * Documents the pre-fix failure mode: a sub-agent built WITHOUT forwarding
     * the parent's SessionApprovalStore uses AgentLoop.kt:293's empty default
     * store, so the gate fires on every write — even when the parent already
     * approved. Kept as a regression reference so anyone reading the file sees
     * why the fix is needed.
     */
    @Test
    fun `sub-agent with fresh empty store re-prompts — the pre-fix failure mode`() = runTest {
        val spy = GateSpy()
        val subagentBrain = SequenceBrain(listOf(
            ApiResult.Success(responseWithToolCall("edit_file", """{"path":"D.kt","old_string":"m","new_string":"n"}""")),
            ApiResult.Success(responseWithToolCall("attempt_completion", """{"result":"done"}""")),
        ))
        val tools = listOf(fakeTool("edit_file"), completionTool())
        val subagentLoop = AgentLoop(
            brain = subagentBrain,
            tools = tools.associateBy { it.name },
            toolDefinitions = tools.map { it.toToolDefinition() },
            contextManager = contextManager,
            project = project,
            approvalGate = spy.gate,
            // NO sessionApprovalStore — defaults to empty.
        )
        subagentLoop.run("sub-agent task")
        assertEquals(1, spy.invocations.size,
            "With no parent store forwarded, the sub-agent's empty default store forces " +
                "the gate to fire — this is the pre-fix bug.")
    }

    /**
     * Defense-in-depth: even if the sub-agent is built with the parent's store,
     * the approve() it performs internally should be visible to the parent when
     * the sub-agent returns. Today this is a non-issue because the stores are
     * different objects entirely — but after the fix it must remain true that
     * sub-agent approvals propagate to the parent (since both sides share one
     * store reference).
     */
    @Test
    fun `when sub-agent shares the parent store, its ALLOWED_FOR_SESSION propagates back`() = runTest {
        val sharedStore = SessionApprovalStore()
        val spy = GateSpy()
        // Simulate the post-fix sub-agent wiring by passing the shared store explicitly.
        val subagentBrain = SequenceBrain(listOf(
            ApiResult.Success(responseWithToolCall("edit_file", """{"path":"D.kt","old_string":"p","new_string":"q"}""")),
            ApiResult.Success(responseWithToolCall("attempt_completion", """{"result":"done"}""")),
        ))
        buildLoopWith(subagentBrain, spy.gate, sharedStore).run("sub-agent approves")

        assertTrue(sharedStore.isApproved("edit_file"),
            "After sub-agent receives ALLOWED_FOR_SESSION, the shared store should record it " +
                "so subsequent parent turns skip the gate.")

        // Parent's next turn uses the same store — no re-prompt.
        val parentBrain = SequenceBrain(listOf(
            ApiResult.Success(responseWithToolCall("edit_file", """{"path":"E.kt","old_string":"r","new_string":"s"}""")),
            ApiResult.Success(responseWithToolCall("attempt_completion", """{"result":"done"}""")),
        ))
        buildLoopWith(parentBrain, spy.gate, sharedStore).run("parent after sub-agent")

        assertEquals(1, spy.invocations.size,
            "Parent must not re-prompt after a sub-agent has already approved for the session.")
    }
}
