package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.model.ServiceType
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.security.MessageDigest

/**
 * Phase 3 Prong A — HTTP response cache.
 *
 * Commit 4c: full four-way outcome classification.
 *
 * - **HIT_FRESH** — cached entry exists and is within its TTL. Return it directly;
 *   no network call.
 * - **HIT_STALE_MATCH** — cached entry exists but past TTL. Refetch; the server's
 *   new body has the same SHA-256 as the cached body. Count as a cache "hit" because
 *   downstream consumers (notably [SmartPoller] in Prong C) can read identical bytes
 *   and short-circuit UI rebuild. Entry is refreshed with the new timestamp.
 * - **HIT_STALE_DIFFER** — cached entry exists but past TTL. Refetch; body differs.
 *   Overwrite cache entry with the new bytes.
 * - **MISS** — no cached entry, or URL is uncacheable, or response was non-200 (we
 *   don't cache errors).
 *
 * Installed per-[ServiceType] by [HttpClientFactory.clientFor] as the outermost
 * application interceptor so a HIT_FRESH short-circuits without touching the
 * network, auth, metrics, or retry layers.
 *
 * Synthetic ETag rationale — see `docs/architecture/phase3-caching-strategy.md` §4
 * (Prong A): Atlassian/SonarSource servers don't send ETag on REST JSON, so we
 * compute our own fingerprint by SHA-256-hashing the response body. The comparison
 * doesn't save network bytes (the body still crosses the wire), but it does save
 * downstream JSON parsing + UI rebuild every time the content is byte-identical.
 */
class CachingInterceptor(private val service: ServiceType) : Interceptor {

    private val serviceTag: String = when (service) {
        ServiceType.JIRA -> "jira"
        ServiceType.BAMBOO -> "bamboo"
        ServiceType.BITBUCKET -> "bitbucket"
        ServiceType.SONARQUBE -> "sonar"
        ServiceType.SOURCEGRAPH -> "sourcegraph"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET") {
            return chain.proceed(request)
        }

        val policy = CachePolicyRegistry.policyFor(service, request.url)
        if (!policy.isCacheable) {
            HttpCacheMetrics.record(serviceTag, HttpCacheMetrics.Outcome.MISS)
            return chain.proceed(request)
        }

        val key = CacheKey.of(request)
        val cached = HttpResponseCache.get(key)

        if (cached != null && cached.isFresh) {
            HttpCacheMetrics.record(serviceTag, HttpCacheMetrics.Outcome.HIT_FRESH)
            return buildCachedResponse(request, cached)
        }

        val networkResponse = chain.proceed(request)
        val originalBody = networkResponse.body
        if (networkResponse.code != 200 || originalBody == null) {
            // Don't cache errors; body is untouched so caller can read it.
            HttpCacheMetrics.record(serviceTag, HttpCacheMetrics.Outcome.MISS)
            return networkResponse
        }

        val contentType = originalBody.contentType()
        val bodyBytes = originalBody.bytes() // consumes the body stream
        val newHash = sha256(bodyBytes)

        val outcome = when {
            cached == null -> HttpCacheMetrics.Outcome.MISS
            cached.sha256.contentEquals(newHash) -> HttpCacheMetrics.Outcome.HIT_STALE_MATCH
            else -> HttpCacheMetrics.Outcome.HIT_STALE_DIFFER
        }
        HttpCacheMetrics.record(serviceTag, outcome)

        HttpResponseCache.put(
            key,
            HttpResponseCache.Entry(
                bodyBytes = bodyBytes,
                sha256 = newHash,
                contentType = contentType?.toString(),
                statusCode = networkResponse.code,
                tag = serviceTag,
                storedAtMillis = System.currentTimeMillis(),
                ttlSeconds = policy.ttlSeconds
            )
        )

        // Rebuild the response with a replayable body so downstream callers can
        // still read it (the original body was consumed above).
        return networkResponse.newBuilder()
            .body(bodyBytes.toResponseBody(contentType))
            .build()
    }

    private fun buildCachedResponse(request: Request, entry: HttpResponseCache.Entry): Response {
        val mediaType = entry.contentType?.toMediaTypeOrNull()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(entry.statusCode)
            .message("OK")
            .body(entry.bodyBytes.toResponseBody(mediaType))
            .addHeader("X-Cache", "HIT")
            .build()
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
