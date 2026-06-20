package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionCall
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ToolCall
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.cancel.ToolCancellationRegistry
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.Choice
import com.workflow.orchestrator.core.ai.dto.StreamChunk
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.ai.dto.UsageInfo
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.ModelPricingRegistry
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Behavioral coverage for the per-tool-call cancellation funnel in
 * [AgentLoop.executeToolCalls] (Task 1.4 — unified tool stop).
 *
 * Contract:
 *  1. Cancelling the *registered per-tool-call job* (the user-Stop path, via
 *     [ToolCancellationRegistry.cancel]) yields a "Stopped by user" tool result
 *     and the loop CONTINUES to completion.
 *  2. Cancelling the *whole loop coroutine* (job.cancel()) still propagates — the
 *     funnel re-throws and does NOT convert it to a "Stopped by user" result.
 */
class AgentLoopToolStopTest {

    private lateinit var project: Project
    private lateinit var contextManager: ContextManager

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        contextManager = ContextManager(maxInputTokens = 100_000)
    }

    @AfterEach
    fun tearDown() {
        // Same FileSystemWatcher daemon-thread teardown as AgentLoopTest — see note there.
        runCatching { ModelPricingRegistry.resetForTests() }
    }

    // ---- Harness (mirrors AgentLoopTest's builders) ----

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
        ): ApiResult<ChatCompletionResponse> =
            throw UnsupportedOperationException("AgentLoop uses chatStream, not chat")

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
        override fun cancelActiveRequest() { /* no-op: test brain has no live request to cancel */ }
    }

    private fun sequenceBrain(vararg responses: ChatCompletionResponse): SequenceBrain =
        SequenceBrain(responses.map { ApiResult.Success(it) })

    private fun completionTool(summary: String = "Done.") = object : AgentTool {
        override val name = "attempt_completion"
        override val description = "Test tool attempt_completion"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project) = ToolResult(
            content = summary,
            summary = summary,
            tokenEstimate = 5,
            isCompletion = true,
        )
    }

    private fun buildLoop(
        brain: LlmBrain,
        tools: List<AgentTool>,
        onToolCall: (ToolCallProgress) -> Unit = {},
    ): AgentLoop {
        val toolMap = tools.associateBy { it.name }
        val toolDefs = tools.map { it.toToolDefinition() }
        return AgentLoop(
            brain = brain,
            tools = toolMap,
            toolDefinitions = toolDefs,
            contextManager = contextManager,
            project = project,
            onToolCall = onToolCall,
            maxIterations = 200,
        )
    }

    /**
     * A fake tool that signals when it has started, then suspends until its coroutine
     * is cancelled. `started` lets the test cancel exactly while the tool is in-flight.
     */
    private class SlowTool(val started: CompletableDeferred<Unit>) : AgentTool {
        override val name = "slow_tool"
        override val description = "Test tool slow_tool"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)

        // CRITICAL: avoids runTest's virtual clock fast-forwarding to the default 120s
        // withTimeoutOrNull and returning a *timeout* result before our stop lands.
        override val timeoutMs = Long.MAX_VALUE
        override suspend fun execute(params: JsonObject, project: Project): ToolResult {
            started.complete(Unit)
            awaitCancellation()
        }
    }

    // ---- Tests ----

    @Test
    fun `stopping a running tool yields a Stopped-by-user result and the loop continues`() = runTest {
        val started = CompletableDeferred<Unit>()
        val slow = SlowTool(started)

        val brain = sequenceBrain(
            toolCallResponse("slow_tool" to "{}"),
            toolCallResponse("attempt_completion" to """{"result":"Continued past the stop."}""")
        )

        // Capture every ToolCallProgress: the pre-execution one carries the toolCallId we
        // must stop (it is non-deterministic — call_${idx}_${nanoTime()}), and the
        // post-execution one carries the recorded tool result (summary/output).
        val progress = java.util.concurrent.CopyOnWriteArrayList<ToolCallProgress>()
        val loop = buildLoop(
            brain,
            listOf(slow, completionTool("Continued past the stop.")),
            onToolCall = { progress.add(it) },
        )

        lateinit var result: LoopResult
        val loopJob = launch { result = loop.run("do the slow thing") }

        // Wait until the tool is actually running, then stop exactly that tool call.
        started.await()
        val id = progress.map { it.toolCallId }.distinct().first { ToolCancellationRegistry.isActive(it) }
        val cancelled = ToolCancellationRegistry.cancel(id)
        assertTrue(cancelled, "Expected a registered slow_tool job to cancel for id=$id")

        loopJob.join()

        // 1. The recorded tool result for the stopped call is "Stopped by user".
        val stoppedRow = progress.first { it.toolCallId == id && it.result.isNotBlank() }
        assertTrue(
            stoppedRow.result.contains("Stopped by user") ||
                (stoppedRow.output?.contains("Stopped by user") == true) ||
                (stoppedRow.output?.contains("stopped by the user") == true),
            "Expected a Stopped-by-user tool result for the cancelled call, got: " +
                "result='${stoppedRow.result}', output='${stoppedRow.output}'"
        )

        // 2. The loop continued to attempt_completion — Completed, NOT Failed/Cancelled.
        //    LoopResult.Completed (vs Cancelled) is the behavioral proof that the loop's
        //    private `cancelled` flag stayed false: a true flag would short-circuit the
        //    next for-loop iteration into makeCancelled() before attempt_completion ran.
        assertTrue(result is LoopResult.Completed, "Expected Completed but got $result")
        assertFalse(loopJob.isCancelled, "Loop coroutine must not be cancelled on a per-tool stop")
    }

    @Test
    fun `cancelling the whole loop still propagates (not converted to a result)`() = runTest {
        val started = CompletableDeferred<Unit>()
        val slow = SlowTool(started)

        val brain = sequenceBrain(
            toolCallResponse("slow_tool" to "{}"),
            toolCallResponse("attempt_completion" to """{"result":"should never reach here"}""")
        )

        val progress = java.util.concurrent.CopyOnWriteArrayList<ToolCallProgress>()
        val loop = buildLoop(
            brain,
            listOf(slow, completionTool()),
            onToolCall = { progress.add(it) },
        )

        var caught: Throwable? = null
        val loopJob = launch {
            try {
                loop.run("do the slow thing")
            } catch (e: CancellationException) {
                caught = e
                throw e
            }
        }

        started.await()
        // Cancel the LOOP's job (a plain CancellationException — NOT a user stop).
        loopJob.cancel()
        loopJob.join()

        assertTrue(loopJob.isCancelled, "Whole-loop cancel must leave the loop coroutine cancelled")
        assertTrue(caught is CancellationException, "Funnel must re-throw the loop cancellation, got: $caught")
        // The funnel must NOT have converted the cancel into a Stopped-by-user tool result.
        assertFalse(
            progress.any {
                it.result.contains("Stopped by user") ||
                    (it.output?.contains("Stopped by user") == true) ||
                    (it.output?.contains("stopped by the user") == true)
            },
            "Whole-loop cancel must not produce a Stopped-by-user tool result: $progress"
        )
    }
}
