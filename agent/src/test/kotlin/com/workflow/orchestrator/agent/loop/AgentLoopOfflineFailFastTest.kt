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
import com.workflow.orchestrator.core.network.NetworkProbe
import com.workflow.orchestrator.core.network.NetworkState
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Pins the fail-fast offline path in [AgentLoop].
 *
 * When the brain returns a NETWORK_ERROR (or TIMEOUT) and [NetworkProbe.checkNow] confirms
 * the machine is OFFLINE, the loop must:
 *   1. Return [LoopResult.Failed] with [FailureReason.OFFLINE].
 *   2. Invoke [LlmBrain.chatStream] exactly once — no retry budget consumed.
 */
class AgentLoopOfflineFailFastTest {

    private lateinit var project: Project
    private lateinit var contextManager: ContextManager

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        contextManager = ContextManager(maxInputTokens = 100_000)
    }

    @AfterEach
    fun stopModelPricingWatcher() {
        runCatching { ModelPricingRegistry.resetForTests() }
    }

    // ---- Fakes ----

    /**
     * A [NetworkProbe] that always reports OFFLINE.
     * [checkNow] returns [NetworkState.OFFLINE] regardless of the target URL.
     */
    private class OfflineProbe : NetworkProbe {
        private val s = MutableStateFlow(NetworkState.OFFLINE)
        override val state: StateFlow<NetworkState> = s
        override fun reportFailure(targetUrl: String) {}
        override fun reportSuccess() {}
        override suspend fun checkNow(targetUrl: String?) = NetworkState.OFFLINE
        override suspend fun awaitOnline(timeoutMs: Long) = false
    }

    /**
     * A [LlmBrain] that always returns a NETWORK_ERROR on [chatStream].
     * Tracks the number of [chatStream] calls so the test can assert exactly-one invocation.
     */
    private class NetworkErrorBrain : LlmBrain {
        override val modelId: String = "test-model"
        var chatStreamCallCount = 0

        override suspend fun chat(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            toolChoice: JsonElement?,
        ): ApiResult<ChatCompletionResponse> = throw UnsupportedOperationException("AgentLoop uses chatStream")

        override suspend fun chatStream(
            messages: List<ChatMessage>,
            tools: List<ToolDefinition>?,
            maxTokens: Int?,
            onChunk: suspend (StreamChunk) -> Unit,
        ): ApiResult<ChatCompletionResponse> {
            chatStreamCallCount++
            return ApiResult.Error(ErrorType.NETWORK_ERROR, "Connection refused — VPN tunnel down")
        }

        override fun estimateTokens(text: String): Int = text.toByteArray().size / 4
        override fun cancelActiveRequest() {}
    }

    private fun completionTool(): AgentTool = object : AgentTool {
        override val name = "attempt_completion"
        override val description = "Signal task completion"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "Done.", summary = "Done.", tokenEstimate = 5, isCompletion = true)
    }

    // ---- Tests ----

    @Test
    fun `network error while offline fails fast with OFFLINE reason and no retries`() = runTest {
        val networkErrorBrain = NetworkErrorBrain()
        val offlineProbe = OfflineProbe()

        val tools = listOf(completionTool())
        val loop = AgentLoop(
            brain = networkErrorBrain,
            tools = tools.associateBy { it.name },
            toolDefinitions = tools.map { it.toToolDefinition() },
            contextManager = contextManager,
            project = project,
            networkProbe = offlineProbe,
            llmProbeUrl = "https://sg.example.com",
        )

        val result = loop.run("do something")

        assertTrue(result is LoopResult.Failed, "Expected LoopResult.Failed but got $result")
        assertEquals(
            FailureReason.OFFLINE,
            (result as LoopResult.Failed).reason,
            "Expected FailureReason.OFFLINE but got ${result.reason}"
        )

        // Brain must be called exactly once — offline fail-fast must not burn the retry budget.
        assertEquals(
            1,
            networkErrorBrain.chatStreamCallCount,
            "chatStream must be invoked exactly once (no retries on confirmed offline)"
        )
    }

    @Test
    fun `network error while online does NOT trigger offline fast-fail (allows normal retry)`() = runTest {
        // An online probe — checkNow always returns ONLINE
        val onlineProbe = object : NetworkProbe {
            private val s = MutableStateFlow(NetworkState.ONLINE)
            override val state: StateFlow<NetworkState> = s
            override fun reportFailure(targetUrl: String) {}
            override fun reportSuccess() {}
            override suspend fun checkNow(targetUrl: String?) = NetworkState.ONLINE
            override suspend fun awaitOnline(timeoutMs: Long) = true
        }

        // Brain returns NETWORK_ERROR every time — exhausts retries normally
        val brain = NetworkErrorBrain()

        val tools = listOf(completionTool())
        val loop = AgentLoop(
            brain = brain,
            tools = tools.associateBy { it.name },
            toolDefinitions = tools.map { it.toToolDefinition() },
            contextManager = contextManager,
            project = project,
            networkProbe = onlineProbe,
            llmProbeUrl = "https://sg.example.com",
        )

        val result = loop.run("do something")

        // Loop should exhaust retries normally and fail with API_ERROR (not OFFLINE).
        assertTrue(result is LoopResult.Failed, "Expected LoopResult.Failed but got $result")
        assertNotEquals(
            FailureReason.OFFLINE,
            (result as LoopResult.Failed).reason,
            "Should NOT fail with OFFLINE when the network probe reports ONLINE"
        )
        // Brain should be called more than once because retries fire normally.
        assertTrue(
            brain.chatStreamCallCount > 1,
            "When online, retries should fire — chatStream should be called more than once (got ${brain.chatStreamCallCount})"
        )
    }
}
