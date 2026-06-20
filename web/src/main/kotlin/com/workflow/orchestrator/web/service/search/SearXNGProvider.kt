package com.workflow.orchestrator.web.service.search

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.workflow.orchestrator.core.web.SearchProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.coroutines.cancellation.CancellationException

/**
 * [SearchProvider] backed by a self-hosted SearXNG instance.
 *
 * Hits `$baseUrl/search?q=<query>&format=json&safesearch=1&categories=general`.
 * No auth header required — SearXNG is expected to be an internal/trusted endpoint.
 *
 * Error mapping:
 * - HTTP 401 → `PROVIDER_AUTH_FAILED`
 * - Any non-200 → failure with status code message
 * - Malformed / unexpected JSON → `PROVIDER_MALFORMED_RESPONSE`
 */
class SearXNGProvider(
    private val baseUrl: String,
    private val client: OkHttpClient,
) : SearchProvider {

    override val id: SearchProvider.ProviderId = SearchProvider.ProviderId.SEARXNG

    // ── Moshi ──────────────────────────────────────────────────────────────────

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @JsonClass(generateAdapter = true)
    internal data class SearXNGResult(
        val title: String = "",
        val url: String = "",
        val content: String = "",
    )

    @JsonClass(generateAdapter = true)
    internal data class SearXNGResponse(
        val results: List<SearXNGResult> = emptyList(),
    )

    private val responseAdapter = moshi.adapter(SearXNGResponse::class.java)

    // ── SearchProvider ─────────────────────────────────────────────────────────

    override suspend fun validate(): Result<Unit> {
        return if (baseUrl.isNotBlank()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("SearXNG baseUrl must not be blank"))
        }
    }

    override suspend fun search(query: String, maxResults: Int): Result<List<SearchProvider.RawHit>> {
        // Fix C (web_search) — capture the Job BEFORE entering withContext so the cancel hook
        // can close the OkHttp socket immediately when the coroutine is stopped.
        val callJob = currentCoroutineContext()[Job]
        return withContext(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
                val url = "$baseUrl/search?q=$encodedQuery&format=json&safesearch=1&categories=general"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val call = client.newCall(request)
                val cancelHook = callJob?.invokeOnCompletion { cause ->
                    if (cause is CancellationException) runCatching { call.cancel() }
                }
                try {
                    call.execute().use { response ->
                        if (response.code == 401) {
                            return@withContext Result.failure(
                                IllegalStateException("PROVIDER_AUTH_FAILED")
                            )
                        }
                        if (!response.isSuccessful) {
                            return@withContext Result.failure(
                                IllegalStateException("SearXNG returned HTTP ${response.code}")
                            )
                        }

                        val body = readBodyCapped(response) ?: ""
                        val parsed = try {
                            responseAdapter.fromJson(body)
                        } catch (_: Exception) {
                            null
                        }

                        if (parsed == null) {
                            return@withContext Result.failure(
                                IllegalStateException("PROVIDER_MALFORMED_RESPONSE")
                            )
                        }

                        val hits = parsed.results
                            .take(maxResults)
                            .mapIndexed { index, item ->
                                SearchProvider.RawHit(
                                    title = item.title,
                                    url = item.url,
                                    snippet = item.content,
                                    rank = index,
                                )
                            }
                        Result.success(hits)
                    }
                } finally {
                    cancelHook?.dispose()
                }
            } catch (e: CancellationException) {
                // I13 — re-throw, never swallow. Per agent/CLAUDE.md contract.
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
