package com.workflow.orchestrator.web.service.search

import com.squareup.moshi.Moshi
import com.workflow.orchestrator.core.web.SearchProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * [SearchProvider] that issues HTTP GET or POST to a user-configured URL template.
 *
 * The URL template must contain `{query}` as a placeholder, e.g.:
 *   `https://corp.example.com/api?q={query}`
 *
 * Field extraction uses a simple JsonPath-lite walker: paths of the form `$.a.b.c`
 * walk the parsed JSON tree. `$` is the root. Intermediate nodes must be JSON objects;
 * the terminal segment for `resultsPath` must be a JSON array; terminal segments for
 * `titlePath`, `urlPath`, and `snippetPath` are read relative to each element of
 * that array.
 *
 * Error mapping:
 * - HTTP 401 → `PROVIDER_AUTH_FAILED`
 * - Any non-200 → failure with status code message
 * - Absent `resultsPath` key or non-array value → `PROVIDER_MALFORMED_RESPONSE`
 * - Malformed JSON → `PROVIDER_MALFORMED_RESPONSE`
 */
class CustomHttpProvider(
    private val urlTemplate: String,
    private val method: String,
    private val headerName: String?,
    private val headerValue: String?,
    private val resultsPath: String,
    private val titlePath: String,
    private val urlPath: String,
    private val snippetPath: String,
    private val client: OkHttpClient,
) : SearchProvider {

    override val id: SearchProvider.ProviderId = SearchProvider.ProviderId.CUSTOM_HTTP

    private val moshi: Moshi = Moshi.Builder().build()

    // ── SearchProvider ─────────────────────────────────────────────────────────

    override suspend fun validate(): Result<Unit> {
        val hasQuery = urlTemplate.contains("{query}")
        val headerPairConsistent = (headerName == null) == (headerValue == null)
        return if (hasQuery && headerPairConsistent) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException(
                    "CustomHttpProvider: urlTemplate must contain '{query}' and headerName/headerValue must be a consistent pair (both null or both non-null)"
                )
            )
        }
    }

    override suspend fun search(query: String, maxResults: Int): Result<List<SearchProvider.RawHit>> =
        withContext(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
                val resolvedUrl = urlTemplate.replace("{query}", encodedQuery)

                val requestBuilder = Request.Builder().url(resolvedUrl)

                when (method.uppercase()) {
                    "POST" -> requestBuilder.post(
                        "{}".toRequestBody("application/json".toMediaType())
                    )
                    else -> requestBuilder.get()
                }

                if (headerName != null && headerValue != null) {
                    requestBuilder.addHeader(headerName, headerValue)
                }

                val request = requestBuilder.build()

                client.newCall(request).execute().use { response ->
                    if (response.code == 401) {
                        return@withContext Result.failure(
                            IllegalStateException("PROVIDER_AUTH_FAILED")
                        )
                    }
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IllegalStateException("CustomHttpProvider received HTTP ${response.code}")
                        )
                    }

                    val body = response.body?.string() ?: ""
                    val root = try {
                        @Suppress("UNCHECKED_CAST")
                        moshi.adapter(Any::class.java).fromJson(body) as? Map<String, Any?>
                    } catch (_: Exception) {
                        null
                    }

                    if (root == null) {
                        return@withContext Result.failure(
                            IllegalStateException("PROVIDER_MALFORMED_RESPONSE")
                        )
                    }

                    // Walk resultsPath to get the array of items
                    val itemsRaw = walkPath(root, resultsPath)
                    if (itemsRaw !is List<*>) {
                        return@withContext Result.failure(
                            IllegalStateException("PROVIDER_MALFORMED_RESPONSE")
                        )
                    }

                    val hits = itemsRaw
                        .take(maxResults)
                        .mapIndexedNotNull { index, item ->
                            @Suppress("UNCHECKED_CAST")
                            val itemMap = item as? Map<String, Any?> ?: return@mapIndexedNotNull null
                            val title = walkPath(itemMap, titlePath)?.toString() ?: ""
                            val url = walkPath(itemMap, urlPath)?.toString() ?: ""
                            val snippet = walkPath(itemMap, snippetPath)?.toString() ?: ""
                            SearchProvider.RawHit(
                                title = title,
                                url = url,
                                snippet = snippet,
                                rank = index,
                            )
                        }

                    Result.success(hits)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ── JsonPath-lite ──────────────────────────────────────────────────────────

    /**
     * Walks a path of the form `$.a.b.c` through a nested [Map] tree.
     *
     * Rules:
     * - `$` is the root node (the map passed as [node])
     * - Each subsequent segment is a map key
     * - Returns `null` when any intermediate key is absent or not a [Map]
     *
     * For leaf access (titlePath/urlPath/snippetPath) the path is interpreted
     * relative to an individual element map, so `$.title` resolves `title` from
     * the element directly.
     */
    private fun walkPath(node: Map<String, Any?>, path: String): Any? {
        val segments = path
            .removePrefix("$")
            .split(".")
            .filter { it.isNotEmpty() }

        var current: Any? = node
        for (segment in segments) {
            current = (current as? Map<*, *>)?.get(segment) ?: return null
        }
        return current
    }
}
