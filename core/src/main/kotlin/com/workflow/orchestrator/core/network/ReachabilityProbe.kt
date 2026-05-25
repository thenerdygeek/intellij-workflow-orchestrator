package com.workflow.orchestrator.core.network

/**
 * A single, cheap, no-retry reachability check. Implementations must NOT route
 * through HttpClientFactory (no RetryInterceptor, no reporting interceptor) — the
 * probe is the one thing allowed to talk to the network while everything else is paused.
 */
interface ReachabilityProbe {
    /** true if [targetUrl]'s host answered at all (any HTTP status counts); false on IOException/timeout. */
    suspend fun isReachable(targetUrl: String): Boolean
}
