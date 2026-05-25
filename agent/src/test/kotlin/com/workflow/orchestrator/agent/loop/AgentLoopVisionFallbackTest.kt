package com.workflow.orchestrator.agent.loop

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.ModelCatalogService
import com.workflow.orchestrator.core.ai.dto.*
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.ModelPricingRegistry
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Phase 7 followup F-P6FU-3 — wires the vision filter into [AgentLoop.run]'s **L2
 * tier-escalation** path.
 *
 * (The L1 `ModelFallbackManager` fallback path was removed in Phase 6f — see audit
 * agent-runtime:F-20. Same-model brain recycling + L2 tier escalation are now the
 * sole automatic recovery layers, and L2 owns the vision filter that L1 used to.)
 *
 * **Risk being closed:** when the in-flight payload contains image parts AND the loop
 * exhausts same-tier recycles, L2 tier escalation advances down `cachedFallbackChain`.
 * Without a vision filter it could land on a model lacking the `vision` capability; the
 * gateway silently strips image content for non-vision models, producing a confusing
 * reply with no error.
 *
 * **Contract pinned here:** image payload + mixed-vision chain → L2 must skip the
 * non-vision intermediary and escalate directly to the next vision-capable model.
 */
class AgentLoopVisionFallbackTest {

    private lateinit var project: Project
    private lateinit var contextManager: ContextManager

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        contextManager = ContextManager(maxInputTokens = 100_000)
    }

    @AfterEach
    fun stopModelPricingWatcher() {
        // AgentLoop touches ModelPricingRegistry which starts a FileSystemWatcher;
        // shut it down so macOS ThreadLeakTracker doesn't trip on the watcher
        // thread after the test completes.
        runCatching { ModelPricingRegistry.resetForTests() }
    }

    // ---- Helpers ----

    private fun successResponse(content: String? = null, toolCalls: List<ToolCall>? = null): ChatCompletionResponse =
        ChatCompletionResponse(
            id = "resp-${System.nanoTime()}",
            choices = listOf(
                Choice(
                    index = 0,
                    message = ChatMessage(role = "assistant", content = content, toolCalls = toolCalls),
                    finishReason = if (toolCalls != null) "tool_calls" else "stop",
                ),
            ),
            usage = UsageInfo(promptTokens = 100, completionTokens = 20, totalTokens = 120),
        )

    private fun completionToolCallResponse(): ChatCompletionResponse =
        successResponse(
            toolCalls = listOf(
                ToolCall(
                    id = "call_done_${System.nanoTime()}",
                    type = "function",
                    function = FunctionCall(name = "attempt_completion", arguments = """{"result":"Done."}"""),
                ),
            ),
        )

    private fun completionTool(): AgentTool = object : AgentTool {
        override val name = "attempt_completion"
        override val description = "Signal task completion"
        override val parameters = FunctionParameters(properties = emptyMap())
        override val allowedWorkers = setOf(WorkerType.CODER)
        override suspend fun execute(params: JsonObject, project: Project) =
            ToolResult(content = "Done.", summary = "Done.", tokenEstimate = 5, isCompletion = true)
    }

    private class SequenceBrain(
        override val modelId: String,
        private val responses: List<ApiResult<ChatCompletionResponse>>,
    ) : LlmBrain {
        private var callIndex = 0

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
            if (callIndex >= responses.size) {
                return ApiResult.Error(ErrorType.SERVER_ERROR, "No more scripted responses (call #$callIndex)")
            }
            return responses[callIndex++]
        }

        override fun estimateTokens(text: String): Int = text.toByteArray().size / 4
        override fun cancelActiveRequest() {}
    }

    private class StubCatalog(
        private val visionSupport: Map<String, Boolean>,
    ) : ModelCatalogService(
        baseUrl = "http://stub",
        tokenProvider = { "stub_token" },
        httpClientOverride = OkHttpClient.Builder().build(),
    ) {
        override fun supportsVision(modelRef: String): Boolean = visionSupport[modelRef] == true
    }

    // ---- Tests ----

    /**
     * Phase 7 followup F-P6FU-3 — L2 tier escalation must apply a vision filter.
     *
     * Also pins the Phase-6f reachability fix: L2 fires on the plain fallback-chain path
     * (`compactOnTimeoutExhaustion = false`, i.e. the `model_fallback` strategy). Because
     * `MAX_TIMEOUT_RETRIES = MAX_SAME_TIER_RECYCLES + 1`, the retry block is still entered on
     * the error after recycles are exhausted, so L2 can fire without needing a compaction reset.
     *
     * **Setup:** `cachedFallbackChain = [primary::vision, mid::no-vision, late::vision]`,
     * primary returns TIMEOUT errors so the loop: (1) recycles same-tier MAX_SAME_TIER_RECYCLES
     * times, (2) enters L2 tier escalation.
     *
     * **Assertion:** L2 must skip `mid::no-vision` and escalate directly to `late::vision`.
     * Without the vision filter, L2 would silently advance to `mid::no-vision` and the gateway
     * would strip images, producing the confusing-reply failure mode.
     */
    @Test
    fun `image payload + L2 tier escalation skips non-vision intermediaries`() = runTest {
        val chain = listOf("primary::vision", "mid::no-vision", "late::vision")
        val catalog = StubCatalog(
            visionSupport = mapOf(
                "primary::vision" to true,
                "mid::no-vision" to false,
                "late::vision" to true,
            ),
        )

        val midBrainCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val lateBrain = SequenceBrain(
            modelId = "late::vision",
            responses = listOf(ApiResult.Success(completionToolCallResponse())),
        )

        // Each erroring brain hands back a fresh TIMEOUT sequence on every (re)build so the
        // loop keeps timing out on a tier until recycles are exhausted and L2 advances.
        val brainFactory: suspend (String, String?) -> LlmBrain = { modelId, _ ->
            when (modelId) {
                "primary::vision" -> SequenceBrain(
                    modelId = "primary::vision",
                    responses = List(12) { ApiResult.Error(ErrorType.TIMEOUT, "Read timed out") },
                )
                "mid::no-vision" -> {
                    midBrainCalls.incrementAndGet()
                    SequenceBrain(
                        modelId = "mid::no-vision",
                        responses = listOf(ApiResult.Error(ErrorType.TIMEOUT, "should not be reached")),
                    )
                }
                "late::vision" -> lateBrain
                else -> throw IllegalArgumentException("Unexpected model: $modelId")
            }
        }

        // Image-bearing user message so vision filter must engage.
        contextManager.addAssistantMessage(
            ChatMessage(
                role = "user",
                content = "look",
                parts = listOf(
                    ContentPart.Image(sha256 = "abc", mime = "image/png", originalFilename = null),
                    ContentPart.Text("look"),
                ),
            ),
        )

        val tools = listOf(completionTool())
        val loop = AgentLoop(
            brain = SequenceBrain(
                modelId = "primary::vision",
                responses = List(12) { ApiResult.Error(ErrorType.TIMEOUT, "Read timed out") },
            ),
            tools = tools.associateBy { it.name },
            toolDefinitions = tools.map { it.toToolDefinition() },
            contextManager = contextManager,
            project = project,
            brainFactory = brainFactory,
            cachedFallbackChain = chain,
            modelCatalogService = catalog,
            // No compactOnTimeoutExhaustion — proves the model_fallback path reaches L2 on its own.
        )

        val result = loop.run("Fix the bug")

        assertTrue(result is LoopResult.Completed, "Expected Completed but got $result")
        assertEquals(0, midBrainCalls.get(),
            "L2 must skip non-vision intermediaries when payload has image parts — mid::no-vision must NOT be invoked")
    }

    /**
     * L2 escalation with an image payload and a chain whose only fallback tiers lack vision →
     * the loop must NOT silently route to a non-vision model. It surfaces the verbatim
     * user-visible error and fails. Mirror of the removed L1 all-non-vision case, now on the
     * L2 path (reachable on the plain fallback-chain path after the Phase-6f reachability fix).
     */
    @Test
    fun `image payload + L2 with all-non-vision chain produces user-visible no-vision error`() = runTest {
        val chain = listOf("primary::vision", "mid::no-vision", "late::no-vision")
        val catalog = StubCatalog(
            visionSupport = mapOf(
                "primary::vision" to true,
                "mid::no-vision" to false,
                "late::no-vision" to false,
            ),
        )

        val nonVisionCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val brainFactory: suspend (String, String?) -> LlmBrain = { modelId, _ ->
            when (modelId) {
                "primary::vision" -> SequenceBrain(
                    modelId = "primary::vision",
                    responses = List(12) { ApiResult.Error(ErrorType.TIMEOUT, "Read timed out") },
                )
                "mid::no-vision", "late::no-vision" -> {
                    nonVisionCalls.incrementAndGet()
                    SequenceBrain(
                        modelId = modelId,
                        responses = listOf(ApiResult.Error(ErrorType.TIMEOUT, "should not be reached")),
                    )
                }
                else -> throw IllegalArgumentException("Unexpected model: $modelId")
            }
        }

        contextManager.addAssistantMessage(
            ChatMessage(
                role = "user",
                content = "look",
                parts = listOf(
                    ContentPart.Image(sha256 = "abc", mime = "image/png", originalFilename = null),
                    ContentPart.Text("look"),
                ),
            ),
        )

        val tools = listOf(completionTool())
        val loop = AgentLoop(
            brain = SequenceBrain(
                modelId = "primary::vision",
                responses = List(12) { ApiResult.Error(ErrorType.TIMEOUT, "Read timed out") },
            ),
            tools = tools.associateBy { it.name },
            toolDefinitions = tools.map { it.toToolDefinition() },
            contextManager = contextManager,
            project = project,
            brainFactory = brainFactory,
            cachedFallbackChain = chain,
            modelCatalogService = catalog,
        )

        val result = loop.run("Fix the bug")

        assertTrue(result is LoopResult.Failed, "Expected Failed but got $result")
        assertEquals(0, nonVisionCalls.get(),
            "L2 must NOT invoke any non-vision fallback brain when the payload has image parts")
        assertTrue(
            (result as LoopResult.Failed).error.contains(
                "no vision-capable fallback available, retry on primary or remove image",
                ignoreCase = true,
            ),
            "expected user-visible no-vision-fallback message, got: ${result.error}",
        )
    }
}
