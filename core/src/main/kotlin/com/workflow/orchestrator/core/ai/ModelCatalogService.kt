package com.workflow.orchestrator.core.ai

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.ai.dto.ClientConfig
import com.workflow.orchestrator.core.ai.dto.ContextWindow
import com.workflow.orchestrator.core.ai.dto.ModelCatalog
import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Live read of Sourcegraph's model catalog and client configuration.
 *
 * Replaces the codebase's hard-coded model assumptions (150K context, name-heuristic
 * vision detection, hard-coded model defaults) with values from
 * `/.api/modelconfig/supported-models.json` and `/.api/client-config`.
 *
 * **Cache:** Both endpoints cache for 1 hour by default. Mutex-guarded; concurrent
 * `getCatalog()` calls share the in-flight result.
 *
 * **Isolation policy:** This service follows `SourcegraphChatClient`'s pattern of
 * constructing its own `OkHttpClient` rather than going through
 * `HttpClientFactory.clientFor(ServiceType.SOURCEGRAPH)` — Sourcegraph (Cody Enterprise)
 * is sensitive to shared interceptor stacks and shared connection pools, and the
 * shared `CachingInterceptor` is explicitly forbidden on Sourcegraph endpoints.
 * See `project_sourcegraph_isolation.md`. The `httpClientOverride` parameter is
 * used by tests + the future "Refresh capabilities" Settings button.
 *
 * **Tier:** Hard-coded to `enterprise` for v1. The user's instance is enterprise
 * per probe baseline; deferred a proper `pluginSettings.getCurrentTier()` setting
 * to v2.
 *
 * Spec: `docs/research/2026-05-02-multimodal-agent-design.md` §Architecture > ModelCatalogService
 * Plan: `docs/research/2026-05-02-multimodal-agent-plan.md` §Phase 2
 */
open class ModelCatalogService(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val cacheTtl: Duration = Duration.ofHours(1),
    httpClientOverride: OkHttpClient? = null,
    connectTimeoutSeconds: Long = 10,
    readTimeoutSeconds: Long = 30
) {
    private val log = Logger.getInstance(ModelCatalogService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val mutex = Mutex()
    @Volatile private var cachedCatalog: ModelCatalog? = null
    @Volatile private var cachedConfig: ClientConfig? = null
    @Volatile private var catalogFetchedAt: Instant? = null
    @Volatile private var configFetchedAt: Instant? = null

    /**
     * Plain `OkHttpClient` per Sourcegraph isolation policy: no shared connection
     * pool, no `CachingInterceptor`, no `MutationInvalidationInterceptor`. Auth uses
     * the TOKEN scheme (`Authorization: token <token>`) per Sourcegraph spec.
     */
    private val httpClient: OkHttpClient by lazy {
        httpClientOverride ?: OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenProvider, AuthScheme.TOKEN))
            .build()
    }

    suspend fun getCatalog(force: Boolean = false): ModelCatalog? = mutex.withLock {
        if (!force && isFresh(catalogFetchedAt)) return@withLock cachedCatalog
        val fetched = fetchCatalogFromGateway()
        if (fetched != null) {
            cachedCatalog = fetched
            catalogFetchedAt = Instant.now()
        }
        fetched
    }

    suspend fun getClientConfig(force: Boolean = false): ClientConfig? = mutex.withLock {
        if (!force && isFresh(configFetchedAt)) return@withLock cachedConfig
        val fetched = fetchClientConfigFromGateway()
        if (fetched != null) {
            cachedConfig = fetched
            configFetchedAt = Instant.now()
        }
        fetched
    }

    /** Returns the catalog's default chat `modelRef`, or `null` if catalog not loaded. */
    fun getDefaultChatModel(): String? = cachedCatalog?.defaultModels?.chat

    /**
     * Per-tier context window for [modelRef]. Reads `modelConfigAllTiers[tier]`
     * first (the REAL value — e.g. enterprise → 132K non-thinking) and falls back
     * to the top-level `contextWindow` only when no tier override exists.
     *
     * Returns `null` if catalog not loaded or model unknown.
     */
    fun getContextWindow(modelRef: String, tier: String = "enterprise"): ContextWindow? {
        val model = cachedCatalog?.models?.firstOrNull { it.modelRef == modelRef } ?: return null
        return model.modelConfigAllTiers?.get(tier)?.contextWindow
            ?: model.contextWindow
    }

    fun supportsVision(modelRef: String): Boolean =
        cachedCatalog?.models?.firstOrNull { it.modelRef == modelRef }
            ?.capabilities?.contains("vision") == true

    fun supportsTools(modelRef: String): Boolean =
        cachedCatalog?.models?.firstOrNull { it.modelRef == modelRef }
            ?.capabilities?.contains("tools") == true

    /** Catalog status: "experimental" | "beta" | "stable" | "deprecated", or `null` if model unknown. */
    fun getStatus(modelRef: String): String? =
        cachedCatalog?.models?.firstOrNull { it.modelRef == modelRef }?.status

    /**
     * Latest stream API version negotiated via `/.api/client-config`.
     * Defaults to 8 (the lowest version known to support image content parts) when
     * client-config has not been fetched.
     */
    open fun getLatestStreamApiVersion(): Int =
        cachedConfig?.latestSupportedCompletionsStreamAPIVersion ?: DEFAULT_STREAM_API_VERSION

    /** Force-refresh both endpoints in parallel. Used by Settings "Refresh capabilities" button. */
    suspend fun refresh(): Unit = coroutineScope {
        val a = async { getCatalog(force = true) }
        val b = async { getClientConfig(force = true) }
        a.await()
        b.await()
    }

    private fun isFresh(at: Instant?): Boolean =
        at != null && Duration.between(at, Instant.now()) < cacheTtl

    private suspend fun fetchCatalogFromGateway(): ModelCatalog? = withContext(Dispatchers.IO) {
        if (tokenProvider() == null) {
            log.debug("[ModelCatalog] No Sourcegraph token; skipping catalog fetch")
            return@withContext null
        }
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + CATALOG_PATH)
            .get()
            .build()
        runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    log.debug("[ModelCatalog] Catalog fetch HTTP ${resp.code}")
                    return@use null
                }
                val body = resp.body?.string() ?: return@use null
                json.decodeFromString(ModelCatalog.serializer(), body)
            }
        }.onFailure { e ->
            // IOException (network) or SerializationException — both expected on degraded paths.
            if (e is IOException) log.debug("[ModelCatalog] Catalog network error: ${e.message}")
            else log.warn("[ModelCatalog] Catalog parse error: ${e.message}")
        }.getOrNull()
    }

    private suspend fun fetchClientConfigFromGateway(): ClientConfig? = withContext(Dispatchers.IO) {
        if (tokenProvider() == null) {
            log.debug("[ModelCatalog] No Sourcegraph token; skipping client-config fetch")
            return@withContext null
        }
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + CLIENT_CONFIG_PATH)
            .get()
            .build()
        runCatching {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    log.debug("[ModelCatalog] ClientConfig fetch HTTP ${resp.code}")
                    return@use null
                }
                val body = resp.body?.string() ?: return@use null
                json.decodeFromString(ClientConfig.serializer(), body)
            }
        }.onFailure { e ->
            if (e is IOException) log.debug("[ModelCatalog] ClientConfig network error: ${e.message}")
            else log.warn("[ModelCatalog] ClientConfig parse error: ${e.message}")
        }.getOrNull()
    }

    companion object {
        const val CATALOG_PATH = "/.api/modelconfig/supported-models.json"
        const val CLIENT_CONFIG_PATH = "/.api/client-config"
        /**
         * Default stream API version when `/.api/client-config` has not been
         * fetched. v8 is the lowest version that accepts `image_url` content
         * parts on `/.api/completions/stream`.
         */
        const val DEFAULT_STREAM_API_VERSION = 8
    }
}
