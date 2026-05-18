package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.project.Project
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins the pre-exit steering-queue drain behavior added to fix the
 * "queued message disappears just before attempt_completion" bug.
 *
 * The producer (UI) and consumer (loop) of [SteeringMessage] have asymmetric
 * lifetimes: the user can enqueue any time up to onComplete, but the loop only
 * consumes at the top of each iteration. The final iteration of a successful
 * task has no successor — without an exit-time drain the message is silently
 * dropped (and `AgentController.onComplete` clears the queue, deleting it).
 *
 * The fix: at the Completion and SessionHandoff exit branches, drain the queue
 * and continue the loop instead of returning. At Failed exits, drain and fire
 * `onSteeringDrained` so the UI promotes the queued pill to a real chat bubble
 * (no continue — hard failures should not retry).
 */
class AgentLoopExitDrainTest {

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
        val callIndex = AtomicInteger(0)

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
            val i = callIndex.getAndIncrement()
            if (i >= responses.size) {
                return ApiResult.Error(ErrorType.SERVER_ERROR, "No more scripted responses (call #$i)")
            }
            return responses[i]
        }

        override fun estimateTokens(text: String): Int = text.toByteArray().size / 4
        override fun cancelActiveRequest() {}
    }

    private fun benignTool(toolName: String): AgentTool = object : AgentTool {
        override val name = toolName
        override val description = "Test tool $toolName"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project): ToolResult =
            ToolResult(content = "ok", summary = "ok", tokenEstimate = 5)
    }

    private fun buildLoop(
        brain: LlmBrain,
        tools: List<AgentTool>,
        steeringQueue: ConcurrentLinkedQueue<SteeringMessage>?,
        onSteeringDrained: ((List<String>) -> Unit)?,
        maxIterations: Int = 10,
        feedbackEnabled: Boolean = false,
    ): AgentLoop {
        val project = mockk<Project>(relaxed = true)
        val contextManager = ContextManager(maxInputTokens = 100_000)
        return AgentLoop(
            brain = brain,
            tools = tools.associateBy { it.name },
            toolDefinitions = tools.map { it.toToolDefinition() },
            contextManager = contextManager,
            project = project,
            maxIterations = maxIterations,
            steeringQueue = steeringQueue,
            onSteeringDrained = onSteeringDrained,
            feedbackEnabled = feedbackEnabled,
        )
    }

    @Test
    fun `attempt_completion with queued steering drains and continues instead of exiting`() = runTest {
        val steeringQueue = ConcurrentLinkedQueue<SteeringMessage>()
        val drainedIds = mutableListOf<List<String>>()

        // Iteration 1: LLM calls attempt_completion. The tool's execute() enqueues a
        // steering message before returning (simulates "user typed mid-final-stream").
        // The fix should drain that message into context, set userInputReceivedInToolCall,
        // and continue the loop INSTEAD of returning LoopResult.Completed.
        //
        // Iteration 2: LLM sees the steering text and calls attempt_completion again.
        // This time the queue is empty so the loop exits normally.
        // The enqueue is gated by a counter so only iteration 1 populates the queue.
        val enqueueCount = AtomicInteger(0)
        val gatedCompletion = object : AgentTool {
            override val name = "attempt_completion"
            override val description = "Test"
            override val parameters = FunctionParameters(properties = emptyMap())
            override val allowedWorkers = setOf(WorkerType.CODER)
            override suspend fun execute(params: JsonObject, project: Project): ToolResult {
                if (enqueueCount.getAndIncrement() == 0) {
                    steeringQueue.offer(SteeringMessage(id = "steer-1", text = "actually, also do X"))
                }
                return ToolResult(content = "Done.", summary = "Done.", tokenEstimate = 5, isCompletion = true)
            }
        }

        val brain = SequenceBrain(listOf(
            ApiResult.Success(toolCallResponse("attempt_completion", """{"kind":"done","result":"Done."}""")),
            ApiResult.Success(toolCallResponse("attempt_completion", """{"kind":"done","result":"Done again."}"""))
        ))

        val loop = buildLoop(
            brain = brain,
            tools = listOf(gatedCompletion),
            steeringQueue = steeringQueue,
            onSteeringDrained = { ids -> drainedIds.add(ids) }
        )

        val result = loop.run("Do the thing")

        // Pre-fix: loop exits on iteration 1 with Completed and drainedIds stays empty
        //          (the queued message gets dropped by AgentController.onComplete's clear()).
        // Post-fix: drainedIds == [["steer-1"]] and the brain was called twice.
        assertEquals(1, drainedIds.size) {
            "Expected exactly one steering drain at exit, got $drainedIds"
        }
        assertEquals(listOf("steer-1"), drainedIds[0]) {
            "Drained IDs should match the enqueued steering message"
        }
        assertEquals(2, brain.callIndex.get()) {
            "Brain should have been called twice — iter 1 (intercepted completion), iter 2 (after steering injected)"
        }
        assertTrue(result is LoopResult.Completed) {
            "Loop should ultimately complete on iteration 2 once the queue is empty"
        }
        assertTrue(steeringQueue.isEmpty()) {
            "Steering queue should be drained, not lingering for onComplete to clear()"
        }
    }

    @Test
    fun `new_task SessionHandoff with queued steering defers handoff and continues`() = runTest {
        val steeringQueue = ConcurrentLinkedQueue<SteeringMessage>()
        val drainedIds = mutableListOf<List<String>>()

        val enqueueCount = AtomicInteger(0)
        val handoffTool = object : AgentTool {
            override val name = "new_task"
            override val description = "Test handoff"
            override val parameters = FunctionParameters(properties = emptyMap())
            override val allowedWorkers = setOf(WorkerType.CODER)
            override suspend fun execute(params: JsonObject, project: Project): ToolResult {
                if (enqueueCount.getAndIncrement() == 0) {
                    steeringQueue.offer(SteeringMessage(id = "steer-2", text = "wait, do this first"))
                    return ToolResult.sessionHandoff(
                        content = "Handing off",
                        summary = "Handoff",
                        tokenEstimate = 5,
                        context = "next session context"
                    )
                }
                // Iteration 2 — no queue, real completion path.
                return ToolResult(content = "Done.", summary = "Done.", tokenEstimate = 5, isCompletion = true)
            }
        }

        // Iteration 1 emits new_task, iteration 2 emits the (same) tool which this
        // time returns a Completion ToolResult. AgentLoop dispatches by tool name +
        // result.type, so a single tool can return either handoff or completion.
        val brain = SequenceBrain(listOf(
            ApiResult.Success(toolCallResponse("new_task", """{"context":"next session"}""")),
            ApiResult.Success(toolCallResponse("new_task", """{"context":"done"}"""))
        ))

        val loop = buildLoop(
            brain = brain,
            tools = listOf(handoffTool),
            steeringQueue = steeringQueue,
            onSteeringDrained = { ids -> drainedIds.add(ids) }
        )

        val result = loop.run("Start work")

        // Pre-fix: iteration 1 returns SessionHandoff, steering message lost.
        // Post-fix: drain fires, loop continues, iteration 2 completes normally.
        assertEquals(1, drainedIds.size) {
            "Expected exactly one steering drain at handoff exit, got $drainedIds"
        }
        assertEquals(listOf("steer-2"), drainedIds[0])
        assertEquals(2, brain.callIndex.get()) {
            "Brain should have been called twice — handoff deferred, then completion"
        }
        assertTrue(result is LoopResult.Completed) {
            "Loop should resolve to Completed on iteration 2; got $result"
        }
        assertTrue(steeringQueue.isEmpty())
    }

    @Test
    fun `Failed exit with queued steering promotes the message to UI via makeFailed drain`() = runTest {
        val steeringQueue = ConcurrentLinkedQueue<SteeringMessage>()
        val drainedIds = mutableListOf<List<String>>()

        // Drive the failure via 3 consecutive empty responses (MAX_CONSECUTIVE_EMPTIES = 3).
        // Critical: enqueue INSIDE iteration 3's chatStream so that Stage 0.5 of iter 3
        // has already run with an empty queue. The message then sits in the queue until
        // makeFailed fires — exclusively exercising promoteSteeringQueueOnFailure, with
        // no possible Stage 0.5 confound. If the fix is reverted (drain removed from
        // makeFailed), drainedIds stays empty and this test fails.
        val callIndex = AtomicInteger(0)
        val brain = object : LlmBrain {
            override val modelId = "test-model"
            override suspend fun chat(
                messages: List<ChatMessage>,
                tools: List<ToolDefinition>?,
                maxTokens: Int?,
                toolChoice: JsonElement?
            ): ApiResult<ChatCompletionResponse> {
                throw UnsupportedOperationException()
            }

            override suspend fun chatStream(
                messages: List<ChatMessage>,
                tools: List<ToolDefinition>?,
                maxTokens: Int?,
                onChunk: suspend (StreamChunk) -> Unit
            ): ApiResult<ChatCompletionResponse> {
                // Call index 2 == iteration 3. Stage 0.5 has already run for iter 3
                // (with an empty queue); enqueueing here means the message survives
                // until makeFailed runs at the bottom of iter 3.
                if (callIndex.getAndIncrement() == 2) {
                    steeringQueue.offer(SteeringMessage(id = "steer-3", text = "abandon all hope"))
                }
                return ApiResult.Success(emptyResponse())
            }

            override fun estimateTokens(text: String) = text.toByteArray().size / 4
            override fun cancelActiveRequest() {}
        }

        val loop = buildLoop(
            brain = brain,
            tools = listOf(benignTool("attempt_completion")),
            steeringQueue = steeringQueue,
            onSteeringDrained = { ids -> drainedIds.add(ids) }
        )

        val result = loop.run("This will fail")

        assertTrue(result is LoopResult.Failed) {
            "Expected Failed from max consecutive empties, got $result"
        }
        assertEquals(3, callIndex.get()) {
            "Brain should have been called 3 times (MAX_CONSECUTIVE_EMPTIES)"
        }
        // Pre-fix: drainedIds is empty (queue silently cleared by AgentController.onComplete).
        // Post-fix: exactly one drain — fired by promoteSteeringQueueOnFailure inside makeFailed.
        assertEquals(listOf(listOf("steer-3")), drainedIds) {
            "Expected exactly one drain from promoteSteeringQueueOnFailure, got $drainedIds"
        }
        assertTrue(steeringQueue.isEmpty()) {
            "Queue must be empty after loop exit so onComplete's clear() is a no-op"
        }
    }

    @Test
    fun `feedbackEnabled attempt_completion with queued steering drains symmetrically`() = runTest {
        val steeringQueue = ConcurrentLinkedQueue<SteeringMessage>()
        val drainedIds = mutableListOf<List<String>>()

        // Iter 1: LLM emits attempt_completion. The tool's execute() enqueues steering.
        // The Completion branch with feedbackEnabled=true should:
        //   - collapse the pair
        //   - set pendingCompletion + awaitingFeedback
        //   - add the feedback nudge
        //   - DRAIN STEERING (the symmetric fix) — fires onSteeringDrained
        //   - continue the loop
        // Iter 2: queue is empty (already drained in iter 1's exit, not Stage 0.5).
        //   LLM emits attempt_completion again; awaitingFeedback=true → falls into
        //   else branch → returns LoopResult.Completed cleanly.
        val enqueueCount = AtomicInteger(0)
        val gatedCompletion = object : AgentTool {
            override val name = "attempt_completion"
            override val description = "Test"
            override val parameters = FunctionParameters(properties = emptyMap())
            override val allowedWorkers = setOf(WorkerType.CODER)
            override suspend fun execute(params: JsonObject, project: Project): ToolResult {
                if (enqueueCount.getAndIncrement() == 0) {
                    steeringQueue.offer(SteeringMessage(id = "steer-fb", text = "one more thing"))
                }
                return ToolResult(content = "Done.", summary = "Done.", tokenEstimate = 5, isCompletion = true)
            }
        }

        val brain = SequenceBrain(listOf(
            ApiResult.Success(toolCallResponse("attempt_completion", """{"kind":"done","result":"Done."}""")),
            ApiResult.Success(toolCallResponse("attempt_completion", """{"kind":"done","result":"Done again."}"""))
        ))

        val loop = buildLoop(
            brain = brain,
            tools = listOf(gatedCompletion),
            steeringQueue = steeringQueue,
            onSteeringDrained = { ids -> drainedIds.add(ids) },
            feedbackEnabled = true,
        )

        val result = loop.run("Do the thing")

        // NOTE — this is a SYMMETRY test, not a discrimination test.
        // Without the symmetric drain in the feedbackEnabled branch, Stage 0.5 of
        // iter 2 still drains the message and the final LLM context is identical
        // — so this test passes both with and without the fix. (Confirmed empirically
        // by reverting the AgentLoop change: this test passes; the other three fail.)
        // The value of the symmetric drain is defensive (faster UI pill cleanup,
        // independence from Stage 0.5's correctness), not functional. We keep this
        // test as a regression guard against breaking the feedbackEnabled flow when
        // touching the drain helper.
        assertTrue(result is LoopResult.Completed) {
            "Expected Completed on iter 2, got $result"
        }
        assertEquals(2, brain.callIndex.get()) {
            "Brain should be called twice — iter 1 attempt_completion, iter 2 re-attempt"
        }
        assertEquals(listOf(listOf("steer-fb")), drainedIds) {
            "Steering should be drained exactly once during the feedbackEnabled exit, got $drainedIds"
        }
        assertTrue(steeringQueue.isEmpty())
    }
}
