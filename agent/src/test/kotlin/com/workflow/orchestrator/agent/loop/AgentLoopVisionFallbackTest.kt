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
 * Phase 6 review followup — wires `ModelFallbackManager.fallbackChainForVision()`
 * into [AgentLoop.run].
 *
 * **Risk being closed:** prior to this change, when the in-flight payload contained
 * image parts AND the primary model errored, [AgentLoop] would fall back to the
 * next model in the chain via [ModelFallbackManager.onNetworkError] — even if that
 * next model lacked the `vision` capability. The gateway silently strips image
 * content for non-vision models, producing a confusing reply with no error
 * (handoff §"Risk register" item 9).
 *
 * **Contract pinned by these tests:**
 *
 *  1. **Image payload + mixed chain** — fallback must skip non-vision-capable
 *     models, advancing to the next vision-capable model in the chain.
 *  2. **Image payload + all-non-vision chain** — fallback must surface a
 *     user-visible message containing the verbatim string
 *     `"no vision-capable fallback available, retry on primary or remove image"`
 *     and abort the loop instead of silently routing to a non-vision model.
 *  3. **Text-only payload** — vision filter does not engage; fallback chain
 *     advances normally (regression guard for non-image turns).
 *
 * Tests written first per Phase 2-6 TDD discipline.
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
     * Image payload + mixed-vision chain → fallback advances PAST the non-vision
     * intermediary to the first vision-capable model.
     *
     * Chain: primary[vision=true] → mid[vision=false] → late[vision=true]
     * Primary errors. Expected: AgentLoop swaps directly to `late`, NEVER touches `mid`.
     */
    @Test
    fun `image payload with mixed-vision chain skips non-vision intermediaries on fallback`() = runTest {
        val chain = listOf("primary::vision", "mid::no-vision", "late::vision")
        val fallbackManager = ModelFallbackManager(chain)
        val catalog = StubCatalog(
            visionSupport = mapOf(
                "primary::vision" to true,
                "mid::no-vision" to false,
                "late::vision" to true,
            ),
        )

        val primaryBrain = SequenceBrain(
            modelId = "primary::vision",
            responses = listOf(ApiResult.Error(ErrorType.NETWORK_ERROR, "Connection refused")),
        )
        val lateBrain = SequenceBrain(
            modelId = "late::vision",
            responses = listOf(ApiResult.Success(completionToolCallResponse())),
        )
        val midBrainCalls = java.util.concurrent.atomic.AtomicInteger(0)

        val brainFactory: suspend (String, String?) -> LlmBrain = { modelId, _ ->
            when (modelId) {
                "primary::vision" -> primaryBrain
                "mid::no-vision" -> {
                    midBrainCalls.incrementAndGet()
                    SequenceBrain(
                        modelId = "mid::no-vision",
                        responses = listOf(ApiResult.Error(ErrorType.NETWORK_ERROR, "should not be reached")),
                    )
                }
                "late::vision" -> lateBrain
                else -> throw IllegalArgumentException("Unexpected model: $modelId")
            }
        }

        // Seed contextManager with an image-bearing user message so the in-flight
        // payload contains image parts.
        contextManager.addUserMessage("look at this")
        // Replace the just-added user message with one carrying parts. (ContextManager
        // exposes addAssistantMessage which takes a full ChatMessage, so we use that.)
        contextManager.addAssistantMessage(
            ChatMessage(
                role = "user",
                content = "look at this",
                parts = listOf(
                    ContentPart.Image(sha256 = "abc", mime = "image/png", originalFilename = null),
                    ContentPart.Text("look at this"),
                ),
            ),
        )

        val tools = listOf(completionTool())

        val loop = AgentLoop(
            brain = primaryBrain,
            tools = tools.associateBy { it.name },
            toolDefinitions = tools.map { it.toToolDefinition() },
            contextManager = contextManager,
            project = project,
            fallbackManager = fallbackManager,
            brainFactory = brainFactory,
            modelCatalogService = catalog,
        )

        val result = loop.run("Fix the bug")

        assertTrue(result is LoopResult.Completed, "Expected Completed but got $result")
        assertEquals(0, midBrainCalls.get(), "non-vision mid model must NOT be invoked")
        // FallbackManager should now sit on `late::vision` (chain index 2), not `mid` (index 1).
        assertEquals("late::vision", fallbackManager.getCurrentModelId())
    }

    /**
     * Image payload + chain with NO vision-capable fallback after primary →
     * AgentLoop must NOT swap to a non-vision model. Loop fails with a
     * user-visible message containing the verbatim "no vision-capable fallback
     * available" string from the reviewer's recommendation.
     */
    @Test
    fun `image payload with all-non-vision fallback chain produces user-visible no-vision-fallback error`() = runTest {
        val chain = listOf("primary::vision", "mid::no-vision", "late::no-vision")
        val fallbackManager = ModelFallbackManager(chain)
        val catalog = StubCatalog(
            visionSupport = mapOf(
                "primary::vision" to true,
                "mid::no-vision" to false,
                "late::no-vision" to false,
            ),
        )

        val primaryBrain = SequenceBrain(
            modelId = "primary::vision",
            // Three errors so the loop exhausts retries even if it doesn't fall back.
            responses = List(5) { ApiResult.Error(ErrorType.NETWORK_ERROR, "Connection refused") },
        )
        val nonVisionCalls = java.util.concurrent.atomic.AtomicInteger(0)

        val brainFactory: suspend (String, String?) -> LlmBrain = { modelId, _ ->
            when (modelId) {
                "primary::vision" -> primaryBrain
                "mid::no-vision", "late::no-vision" -> {
                    nonVisionCalls.incrementAndGet()
                    SequenceBrain(
                        modelId = modelId,
                        responses = listOf(ApiResult.Error(ErrorType.NETWORK_ERROR, "should not be reached")),
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
            brain = primaryBrain,
            tools = tools.associateBy { it.name },
            toolDefinitions = tools.map { it.toToolDefinition() },
            contextManager = contextManager,
            project = project,
            fallbackManager = fallbackManager,
            brainFactory = brainFactory,
            modelCatalogService = catalog,
        )

        val result = loop.run("Fix the bug")

        // Loop must fail (not complete on a non-vision model)
        assertTrue(result is LoopResult.Failed, "Expected Failed but got $result")
        assertEquals(0, nonVisionCalls.get(), "non-vision fallback brains must NEVER be invoked when no vision fallback exists")
        val errMsg = (result as LoopResult.Failed).error
        assertTrue(
            errMsg.contains("no vision-capable fallback available, retry on primary or remove image", ignoreCase = true),
            "expected user-visible no-vision-fallback message, got: $errMsg",
        )
    }

    /**
     * Regression guard: text-only payload + mixed-vision chain → fallback ignores
     * vision capability and advances to the very next model (mid::no-vision)
     * normally. The vision filter must NEVER engage on text-only turns.
     */
    @Test
    fun `text-only payload uses unfiltered fallback chain even when catalog is wired`() = runTest {
        val chain = listOf("primary::vision", "mid::no-vision", "late::vision")
        val fallbackManager = ModelFallbackManager(chain)
        val catalog = StubCatalog(
            visionSupport = mapOf(
                "primary::vision" to true,
                "mid::no-vision" to false,
                "late::vision" to true,
            ),
        )

        val primaryBrain = SequenceBrain(
            modelId = "primary::vision",
            responses = listOf(ApiResult.Error(ErrorType.NETWORK_ERROR, "Connection refused")),
        )
        val midBrain = SequenceBrain(
            modelId = "mid::no-vision",
            responses = listOf(ApiResult.Success(completionToolCallResponse())),
        )

        val brainFactory: suspend (String, String?) -> LlmBrain = { modelId, _ ->
            when (modelId) {
                "primary::vision" -> primaryBrain
                "mid::no-vision" -> midBrain
                else -> throw IllegalArgumentException("Unexpected model: $modelId")
            }
        }

        // No image parts on the user message — text-only.
        contextManager.addUserMessage("Fix the bug")

        val tools = listOf(completionTool())
        val loop = AgentLoop(
            brain = primaryBrain,
            tools = tools.associateBy { it.name },
            toolDefinitions = tools.map { it.toToolDefinition() },
            contextManager = contextManager,
            project = project,
            fallbackManager = fallbackManager,
            brainFactory = brainFactory,
            modelCatalogService = catalog,
        )

        val result = loop.run("Fix the bug")

        assertTrue(result is LoopResult.Completed, "Expected Completed but got $result")
        // Should have advanced to mid::no-vision normally (vision filter doesn't engage on text-only)
        assertEquals("mid::no-vision", fallbackManager.getCurrentModelId())
    }
}
