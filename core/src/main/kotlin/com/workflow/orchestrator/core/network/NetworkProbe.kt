package com.workflow.orchestrator.core.network

import kotlinx.coroutines.flow.StateFlow

/**
 * The connectivity authority's public surface. Injected (defaulting to the APP service)
 * into SmartPoller and AgentLoop so both can be unit-tested with a fake.
 */
interface NetworkProbe {
    val state: StateFlow<NetworkState>

    /** Reactive input: any HTTP client that got a transport (IOException) failure. */
    fun reportFailure(targetUrl: String)

    /** Reactive input: any HTTP request reached the server (even a 4xx/5xx). Flips to ONLINE. */
    fun reportSuccess()

    /**
     * Bounded live probe used by the agent at its retry seam. Probes [targetUrl]
     * (or the last-failed target when null), updates [state], and returns the result.
     */
    suspend fun checkNow(targetUrl: String?): NetworkState

    /** Suspends until ONLINE or [timeoutMs] elapses. Returns true if online, false on timeout. */
    suspend fun awaitOnline(timeoutMs: Long): Boolean
}
