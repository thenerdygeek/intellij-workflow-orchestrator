package com.workflow.orchestrator.web.service.search

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.workflow.orchestrator.core.web.SearchProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * [SearchProvider] backed by the Brave Search API.
 *
 * Endpoint: `$baseUrl?q=<query>&count=<maxResults>`
 * Auth header: `X-Subscription-Token: <apiKey>`
 *
 * Response shape: `{web: {results: [{title, url, description, ...}]}}`
 *
 * Error mapping:
 * - HTTP 401 → `PROVIDER_AUTH_FAILED`
 * - Any non-200 → failure with status code message
 * - Malformed / unexpected JSON → `PROVIDER_MALFORMED_RESPONSE`
 */
class BraveProvider(
    private val baseUrl: String = "https://api.search.brave.com/res/v1/web/search",
    private val apiKey: String?,
    private val client: OkHttpClient,
) : SearchProvider {

    override val id: SearchProvider.ProviderId = SearchProvider.ProviderId.BRAVE

    // ── Moshi ──────────────────────────────────────────────────────────────────

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    @JsonClass(generateAdapter = true)
    internal data class BraveWebResult(
        val title: String = "",
        val url: String = "",
        val description: String = "",
    )

    @JsonClass(generateAdapter = true)
    internal data class BraveWebSection(
        val results: List<BraveWebResult> = emptyList(),
    )

    @JsonClass(generateAdapter = true)
    internal data class BraveResponse(
        val web: BraveWebSection? = null,
    )

    private val responseAdapter = moshi.adapter(BraveResponse::class.java)

    // ── SearchProvider ─────────────────────────────────────────────────────────

    override suspend fun validate(): Result<Unit> {
        return if (apiKey != null && apiKey.isNotBlank()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("BraveProvider requires a non-blank apiKey"))
        }
    }

    override suspend fun search(query: String, maxResults: Int): Result<List<SearchProvider.RawHit>> =
        withContext(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
                val url = "$baseUrl?q=$encodedQuery&count=$maxResults"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("X-Subscription-Token", apiKey ?: "")
                    .addHeader("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.code == 401) {
                        return@withContext Result.failure(
                            IllegalStateException("PROVIDER_AUTH_FAILED")
                        )
                    }
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IllegalStateException("Brave Search returned HTTP ${response.code}")
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

                    val results = parsed.web?.results ?: emptyList()
                    val hits = results
                        .take(maxResults)
                        .mapIndexed { index, item ->
                            SearchProvider.RawHit(
                                title = item.title,
                                url = item.url,
                                snippet = item.description,
                                rank = index,
                            )
                        }
                    Result.success(hits)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
