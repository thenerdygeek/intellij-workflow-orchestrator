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

    // B6: read lock-free by getCached() and written off-lock by populateFromExternal()/reset()
    // (settings EDT) while fetchModels() mutates under [lock]. @Volatile gives the happens-before
    // edge so readers never see a stale ref or an inconsistent (models, lastFetchMs) pair.
    @Volatile private var models: List<ModelInfo> = emptyList()

    @Volatile private var lastFetchMs: Long = 0

    private val lock = Mutex()

    private const val TTL_MS = 24L * 60 * 60 * 1000

    suspend fun getModels(
        client: SourcegraphChatClient,
        force: Boolean = false
    ): List<ModelInfo> {
        return when (val r = fetchModels(client, force)) {
            is FetchResult.Fresh -> r.models
            is FetchResult.Cached -> r.models
            is FetchResult.Failed -> r.cached
        }
    }

    sealed class FetchResult {
        data class Fresh(val models: List<ModelInfo>) : FetchResult()
        data class Cached(val models: List<ModelInfo>) : FetchResult()
        data class Failed(val cached: List<ModelInfo>, val message: String) : FetchResult()
    }

    suspend fun fetchModels(
        client: SourcegraphChatClient,
        force: Boolean = false
    ): FetchResult {
        lock.withLock {
            val now = System.currentTimeMillis()
            if (!force && models.isNotEmpty() && (now - lastFetchMs) < TTL_MS) {
                return FetchResult.Cached(models)
            }
            val result = client.listModels()
            if (result is ApiResult.Success) {
                models = result.data.data
                lastFetchMs = now
                LOG.info("ModelCache: fetched ${models.size} models")
                return FetchResult.Fresh(models)
            }
            val msg = (result as? ApiResult.Error)?.message ?: "unknown error"
            LOG.warn("ModelCache: failed to fetch models ($msg), using cached (${models.size})")
            return FetchResult.Failed(models, msg)
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
        val anthropic = models.anthropicFamily()

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
        val anthropic = models.anthropicFamily()
        // Prefer Sonnet with thinking/reasoning
        anthropic.filter { it.modelName.lowercase().contains("sonnet") && it.isThinkingModel }
            .maxByOrNull { it.created }?.let { return it }
        // Fall back to any Sonnet
        anthropic.filter { it.modelName.lowercase().contains("sonnet") }
            .maxByOrNull { it.created }?.let { return it }
        return null
    }

    /** Pick latest non-thinking Sonnet — used as the sub-agent default tier. */
    fun pickSonnetNonThinking(models: List<ModelInfo>): ModelInfo? {
        val anthropic = models.anthropicFamily()
        anthropic.filter { it.modelName.lowercase().contains("sonnet") && !it.isThinkingModel }
            .maxByOrNull { it.created }?.let { return it }
        // Fall back to any Sonnet (including thinking) so callers still get a Sonnet-class model.
        anthropic.filter { it.modelName.lowercase().contains("sonnet") }
            .maxByOrNull { it.created }?.let { return it }
        return null
    }

    /** Pick the latest Haiku model — cheapest tier, for lightweight single-shot tasks. */
    fun pickHaiku(models: List<ModelInfo>): ModelInfo? {
        val anthropic = models.anthropicFamily()
        return anthropic.filter { it.modelName.lowercase().contains("haiku") }
            .maxByOrNull { it.created }
    }

    /** Pick the cheapest available model (Haiku > Sonnet > anything) for lightweight tasks. */
    fun pickCheapest(models: List<ModelInfo>): ModelInfo? {
        if (models.isEmpty()) return null
        val anthropic = models.anthropicFamily()

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
        val anthropic = models.anthropicFamily()
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

    /**
     * Anthropic-family models, identified robustly by model NAME rather than the
     * `provider` id-prefix.
     *
     * `ModelInfo.provider` is parsed as `id.substringBefore("::")`, so it only equals
     * "anthropic" when the id is in the strict `anthropic::apiVersion::modelName` form.
     * On an Anthropic-Vertex Sourcegraph instance the prefix differs (e.g.
     * `anthropic-vertex::…`), and some instances return ids with no `::` at all (provider
     * then resolves to "unknown"). A `provider == "anthropic"` gate silently returns null
     * for ALL models there, which made `pickHaiku`/`pickSonnet*` fall through to the heavy
     * configured model (e.g. for the web-content sanitizer). The family names
     * claude/haiku/sonnet/opus are Anthropic-exclusive and `modelName` falls back to the
     * full id, so name-matching is correct AND format-robust. A `provider` substring match
     * is kept as a belt-and-braces accept.
     */
    private fun List<ModelInfo>.anthropicFamily(): List<ModelInfo> =
        filter { m ->
            m.provider.contains("anthropic", ignoreCase = true) ||
                m.modelName.lowercase().let { n ->
                    n.contains("claude") || n.contains("haiku") ||
                        n.contains("sonnet") || n.contains("opus")
                }
        }
}
