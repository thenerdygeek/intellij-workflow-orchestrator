package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.LlmProvider
import com.workflow.orchestrator.core.ai.ModelCache
import com.workflow.orchestrator.core.ai.ModelCatalogService
import com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.ContextWindow
import com.workflow.orchestrator.core.ai.dto.ModelCatalog
import com.workflow.orchestrator.core.ai.dto.StreamChunk
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.ai.protocol.ToolProtocol
import com.workflow.orchestrator.core.ai.protocol.XmlToolProtocol
import com.workflow.orchestrator.core.api.InternalApi
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.serialization.json.JsonElement

/**
 * The Tier-1 XML provider: an [LlmProvider] facade over the existing transport brain (today a
 * BrainRouter that already routes image-bearing turns to the /stream endpoint) + ModelCatalogService
 * + an XmlToolProtocol. Reading ONE provider replaces "a brain plus a router that re-decides each turn"
 * (spec §6: "absorb BrainRouter's per-message image routing into the provider").
 *
 * Pure delegation — chat/chatStream/tokens/cancel/interrupt forward to [delegate]; catalog accessors
 * forward to [catalogService] (nullable-safe, mirroring ContextManager's null handling). No behavior change.
 *
 * **Same-instance guarantee:** [delegate] must be the SAME brain instance passed to AgentLoop's `brain`
 * parameter, so that cancelActiveRequest()/interruptStream()/temperature writes (from the Stop button
 * and temperature-reset paths) reach the actual in-flight request. Do NOT wrap a different brain.
 *
 * **BrainRouter routing preserved:** [delegate] is typically a BrainRouter which already routes
 * image-bearing turns to /.api/completions/stream internally. XmlLlmProvider is transparent to this —
 * chatStream() delegates to the same BrainRouter.chatStream() that today's loop calls directly
 * (pinned by XmlLlmProviderImageRoutingCharacterizationTest).
 *
 * **Phase 4 note:** AgentLoop continues to hold `brain: LlmBrain` and `toolProtocol` separately in
 * 0b-1. This provider is constructed additively (not yet plumbed as the loop's single input). Full
 * loop rewiring is out of scope until Phase 4.
 */
@InternalApi
class XmlLlmProvider(
    private val delegate: LlmBrain,
    private val catalogService: ModelCatalogService?,
    override val toolProtocol: ToolProtocol = XmlToolProtocol(),
) : LlmProvider {

    override val modelId: String get() = delegate.modelId
    override fun estimateTokens(text: String): Int = delegate.estimateTokens(text)
    override fun cancelActiveRequest() = delegate.cancelActiveRequest()
    override fun interruptStream() = delegate.interruptStream()
    override var temperature: Double
        get() = delegate.temperature
        set(v) { delegate.temperature = v }

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
        toolChoice: JsonElement?,
    ): ApiResult<ChatCompletionResponse> = delegate.chat(messages, tools, maxTokens, toolChoice)

    override suspend fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        maxTokens: Int?,
        onChunk: suspend (StreamChunk) -> Unit,
    ): ApiResult<ChatCompletionResponse> = delegate.chatStream(messages, tools, maxTokens, onChunk)

    override suspend fun getCatalog(force: Boolean): ModelCatalog? = catalogService?.getCatalog(force)
    override fun getContextWindow(modelRef: String, tier: String): ContextWindow? = catalogService?.getContextWindow(modelRef, tier)
    override fun supportsVision(modelRef: String): Boolean = catalogService?.supportsVision(modelRef) ?: false
    override fun supportsTools(modelRef: String): Boolean = catalogService?.supportsTools(modelRef) ?: true
    override fun getDefaultChatModel(): String? = catalogService?.getDefaultChatModel()
    override fun getLatestStreamApiVersion(): Int =
        catalogService?.getLatestStreamApiVersion() ?: ModelCatalogService.DEFAULT_STREAM_API_VERSION

    // GAP3 — delegate to today's catalog/cache (behavior-neutral).
    override fun getStatus(modelRef: String): String? = catalogService?.getStatus(modelRef)
    override fun buildFallbackChain(): List<String> = ModelCache.buildFallbackChain(ModelCache.getCached())

    // GAP2 — XML transport never sees raw HTTP error status (it parses SSE frames), so null today.
    override fun classifyStreamLine(line: String): String? = toolProtocol.classifyStreamLine(line)
    override fun classifyHttpError(statusCode: Int, body: String): String? = null
}
