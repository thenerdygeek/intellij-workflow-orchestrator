package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ContextWindow
import com.workflow.orchestrator.core.ai.dto.ModelCatalog
import com.workflow.orchestrator.core.ai.protocol.ToolProtocol
import com.workflow.orchestrator.core.api.InternalApi

/**
 * Widens [LlmBrain] (transport: chat/chatStream/tokens) with the surfaces the agent loop +
 * compaction need from a provider but that the narrow brain interface lacks:
 *  - model catalog / capability / context-window (today: ModelCatalogService; consumed by
 *    ContextManager.effectiveMaxInputTokens and AgentLoop's vision-filtered fallback);
 *  - provider error classification (today: GatewayErrorDetector → "upstream_timeout");
 *  - the active [ToolProtocol] (XML today; native in Phase 4).
 *
 * Phase 0b-1 ships only the XML adapter (XmlLlmProvider, in :agent). AnthropicDirectProvider is Phase 4.
 *
 * @InternalApi: public for B; unfrozen.
 */
@InternalApi
interface LlmProvider : LlmBrain {

    /** The tool-calling paradigm this provider speaks. */
    val toolProtocol: ToolProtocol

    /** Live model catalog (today: ModelCatalogService.getCatalog). Native = static Anthropic catalog (Phase 4). */
    suspend fun getCatalog(force: Boolean = false): ModelCatalog?

    /** Per-model context window — denominator of ContextManager's compaction threshold. */
    fun getContextWindow(modelRef: String, tier: String = "enterprise"): ContextWindow?

    fun supportsVision(modelRef: String): Boolean
    fun supportsTools(modelRef: String): Boolean
    fun getDefaultChatModel(): String?

    /** Sourcegraph-specific stream API version negotiation; native providers may return a constant. */
    fun getLatestStreamApiVersion(): Int

    /**
     * Model lifecycle status (today: ModelCatalogService.getStatus → "deprecated"/etc.; consumed via
     * `catalog?.getStatus(...)` at AgentController.kt:2069/:2128). Null = unknown/active. (GAP3)
     */
    fun getStatus(modelRef: String): String?

    /**
     * Ordered model fallback chain. **VERIFIED:** the real consumer (`AgentService.kt:1940`) calls
     * `ModelCache.buildFallbackChain(ModelCache.getCached())` — i.e. it derives the chain from the
     * CACHED model list, NOT from a modelRef. So this accessor is parameterless and the XML adapter
     * delegates exactly to `ModelCache.buildFallbackChain(ModelCache.getCached())` (behavior-neutral). (GAP3)
     */
    fun buildFallbackChain(): List<String>

    /**
     * Normalize a raw stream line into a finish-reason the loop understands (e.g. "upstream_timeout").
     * Delegates to [toolProtocol] for XML; native maps HTTP error events (Phase 4). Null = no special handling.
     */
    fun classifyStreamLine(line: String): String?

    /**
     * Classify a raw HTTP error (status + body) into a normalized finish-reason. Native providers see
     * HTTP 529/413 as a STATUS (not an SSE frame), so the loop's overflow/retry logic (consumer:
     * `AgentLoop.isContextOverflowError`, AgentLoop.kt:1854) needs this surface. The XML adapter returns
     * null (today's XML transport parses SSE frames, never raw HTTP error status) — behavior-neutral. (GAP2)
     */
    fun classifyHttpError(statusCode: Int, body: String): String?
}
