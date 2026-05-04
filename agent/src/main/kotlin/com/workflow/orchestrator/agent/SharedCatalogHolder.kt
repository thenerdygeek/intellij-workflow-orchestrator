package com.workflow.orchestrator.agent

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.ai.ModelCatalogService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Cached, lazy holder for the shared [ModelCatalogService] instance reused
 * across `wrapBrainWithRouter` calls within and across sessions.
 *
 * Phase 7 followup F-P6FU-1 + F-P6FU-2 — the Phase 6 fix-up reviewer flagged:
 *
 *  - **F-P6FU-1.** No test pinned the keying contract (same URL → same
 *    instance; URL change → fresh instance; warm-up failure → no propagation).
 *  - **F-P6FU-2.** The original implementation built the cache key as
 *    `sgUrl to tokenProvider` (the lambda half) but only compared `sgUrl` on
 *    lookup. The comment claimed "rebuild on token rotation" but the code did
 *    not enforce it — the lambda half was dead. Resolution: drop the dead
 *    half. The 1-hour catalog TTL plus `getCatalog(force = true)` covers
 *    rotation.
 *
 * Lifetime: as long as the owning [scope] (in production, the
 * [com.intellij.openapi.components.Service]-injected `cs` of `AgentService`).
 *
 * Thread safety: all mutating access is guarded by [lock]. `get` is the only
 * public entry point.
 */
internal class SharedCatalogHolder(
    private val scope: CoroutineScope,
    private val factory: (sgUrl: String, tokenProvider: () -> String?) -> ModelCatalogService,
    private val warmUp: suspend (ModelCatalogService) -> Unit,
) {
    private val lock = Any()

    @Volatile private var cachedKey: String? = null
    @Volatile private var cached: ModelCatalogService? = null

    /**
     * Returns a [ModelCatalogService] keyed by [sgUrl]. If the cached service
     * was constructed with the same URL, the cached instance is returned;
     * otherwise a fresh service is constructed via [factory] and a warm-up
     * coroutine is launched in [scope].
     *
     * The warm-up runs fire-and-forget. Failures are swallowed with a debug
     * log — the next `getCatalog()` call by [com.workflow.orchestrator.agent.loop.AgentLoop]
     * (or any other consumer) will retry on demand, gated by the catalog's
     * own internal mutex.
     */
    /**
     * Returns the most-recently-cached catalog without constructing a fresh
     * one. Used by [com.workflow.orchestrator.agent.loop.ContextManager] which
     * needs the catalog reference at session start but doesn't have the
     * `(sgUrl, tokenProvider)` pair handy. Returns `null` until [get] has
     * been called at least once with valid args (which `wrapBrainWithRouter`
     * does early in [com.workflow.orchestrator.agent.AgentService.executeTask]).
     */
    fun peek(): ModelCatalogService? = cached

    fun get(sgUrl: String, tokenProvider: () -> String?): ModelCatalogService = synchronized(lock) {
        val existing = cached
        if (existing != null && cachedKey == sgUrl) {
            return existing
        }
        val fresh = factory(sgUrl, tokenProvider)
        cached = fresh
        cachedKey = sgUrl
        scope.launch(Dispatchers.IO) {
            runCatching { warmUp(fresh) }
                .onFailure { e ->
                    LOG.debug("[SharedCatalogHolder] warm-up failed (non-fatal): ${e.message}")
                }
        }
        fresh
    }

    companion object {
        private val LOG = Logger.getInstance(SharedCatalogHolder::class.java)
    }
}
