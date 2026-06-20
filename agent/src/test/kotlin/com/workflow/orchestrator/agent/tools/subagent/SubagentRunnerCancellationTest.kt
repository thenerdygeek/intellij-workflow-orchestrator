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
import com.workflow.orchestrator.core.ai.dto.StreamChunk
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ModelPricingRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
     * early-abort guard at line 264 of SubagentRunner.kt, which this test must NOT hit).
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
        // This test exercises the ELSE branch of the new catch (CancellationException) block
        // (SubagentRunner.kt ~line 490). The brain calls runner.abort() and then throws
        // CancellationException — simulating a user abort that arrives while the LLM is
        // streaming. At that point abortRequested == true, so the block must return a
        // cancelled SubagentRunResult, NOT re-throw.
        //
        // Critical: abort() is called INSIDE the brain's chatStream(), i.e. AFTER the early
        // abort guard at line 264 has already passed (abortRequested was false then). This
        // is the only code path that the existing tests do NOT cover — test 2 above calls
        // abort() BEFORE run(), so it hits the early guard and never reaches the new block.
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
}
