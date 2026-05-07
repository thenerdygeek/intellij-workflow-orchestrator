package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.model.ServiceType
import okhttp3.HttpUrl

/**
 * Per-URL caching policy: how long a GET response may be trusted before
 * revalidation, or whether the URL is explicitly uncacheable.
 *
 * Matched by [CachePolicyRegistry.policyFor] ahead of every HTTP request
 * inside the Prong A [CachingInterceptor] (wired in the activation commit).
 */
data class CachePolicy(
    /**
     * TTL in seconds.
     *
     * - `> 0` : serve from cache as fresh within this window; synthetic-ETag
     *   revalidation after expiry.
     * - `0`   : always revalidate via synthetic-ETag; hash-match still avoids
     *   downstream parsing + UI rebuild (Prong C benefits here).
     * - `< 0` : never cache. Sentinel: [NEVER].
     */
    val ttlSeconds: Long
) {
    val isCacheable: Boolean get() = ttlSeconds >= 0

    companion object {
        /** The URL is explicitly not cacheable — sensitive endpoint, streaming, or mutation. */
        val NEVER: CachePolicy = CachePolicy(-1L)
    }
}

/**
 * Registry mapping URL patterns to [CachePolicy], scoped per [ServiceType].
 *
 * Matching semantics:
 * - Sensitive-path regex always wins and returns [CachePolicy.NEVER] regardless of service.
 * - Service rules matched in order; first match wins.
 * - No match → [CachePolicy.NEVER] (conservative default: prefer no-cache over wrong-cache).
 *
 * This object is a pure data layer; the [CachingInterceptor] consumes these
 * decisions in the Prong A activation commit. Landing the registry first keeps
 * the activation commit focused on the wiring + Caffeine store without also
 * introducing policy at the same time.
 *
 * TTL values are starting points from `docs/architecture/phase3-caching-strategy.md` §4;
 * tunable after measurement against `HttpCacheMetrics` hit-rate telemetry.
 */
object CachePolicyRegistry {

    private data class Rule(val pattern: Regex, val policy: CachePolicy)

    /**
     * Paths treated as sensitive across every service. Matches the existing
     * [HttpClientFactory.SensitiveEndpointNoCacheInterceptor] allowlist plus a
     * defensive `/login` match — the two layers are belt-and-braces because one
     * is a network interceptor (cache-control header mutation) and the other is
     * an application-level cache-key rejection.
     */
    private val sensitivePaths: List<Regex> = listOf(
        Regex("""/rest/api/2/myself"""),
        Regex("""/rest/auth"""),
        Regex("""/_api/graphql"""),
        Regex("""/api/user\b"""),
        Regex("""/login""")
    )

    private val rulesByService: Map<ServiceType, List<Rule>> = mapOf(
        ServiceType.JIRA to listOf(
            Rule(Regex("""/rest/agile/1\.0/board(/\d+)?$"""), CachePolicy(300)),
            Rule(Regex("""/rest/agile/1\.0/sprint/\d+$"""), CachePolicy(120)),
            Rule(Regex("""/rest/api/2/user/assignable/search"""), CachePolicy(3600)),
            Rule(Regex("""/rest/api/2/search"""), CachePolicy(10)),
            Rule(Regex("""/rest/api/2/issue/[A-Z][A-Z0-9]+-\d+(?:$|/transitions$)"""), CachePolicy(60)),
            Rule(Regex("""/rest/api/2/project/[^/]+/versions"""), CachePolicy(300)),
            Rule(Regex("""/rest/api/2/project/[^/]+/components"""), CachePolicy(300))
        ),
        ServiceType.BAMBOO to listOf(
            Rule(Regex("""/rest/api/latest/result/[A-Z0-9-]+/\d+$"""), CachePolicy(86400)),
            Rule(Regex("""/rest/api/latest/result/[A-Z0-9-]+$"""), CachePolicy(0)),
            Rule(Regex("""/rest/api/latest/plan"""), CachePolicy(300))
        ),
        ServiceType.BITBUCKET to listOf(
            Rule(Regex("""/pull-requests/\d+/activities"""), CachePolicy(60)),
            Rule(Regex("""/pull-requests/\d+/comments"""), CachePolicy(30)),
            Rule(Regex("""/pull-requests/\d+$"""), CachePolicy(30)),
            Rule(Regex("""/pull-requests\??"""), CachePolicy(30))
        ),
        ServiceType.SONARQUBE to listOf(
            Rule(Regex("""/api/ce/task"""), CachePolicy.NEVER),
            Rule(Regex("""/api/ce/activity"""), CachePolicy.NEVER),
            Rule(Regex("""/api/qualitygates/project_status"""), CachePolicy(300)),
            Rule(Regex("""/api/measures/component"""), CachePolicy(120)),
            Rule(Regex("""/api/issues/search"""), CachePolicy(60)),
            Rule(Regex("""/api/project_branches/list"""), CachePolicy(300)),
            Rule(Regex("""/api/components/search"""), CachePolicy(120))
        ),
        ServiceType.SOURCEGRAPH to listOf(
            Rule(Regex("""/\.api/completions/stream"""), CachePolicy.NEVER),
            Rule(Regex("""/\.api/llm/chat"""), CachePolicy.NEVER),
            Rule(Regex("""/\.api/client-config"""), CachePolicy(600)),
            Rule(Regex("""/\.api/llm/models"""), CachePolicy(600))
        )
    )

    fun policyFor(service: ServiceType, url: HttpUrl): CachePolicy {
        val path = url.encodedPath
        if (sensitivePaths.any { it.containsMatchIn(path) }) return CachePolicy.NEVER
        val rules = rulesByService[service] ?: return CachePolicy.NEVER
        return rules.firstOrNull { it.pattern.containsMatchIn(path) }?.policy ?: CachePolicy.NEVER
    }
}
