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
 * Activated in commit 4b: consults [CachePolicyRegistry] and [HttpResponseCache]
 * to serve GET responses from memory when fresh, store them on miss. Does NOT
 * yet implement synthetic-ETag stale-match — that arrives in commit 4c. Stale
 * entries currently behave like misses.
 *
 * Installed per-[ServiceType] by [HttpClientFactory.clientFor] as the outermost
 * application interceptor so a fresh-hit short-circuits without touching the
 * network, auth, metrics, or retry layers.
 */
class CachingInterceptor(private val service: ServiceType) : Interceptor {

    private val serviceTag: String = when (service) {
        ServiceType.JIRA -> "jira"
        ServiceType.BAMBOO -> "bamboo"
        ServiceType.BITBUCKET -> "bitbucket"
        ServiceType.SONARQUBE -> "sonar"
        ServiceType.SOURCEGRAPH -> "sourcegraph"
        ServiceType.NEXUS -> "nexus"
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

        // TODO(commit 4c): on stale hit, refetch and synthetic-ETag match against
        // cached.sha256. For now, stale behaves as miss (refetch + overwrite).
        HttpCacheMetrics.record(serviceTag, HttpCacheMetrics.Outcome.MISS)
        val networkResponse = chain.proceed(request)
        return storeAndReturn(networkResponse, key, policy)
    }

    private fun storeAndReturn(response: Response, key: CacheKey, policy: CachePolicy): Response {
        if (response.code != 200) return response
        val originalBody = response.body ?: return response
        val contentType = originalBody.contentType()
        val bodyBytes = originalBody.bytes()

        val sha = MessageDigest.getInstance("SHA-256").digest(bodyBytes)
        val entry = HttpResponseCache.Entry(
            bodyBytes = bodyBytes,
            sha256 = sha,
            contentType = contentType?.toString(),
            statusCode = response.code,
            tag = serviceTag,
            storedAtMillis = System.currentTimeMillis(),
            ttlSeconds = policy.ttlSeconds
        )
        HttpResponseCache.put(key, entry)

        // The original body is now spent; rebuild the response with a fresh
        // ReadableByteArray so downstream callers can still read the body.
        return response.newBuilder()
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
}
