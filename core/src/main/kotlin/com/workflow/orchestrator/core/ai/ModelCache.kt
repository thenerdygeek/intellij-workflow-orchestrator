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

    fun reset() {
        models = emptyList()
        lastFetchMs = 0
    }
}
