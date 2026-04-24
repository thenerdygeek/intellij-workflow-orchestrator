package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.model.ServiceType
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Phase 3 Prong A — HTTP response cache.
 *
 * This commit ships the pass-through skeleton: every GET is counted as a MISS in
 * [HttpCacheMetrics]; nothing is actually stored. The activation commit that follows
 * adds the Caffeine-backed store, URL-pattern TTL policy, and SHA-256 stale-match
 * detection.
 *
 * Installed per-[ServiceType] by [HttpClientFactory.clientFor]. Runs as an
 * *application* interceptor (outermost in the chain) so that cache hits in the
 * activation commit can short-circuit without touching the network layer or the
 * other interceptors — see `docs/architecture/phase3-caching-strategy.md` §4
 * (Prong A).
 *
 * Why pass-through first: landing the interceptor separately from the store gives
 * us a self-comparative baseline. At this commit, hit rate reported by
 * [HttpCacheMetrics] is 0% by construction; after the activation commit the same
 * counter reports the real rate. No separate `phase3-baseline.md` needed.
 */
class CachingInterceptor(service: ServiceType) : Interceptor {

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
        if (request.method == "GET") {
            HttpCacheMetrics.record(serviceTag, HttpCacheMetrics.Outcome.MISS)
        }
        return chain.proceed(request)
    }
}
