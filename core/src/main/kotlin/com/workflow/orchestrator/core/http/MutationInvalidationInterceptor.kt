package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.model.ServiceType
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Phase 3 Prong A commit 5 — on 2xx POST/PUT/PATCH/DELETE, evict the cached
 * GET responses for the affected resource so the user's own writes are visible
 * immediately instead of "for up to TTL seconds" after the fact.
 *
 * Without this, a user who transitions Jira ticket `PROJ-1` through our plugin
 * would see "To Do" in the Sprint tab until the 60s TTL on the cached
 * `GET /rest/api/2/issue/PROJ-1` expires. The mutation itself returned 204
 * successfully; the read path is simply stale. This interceptor watches writes
 * and walks the cache by URL prefix to evict anything that depended on the
 * resource the write just changed.
 *
 * Extraction rules live in [MutationInvalidationPolicy] so the test surface
 * and the interceptor surface are separate concerns.
 *
 * Installed per-[ServiceType] by [HttpClientFactory.clientFor] after
 * [CachingInterceptor]: GETs continue to short-circuit at the caching layer;
 * mutations flow through this interceptor on their way out and trigger
 * invalidation on their way back.
 */
class MutationInvalidationInterceptor(private val service: ServiceType) : Interceptor {

    private val serviceTag: String = when (service) {
        ServiceType.JIRA -> "jira"
        ServiceType.BAMBOO -> "bamboo"
        ServiceType.BITBUCKET -> "bitbucket"
        ServiceType.SONARQUBE -> "sonar"
        ServiceType.SOURCEGRAPH -> "sourcegraph"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (request.method !in MUTATION_METHODS) return response
        if (response.code !in SUCCESS_CODES) return response

        val prefix = MutationInvalidationPolicy.invalidationPrefixFor(service, request.url)
            ?: return response

        val removed = HttpResponseCache.invalidateByPrefix(prefix)
        repeat(removed) {
            HttpCacheMetrics.recordMutationInvalidation(serviceTag)
        }
        return response
    }

    companion object {
        private val MUTATION_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
        private val SUCCESS_CODES = 200..299
    }
}

/**
 * URL patterns that identify the cache-key prefix to invalidate when a mutation
 * on a matching URL succeeds. Return `null` for mutations that don't affect
 * cached reads — we don't want to nuke the cache on every random write.
 *
 * Each rule returns the prefix (a substring) that matching cache keys contain.
 * `HttpResponseCache.invalidateByPrefix` does a substring match against the
 * URL portion of each cache key.
 */
object MutationInvalidationPolicy {

    private val jiraIssuePattern = Regex("""(/rest/api/2/issue/[A-Z][A-Z0-9]+-\d+)(?:/|$)""")
    private val bitbucketPrPattern =
        Regex("""(/rest/api/1\.0/projects/[^/]+/repos/[^/]+/pull-requests/\d+)""")

    fun invalidationPrefixFor(service: ServiceType, url: HttpUrl): String? {
        val path = url.encodedPath
        return when (service) {
            ServiceType.JIRA -> jiraIssuePattern.find(path)?.groupValues?.get(1)
            ServiceType.BITBUCKET -> bitbucketPrPattern.find(path)?.groupValues?.get(1)
            else -> null
        }
    }
}
