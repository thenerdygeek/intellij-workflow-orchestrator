// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.tools.subagent

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
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
import com.workflow.orchestrator.core.model.ModelPricingRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Tests for Task 2.2: SubagentRunner must re-throw a genuine CancellationException
 * (structured-concurrency teardown) instead of mapping it to a FAILED SubagentRunResult.
 *
 * Contract:
 * - When the child run throws a CancellationException and abortRequested is FALSE,
 *   runner.run(...) must PROPAGATE the CancellationException.
 * - When the user explicitly calls runner.abort() (abortRequested becomes TRUE) the existing
 *   path is unchanged: the result is a cancelled SubagentRunResult (status FAILED, error
 *   contains "cancelled"), NOT a re-throw.
 */
class SubagentRunnerCancellationTest {

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        every { project.name } returns "TestProject"
        every { project.basePath } returns "/tmp/test-project"
    }

    @AfterEach
    fun stopModelPricingWatcher() {
        runCatching { ModelPricingRegistry.resetForTests() }
    }

    // ---- Helpers (mirrored from SubagentRunnerTest) ----

    private fun stubTool(toolName: String): AgentTool = object : AgentTool {
        override val name = toolName
        override val description = "Stub tool: $toolName"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "stub:$toolName", summary = "stub", tokenEstimate = 5)
    }

    private fun buildTools(): Map<String, AgentTool> {
        val tools = mutableMapOf<String, AgentTool>()
        for (name in listOf("read_file", "search_code")) {
            tools[name] = stubTool(name)
        }
        tools["attempt_completion"] = AttemptCompletionTool()
        return tools
    }

    /**
     * A brain that always throws a CancellationException when chatStream is called.
     * This simulates structured-concurrency teardown reaching through the AgentLoop.
     */
    private class ThrowingCancellationBrain : LlmBrain {
        override val modelId: String = "test-throwing-cancellation-brain"
        override var toolNameSet: Set<String> = emptySet()
        override var paramNameSet: Set<String> = emptySet()

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
            // Simulate structured-concurrency cancellation propagating from a parent scope.
            throw CancellationException("Simulated structured-concurrency teardown")
        }

        override fun estimateTokens(text: String): Int = text.toByteArray().size / 4
        override fun cancelActiveRequest() { /* no-op for this stub */ }
    }

    private fun createRunner(brain: LlmBrain): SubagentRunner = SubagentRunner(
        brain = brain,
        coreTools = buildTools(),
        systemPrompt = "You are a test sub-agent.",
        project = project,
        maxIterations = 50,
        planMode = false,
        contextBudget = 50_000
    )

    // ---- Tests ----

    @Test
    fun `runInternal re-throws a CancellationException when not user-aborted`() = runTest {
        // Build a runner whose injected brain throws a raw CancellationException.
        // Do NOT call runner.abort() — so abortRequested stays false.
        val runner = createRunner(ThrowingCancellationBrain())

        // The exception must propagate; it must NOT be swallowed into a FAILED result.
        assertThrows<CancellationException> {
            runner.run("Find the answer", "test-agent", "test (unit-test)") {}
        }
    }

    @Test
    fun `user abort still maps to a cancelled result and does not re-throw`() = runTest {
        // Mirror pattern from SubagentRunnerTest.AbortTests: abort before running so
        // abortRequested is already true when the loop starts; result must be FAILED /
        // "cancelled", NOT a re-throw.
        val brain = ThrowingCancellationBrain()
        val runner = createRunner(brain)
        runner.abort() // Sets abortRequested = true BEFORE running.

        // Must return the cancelled SubagentRunResult, NOT propagate the CancellationException.
        val result = runner.run("Should be cancelled", "test-agent", "test (unit-test)") {}

        assertEquals(SubagentRunStatus.FAILED, result.status)
        assertTrue(
            result.error!!.contains("cancelled", ignoreCase = true),
            "Error message should contain 'cancelled' but was: ${result.error}",
        )
    }

    /**
     * A brain that holds a callback invoked in [chatStream] BEFORE throwing
     * [CancellationException]. The callback is set after the runner is constructed so it
     * can call [SubagentRunner.abort] on the runner, exercising the abort path that only
     * fires when [abortRequested] becomes true WHILE the brain is running (i.e. AFTER the
     * early-abort guard at the top of runInternal, which this test must NOT hit).
     */
    private class AbortingCancellationBrain : LlmBrain {
        override val modelId: String = "test-aborting-cancellation-brain"
        override var toolNameSet: Set<String> = emptySet()
        override var paramNameSet: Set<String> = emptySet()

        /** Set this to `{ runner.abort() }` after constructing the runner. */
        var onStream: () -> Unit = {}

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
            // 1. Trigger the abort (sets abortRequested = true on the runner).
            onStream()
            // 2. Then surface a CancellationException — the new catch(CancellationException)
            //    block should see abortRequested == true and return a cancelled result, NOT re-throw.
            throw CancellationException("Simulated cancellation after user abort")
        }

        override fun estimateTokens(text: String): Int = text.toByteArray().size / 4
        override fun cancelActiveRequest() { /* no-op for this stub */ }
    }

    @Test
    fun `abort mid-brain returns cancelled result via catch CancellationException block`() = runTest {
        // This test exercises the ELSE branch of the catch(CancellationException) block.
        // The brain calls runner.abort() and then throws CancellationException — simulating
        // a user abort that arrives while the LLM is streaming. At that point
        // abortRequested == true, so the block must return a cancelled SubagentRunResult,
        // NOT re-throw.
        //
        // Critical: abort() is called INSIDE the brain's chatStream(), i.e. AFTER the
        // early-abort guard at the top of runInternal has already passed (abortRequested
        // was false then). This is the only code path that the existing tests do NOT cover
        // — test 2 above calls abort() BEFORE run(), so it hits the early guard and never
        // reaches the catch(CancellationException) block.
        val brain = AbortingCancellationBrain()
        val runner = createRunner(brain)

        // Wire the callback now that we have the runner instance.
        brain.onStream = { runner.abort() }

        // run() must return (NOT throw): the catch(CancellationException) block's else-branch
        // intercepts abortRequested==true and returns the cancelled result.
        val result = runner.run("Do something", "test-agent", "test (unit-test)") {}

        assertEquals(SubagentRunStatus.FAILED, result.status)
        assertTrue(
            result.error!!.contains("cancelled", ignoreCase = true),
            "Error message should contain 'cancelled' but was: ${result.error}",
        )
    }

    // ---- Fix B: abort cancels the sub-agent's IN-FLIGHT tool ----

    /**
     * A fake tool that signals it has started (via [started]) and then suspends forever via
     * [awaitCancellation]. When the surrounding coroutine is cancelled, `awaitCancellation`
     * throws [CancellationException], which runs the `finally` block — flipping
     * [wasCancelled]. This lets the test prove the tool's OWN coroutine was cancelled (not
     * merely abandoned).
     */
    private class BlockingTool(
        private val started: CompletableDeferred<Unit>,
        private val wasCancelled: AtomicBoolean,
    ) : AgentTool {
        override val name = "blocking_tool"
        override val description = "Signals started, then suspends until cancelled."
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project): ToolResult {
            try {
                started.complete(Unit)
                awaitCancellation() // suspend forever; only cancellation ends this
            } finally {
                // Runs on cancellation (and only then — we never complete normally).
                wasCancelled.set(true)
            }
        }
    }

    /**
     * A scripted brain that emits ONE tool call (for [toolName]) on its first chatStream
     * call, then errors on any subsequent call. Mirrors SubagentRunnerTest.SequenceBrain's
     * tool-call response shape. Used to drive the sub-agent's inner AgentLoop into executing
     * a real (fake) tool so we can abort mid-tool.
     */
    private class SingleToolCallBrain(private val toolName: String) : LlmBrain {
        override val modelId: String = "test-single-tool-call-brain"
        override var toolNameSet: Set<String> = emptySet()
        override var paramNameSet: Set<String> = emptySet()
        private var callIndex = 0
        var cancelled = false
            private set

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
            if (callIndex++ > 0) {
                return ApiResult.Error(
                    com.workflow.orchestrator.core.model.ErrorType.SERVER_ERROR,
                    "No more scripted responses",
                )
            }
            return ApiResult.Success(
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
                                        id = "call_0_${System.nanoTime()}",
                                        type = "function",
                                        function = FunctionCall(name = toolName, arguments = "{}"),
                                    )
                                )
                            ),
                            finishReason = "tool_calls"
                        )
                    ),
                    usage = UsageInfo(promptTokens = 100, completionTokens = 30, totalTokens = 130)
                )
            )
        }

        override fun estimateTokens(text: String): Int = text.toByteArray().size / 4
        override fun cancelActiveRequest() { cancelled = true }
    }

    @Test
    @kotlinx.coroutines.ExperimentalCoroutinesApi // UnconfinedTestDispatcher
    fun `abort cancels the sub-agent's in-flight tool and run returns without throwing`() =
        runTest(UnconfinedTestDispatcher()) {
            // The sub-agent's inner AgentLoop will dispatch blocking_tool, whose execute()
            // signals `toolStarted` then awaitCancellation()s. We then call runner.abort().
            // abort() cancels the child scope wrapping loop.run → the tool's own coroutine
            // (a descendant) is cancelled → its finally flips `toolWasCancelled`. Because
            // abortRequested was set BEFORE the cancel, runInternal's
            // catch(CancellationException) returns the cancelled result (NOT a re-throw), so
            // run() RETURNS a FAILED SubagentRunResult to the caller — proving the parent
            // coroutine was not cancelled.
            val toolStarted = CompletableDeferred<Unit>()
            val toolWasCancelled = AtomicBoolean(false)

            val tools = mutableMapOf<String, AgentTool>(
                "blocking_tool" to BlockingTool(toolStarted, toolWasCancelled),
            )
            tools["attempt_completion"] = AttemptCompletionTool()

            val brain = SingleToolCallBrain("blocking_tool")
            val runner = SubagentRunner(
                brain = brain,
                coreTools = tools,
                systemPrompt = "You are a test sub-agent.",
                project = project,
                maxIterations = 50,
                planMode = false,
                contextBudget = 50_000,
            )

            // Launch the run as a sibling child so the test body can abort it while it is
            // suspended in the tool. UnconfinedTestDispatcher runs the launch eagerly up to
            // the awaitCancellation() suspension point.
            var result: SubagentRunResult? = null
            val runJob = launch {
                result = runner.run("Do the blocking thing", "test-agent", "test (unit-test)") {}
            }

            // Wait until the fake tool is actually executing (mid-flight).
            toolStarted.await()
            assertFalse(
                toolWasCancelled.get(),
                "Tool should still be running before abort()",
            )

            // Abort: cancels the child run scope → the in-flight tool coroutine is cancelled.
            runner.abort()

            // Let the cancellation unwind and the run() coroutine finish returning.
            runJob.join()

            // (2) The fake tool's coroutine was actually cancelled (its finally ran).
            assertTrue(
                toolWasCancelled.get(),
                "abort() must cancel the in-flight tool's coroutine (its awaitCancellation should have thrown)",
            )
            // (1) run() RETURNED (did not throw to the caller) with a cancelled/FAILED result —
            // proving the parent coroutine was NOT cancelled by the child-scope abort.
            assertNotNull(result, "run() must return a result (not throw) when its tool is aborted")
            assertEquals(SubagentRunStatus.FAILED, result!!.status)
            assertTrue(
                result!!.error!!.contains("cancelled", ignoreCase = true),
                "Error message should contain 'cancelled' but was: ${result!!.error}",
            )
            // The brain's stream-cancel path was also exercised by abort().
            assertTrue(brain.cancelled, "abort() must also cancel the brain's active request")
        }
}
