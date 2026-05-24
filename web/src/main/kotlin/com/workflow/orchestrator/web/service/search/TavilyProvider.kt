// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.web.service.search

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.workflow.orchestrator.core.web.SearchProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * [SearchProvider] backed by the Tavily Search API — an LLM-agent-optimised search service.
 *
 * Endpoint: POST `$baseUrl/search`
 * Auth: `api_key` field in the JSON request body (no separate auth header).
 *
 * Request shape:
 * ```json
 * {"api_key":"tvly-...","query":"...","max_results":5,"search_depth":"basic"}
 * ```
 *
 * Response shape:
 * ```json
 * {"query":"...","results":[{"title":"...","url":"...","content":"...","score":0.93}],"response_time":1.42}
 * ```
 *
 * Error mapping:
 * - HTTP 401 → `PROVIDER_AUTH_FAILED`
 * - Any non-200 → failure with status code message
 * - Malformed / unexpected JSON → `PROVIDER_MALFORMED_RESPONSE`
 */
class TavilyProvider(
    private val baseUrl: String = "https://api.tavily.com",
    private val apiKey: String?,
    private val client: OkHttpClient,
) : SearchProvider {

    override val id: SearchProvider.ProviderId = SearchProvider.ProviderId.TAVILY

    // ── Moshi ──────────────────────────────────────────────────────────────────

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @JsonClass(generateAdapter = true)
    internal data class TavilyRequestBody(
        val api_key: String,
        val query: String,
        val max_results: Int,
        val search_depth: String = "basic",
    )

    @JsonClass(generateAdapter = true)
    internal data class TavilyResult(
        val title: String = "",
        val url: String = "",
        val content: String = "",
        val score: Double = 0.0,
        val raw_content: String? = null,
    )

    @JsonClass(generateAdapter = true)
    internal data class TavilyResponse(
        val query: String = "",
        val results: List<TavilyResult> = emptyList(),
        val response_time: Double = 0.0,
    )

    private val requestAdapter = moshi.adapter(TavilyRequestBody::class.java)
    private val responseAdapter = moshi.adapter(TavilyResponse::class.java)

    // ── SearchProvider ─────────────────────────────────────────────────────────

    override suspend fun validate(): Result<Unit> {
        return if (apiKey != null && apiKey.isNotBlank()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("TavilyProvider requires a non-blank apiKey"))
        }
    }

    override suspend fun search(query: String, maxResults: Int): Result<List<SearchProvider.RawHit>> =
        withContext(Dispatchers.IO) {
            try {
                val requestBody = TavilyRequestBody(
                    api_key = apiKey ?: "",
                    query = query,
                    max_results = maxResults,
                )
                val bodyJson = requestAdapter.toJson(requestBody)
                val body = bodyJson.toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$baseUrl/search")
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.code == 401) {
                        return@withContext Result.failure(
                            IllegalStateException("PROVIDER_AUTH_FAILED")
                        )
                    }
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IllegalStateException("Tavily returned HTTP ${response.code}")
                        )
                    }

                    val responseBodyStr = readBodyCapped(response) ?: ""
                    val parsed = try {
                        responseAdapter.fromJson(responseBodyStr)
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                // I13 — re-throw, never swallow. Per agent/CLAUDE.md contract.
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
