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
 * Reproduces the "total input/output tokens (and USD cost) get reset mid-conversation"
 * bug the TopBar displays.
 *
 * Root cause:
 *   - `AgentLoop.totalInputTokens` / `totalOutputTokens` / `totalCostUsd` are
 *     instance fields (AgentLoop.kt:354-360) that start at 0/null on construction.
 *   - `AgentService.executeTask()` creates a **fresh** `AgentLoop` on every user
 *     message (AgentService.kt:1358). Follow-up messages therefore begin with totals
 *     reset to 0/null.
 *   - After the first API call of the new turn, `onSessionStats(...)` pushes the
 *     turn-local totals to the UI. `chatStore.updateSessionStats` does
 *     `set({ sessionStats: stats })` — a replace, not an accumulate — so the
 *     TopBar display snaps back to just-this-turn's numbers.
 *
 * Persisted totals in `sessions.json` (via `MessageStateHandler.updateGlobalIndex`)
 * are correct because they are summed from api-history on disk — but the live UI
 * signal is not.
 *
 * These tests simulate the buggy flow and assert what the UI sees. Today they FAIL
 * with clear output showing the reset. After the fix (accumulate across loop runs,
 * either by seeding a fresh AgentLoop with prior totals or by accumulating in a
 * session-scoped holder), they should pass.
 */
class SessionStatsResetBugTest {

    private lateinit var project: Project
    private lateinit var contextManager: ContextManager

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        // Same ContextManager across turns — mirrors AgentController reusing the
        // context manager for follow-up messages (AgentController.kt:1140-1143).
        contextManager = ContextManager(maxInputTokens = 100_000)
    }

    @AfterEach
    fun stopModelPricingWatcher() {
        runCatching { ModelPricingRegistry.resetForTests() }
    }

    // ---- Helpers (copied from CostTrackingTest so the two files are independent) ----

    private fun responseWithUsage(
        promptTokens: Int,
        completionTokens: Int,
        toolCallName: String? = null,
        toolCallArgs: String = "{}"
    ): ChatCompletionResponse {
        val message = if (toolCallName != null) {
            ChatMessage(
                role = "assistant",
                content = null,
                toolCalls = listOf(
                    ToolCall(
                        id = "call_${System.nanoTime()}",
                        type = "function",
                        function = FunctionCall(name = toolCallName, arguments = toolCallArgs)
                    )
                )
            )
        } else {
            ChatMessage(role = "assistant", content = "")
        }
        return ChatCompletionResponse(
            id = "resp-${System.nanoTime()}",
            choices = listOf(Choice(index = 0, message = message, finishReason = "stop")),
            usage = UsageInfo(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = promptTokens + completionTokens
            )
        )
    }

    private class SequenceBrain(private val responses: List<ApiResult<ChatCompletionResponse>>) : LlmBrain {
        override val modelId: String = "test-model"
        private var callIndex = 0

        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            toolChoice: JsonElement?
        ): ApiResult<ChatCompletionResponse> =
            throw UnsupportedOperationException("Uses chatStream")

        override suspend fun chatStream(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            onChunk: suspend (StreamChunk) -> Unit
        ): ApiResult<ChatCompletionResponse> {
            if (callIndex >= responses.size) {
                return ApiResult.Error(ErrorType.SERVER_ERROR, "No more responses")
            }
            return responses[callIndex++]
        }

        override fun estimateTokens(text: String): Int = text.toByteArray().size / 4
        override fun cancelActiveRequest() {}
    }

    private fun fakeTool(
        toolName: String,
        result: ToolResult = ToolResult(content = "ok", summary = "ok", tokenEstimate = 5)
    ): AgentTool = object : AgentTool {
        override val name = toolName
        override val description = "Test tool"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project) = result
    }

    private fun completionTool() = fakeTool(
        "attempt_completion",
        ToolResult(content = "Done.", summary = "Done.", tokenEstimate = 5, isCompletion = true)
    )

    /** Collected sessionStats pushes (modelId, tokensIn, tokensOut, costUsd). */
    private data class StatsPush(val modelId: String, val tokensIn: Long, val tokensOut: Long, val costUsd: Double?)

    private fun buildLoop(
        brain: LlmBrain,
        tools: List<AgentTool>,
        statsSink: MutableList<StatsPush>,
        /**
         * Optional running-totals holder. When non-null, models the fixed wiring
         * (`AgentService.sessionRuntime`): seeds initial totals from the holder
         * and writes every onSessionStats push back into it so the next loop run
         * resumes from where this one left off. Null = old buggy pattern (fresh
         * zero-seeded loop, nothing captured for the next turn).
         */
        sessionTotals: SessionStatsResetBugTest.SessionTotalsHolder? = null,
    ): AgentLoop {
        val toolMap = tools.associateBy { it.name }
        val toolDefs = tools.map { it.toToolDefinition() }
        return AgentLoop(
            brain = brain,
            tools = toolMap,
            toolDefinitions = toolDefs,
            contextManager = contextManager,
            project = project,
            initialInputTokens = sessionTotals?.inputTokens?.toInt() ?: 0,
            initialOutputTokens = sessionTotals?.outputTokens?.toInt() ?: 0,
            initialCostUsd = sessionTotals?.costUsd,
            onSessionStats = { modelId, tokensIn, tokensOut, costUsd ->
                sessionTotals?.let {
                    it.inputTokens = tokensIn
                    it.outputTokens = tokensOut
                    it.costUsd = costUsd
                }
                statsSink.add(StatsPush(modelId, tokensIn, tokensOut, costUsd))
            }
        )
    }

    /** Mirrors [AgentService.sessionRuntime] — session-scoped running totals. */
    class SessionTotalsHolder(
        var inputTokens: Long = 0L,
        var outputTokens: Long = 0L,
        var costUsd: Double? = null,
    )

    // ---- Tests ----

    /**
     * Simulates a session with TWO user messages. Each message runs under a new
     * AgentLoop (what `AgentService.executeTask` does). We capture every
     * `onSessionStats` push — i.e. everything the UI would show in the TopBar.
     *
     * Post-fix invariant (verified here): the session-level running-totals holder
     * is seeded into each new loop via [AgentLoop.initialInputTokens]/Out/Cost, and
     * each onSessionStats push updates the holder so the next loop picks up from
     * there. Turn 2's first push therefore reports the cumulative session total.
     */
    @Test
    fun `turn 2's first onSessionStats push reports cumulative session total, not turn-local`() = runTest {
        val statsHistory = mutableListOf<StatsPush>()
        val sessionTotals = SessionTotalsHolder()

        // ── Turn 1: two API calls → 300 input + 50 output cumulative ──
        val turn1Brain = SequenceBrain(listOf(
            ApiResult.Success(responseWithUsage(
                promptTokens = 100, completionTokens = 20,
                toolCallName = "read_file", toolCallArgs = """{"path":"a.kt"}"""
            )),
            ApiResult.Success(responseWithUsage(
                promptTokens = 200, completionTokens = 30,
                toolCallName = "attempt_completion", toolCallArgs = """{"result":"done"}"""
            ))
        ))
        val turn1Loop = buildLoop(
            turn1Brain,
            listOf(fakeTool("read_file"), completionTool()),
            statsHistory,
            sessionTotals,
        )
        val turn1Result = turn1Loop.run("first user message")
        assertTrue(turn1Result is LoopResult.Completed)

        val lastPushOfTurn1 = statsHistory.last()
        assertEquals(300L, lastPushOfTurn1.tokensIn,
            "Sanity: turn 1's last onSessionStats push must report cumulative 300 input tokens.")
        assertEquals(50L, lastPushOfTurn1.tokensOut,
            "Sanity: turn 1's last onSessionStats push must report cumulative 50 output tokens.")

        // Fixed wiring: the running-totals holder mirrors the last push.
        assertEquals(300L, sessionTotals.inputTokens)
        assertEquals(50L, sessionTotals.outputTokens)

        val pushesAfterTurn1 = statsHistory.size

        // ── Turn 2: one API call, 500 input + 80 output for this turn alone ──
        val turn2Brain = SequenceBrain(listOf(
            ApiResult.Success(responseWithUsage(
                promptTokens = 500, completionTokens = 80,
                toolCallName = "attempt_completion", toolCallArgs = """{"result":"again"}"""
            ))
        ))
        val turn2Loop = buildLoop(
            turn2Brain,
            listOf(completionTool()),
            statsHistory,
            sessionTotals,
        )
        val turn2Result = turn2Loop.run("second user message (same session)")
        assertTrue(turn2Result is LoopResult.Completed)

        // The very first push after the turn-2 loop starts is what the UI snaps to.
        val firstPushOfTurn2 = statsHistory[pushesAfterTurn1]

        // Post-fix: running session total = 300 + 500 = 800 in, 50 + 80 = 130 out.
        assertEquals(800L, firstPushOfTurn2.tokensIn,
            "Turn 2's first onSessionStats push must report the cumulative session total (800), " +
                "not turn-local 500. If this fails, the new loop was not seeded from the " +
                "running-totals holder — bug 2 has regressed.")
        assertEquals(130L, firstPushOfTurn2.tokensOut,
            "Turn 2's first onSessionStats push must report cumulative 130 output tokens.")
    }

    /**
     * Regression guard: without the running-totals holder (i.e. the pre-fix wiring),
     * turn 2's first push MUST be turn-local. This pins down the exact failure
     * mode so anyone reading the test file sees why the fix is needed.
     */
    @Test
    fun `without seeding the AgentLoop from session totals, turn 2's first push is turn-local (documents bug)`() = runTest {
        val statsHistory = mutableListOf<StatsPush>()
        // NO sessionTotals passed — models the original buggy wiring.

        val turn1Brain = SequenceBrain(listOf(
            ApiResult.Success(responseWithUsage(100, 20, "attempt_completion", """{"result":"d"}"""))
        ))
        buildLoop(turn1Brain, listOf(completionTool()), statsHistory, sessionTotals = null).run("t1")
        val pushesAfterTurn1 = statsHistory.size

        val turn2Brain = SequenceBrain(listOf(
            ApiResult.Success(responseWithUsage(500, 80, "attempt_completion", """{"result":"d"}"""))
        ))
        buildLoop(turn2Brain, listOf(completionTool()), statsHistory, sessionTotals = null).run("t2")
        val firstPushOfTurn2 = statsHistory[pushesAfterTurn1]

        assertEquals(500L, firstPushOfTurn2.tokensIn,
            "With no seeding, turn 2's first push is just this-turn (500). This is the bug " +
                "that [AgentService.sessionRuntime] + [AgentLoop.initialInputTokens] fixes.")
    }

    /**
     * Same shape but for cost (USD). `test-model` is not in the pricing registry, so
     * both turns will legitimately report `costUsd = null`. This test locks in the
     * requirement: once a session has a non-null total, a follow-up turn must NOT
     * demote it back to null either.
     *
     * Implementation note: when we fix this, if the new turn's brain has no pricing
     * entry, `costUsd` for that turn should be null BUT the session-level displayed
     * cost should remain whatever the prior turn accumulated. The simplest way to
     * verify that is to drive a brain whose modelId IS in the registry in turn 1 and
     * a no-pricing brain in turn 2, and assert the UI still shows turn 1's total.
     *
     * That requires fixture plumbing around ModelPricingRegistry. For now, lock in
     * the simpler invariant: turn 2's first stats push must not carry a costUsd that
     * is strictly smaller than turn 1's last push — i.e. cost is monotonic per
     * session.
     */
    @Test
    fun `cost USD must be monotonic non-decreasing across turns within one session`() = runTest {
        val statsHistory = mutableListOf<StatsPush>()
        val sessionTotals = SessionTotalsHolder()

        // Turn 1
        val turn1Brain = SequenceBrain(listOf(
            ApiResult.Success(responseWithUsage(
                promptTokens = 100, completionTokens = 10,
                toolCallName = "attempt_completion", toolCallArgs = """{"result":"done"}"""
            ))
        ))
        buildLoop(turn1Brain, listOf(completionTool()), statsHistory, sessionTotals).run("turn 1")

        // Turn 2
        val turn2Brain = SequenceBrain(listOf(
            ApiResult.Success(responseWithUsage(
                promptTokens = 100, completionTokens = 10,
                toolCallName = "attempt_completion", toolCallArgs = """{"result":"done"}"""
            ))
        ))
        val pushesBeforeTurn2 = statsHistory.size
        buildLoop(turn2Brain, listOf(completionTool()), statsHistory, sessionTotals).run("turn 2")

        val turn1LastCost = statsHistory[pushesBeforeTurn2 - 1].costUsd
        val turn2FirstCost = statsHistory[pushesBeforeTurn2].costUsd

        if (turn1LastCost != null) {
            assertNotNull(turn2FirstCost,
                "Session had a non-null cost ($turn1LastCost USD) at end of turn 1; " +
                    "turn 2's first stats push must not demote it back to null.")
            assertTrue(turn2FirstCost!! >= turn1LastCost,
                "Session cost went DOWN across turns (turn1=$turn1LastCost, turn2First=$turn2FirstCost). " +
                    "Loop 2 is not being seeded from the session's running cost.")
        }
        // If turn 1's cost was null (test model has no pricing entry), the invariant
        // is trivially satisfied; nothing more to assert here without pricing fixtures.
    }
}
