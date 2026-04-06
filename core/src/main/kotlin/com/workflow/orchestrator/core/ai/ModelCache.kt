package com.workflow.orchestrator.core.ai

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.ai.dto.ModelInfo
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cached model list with TTL and best-pick logic.
 * Thread-safe, deduplicates concurrent fetches via Mutex.
 */
object ModelCache {

    private val LOG = Logger.getInstance(ModelCache::class.java)
    private var models: List<ModelInfo> = emptyList()
    private var lastFetchMs: Long = 0
    private val lock = Mutex()

    private const val TTL_MS = 24L * 60 * 60 * 1000

    suspend fun getModels(
        client: SourcegraphChatClient,
        force: Boolean = false
    ): List<ModelInfo> {
        lock.withLock {
            val now = System.currentTimeMillis()
            if (!force && models.isNotEmpty() && (now - lastFetchMs) < TTL_MS) {
                return models
            }
            val result = client.listModels()
            if (result is ApiResult.Success) {
                models = result.data.data
                lastFetchMs = now
                LOG.info("ModelCache: fetched ${models.size} models")
            } else {
                LOG.warn("ModelCache: failed to fetch models, using cached (${models.size})")
            }
            return models
        }
    }

    fun getCached(): List<ModelInfo> = models

    /** Populate cache from externally-fetched models (e.g., settings page). */
    fun populateFromExternal(modelList: List<ModelInfo>) {
        models = modelList
        lastFetchMs = System.currentTimeMillis()
    }

    fun pickBest(models: List<ModelInfo>): ModelInfo? {
        if (models.isEmpty()) return null
        val anthropic = models.filter { it.provider == "anthropic" }

        // 1. Latest Opus with thinking
        anthropic.filter { it.isOpusClass && it.isThinkingModel }
            .maxByOrNull { it.created }?.let { return it }

        // 2. Latest Opus (any)
        anthropic.filter { it.isOpusClass }
            .maxByOrNull { it.created }?.let { return it }

        // 3. Latest Sonnet
        anthropic.filter { it.modelName.lowercase().contains("sonnet") }
            .maxByOrNull { it.created }?.let { return it }

        // 4. Any Anthropic model
        anthropic.maxByOrNull { it.created }?.let { return it }

        // 5. Anything
        return models.maxByOrNull { it.created }
    }

    /** Pick Sonnet thinking model for text generation (commit messages, PR descriptions). */
    fun pickSonnetThinking(models: List<ModelInfo>): ModelInfo? {
        val anthropic = models.filter { it.provider == "anthropic" }
        // Prefer Sonnet with thinking/reasoning
        anthropic.filter { it.modelName.lowercase().contains("sonnet") && it.isThinkingModel }
            .maxByOrNull { it.created }?.let { return it }
        // Fall back to any Sonnet
        anthropic.filter { it.modelName.lowercase().contains("sonnet") }
            .maxByOrNull { it.created }?.let { return it }
        return null
    }

    /** Pick the cheapest available model (Haiku > Sonnet > anything) for lightweight tasks. */
    fun pickCheapest(models: List<ModelInfo>): ModelInfo? {
        if (models.isEmpty()) return null
        val anthropic = models.filter { it.provider == "anthropic" }

        // 1. Haiku (cheapest Anthropic)
        anthropic.filter { it.modelName.lowercase().contains("haiku") }
            .maxByOrNull { it.created }?.let { return it }

        // 2. Sonnet (mid-tier)
        anthropic.filter { it.modelName.lowercase().contains("sonnet") }
            .maxByOrNull { it.created }?.let { return it }

        // 3. Anything
        return anthropic.maxByOrNull { it.created } ?: models.maxByOrNull { it.created }
    }

    /**
     * Build an ordered fallback chain for smart model fallback.
     * Order: Opus thinking → Opus → Sonnet thinking → Sonnet.
     * Skips tiers with no matching model. Excludes Haiku.
     * Picks the latest (by created timestamp) model per tier.
     */
    fun buildFallbackChain(models: List<ModelInfo>): List<String> {
        val anthropic = models.filter { it.provider == "anthropic" }
        val chain = mutableListOf<String>()

        // Tier 1: Opus thinking
        anthropic.filter { it.isOpusClass && it.isThinkingModel }
            .maxByOrNull { it.created }?.let { chain.add(it.id) }

        // Tier 2: Opus non-thinking
        anthropic.filter { it.isOpusClass && !it.isThinkingModel }
            .maxByOrNull { it.created }?.let { chain.add(it.id) }

        // Tier 3: Sonnet thinking
        anthropic.filter { it.modelName.lowercase().contains("sonnet") && it.isThinkingModel }
            .maxByOrNull { it.created }?.let { chain.add(it.id) }

        // Tier 4: Sonnet non-thinking
        anthropic.filter { it.modelName.lowercase().contains("sonnet") && !it.isThinkingModel }
            .maxByOrNull { it.created }?.let { chain.add(it.id) }

        return chain
    }

    fun reset() {
        models = emptyList()
        lastFetchMs = 0
    }
}
