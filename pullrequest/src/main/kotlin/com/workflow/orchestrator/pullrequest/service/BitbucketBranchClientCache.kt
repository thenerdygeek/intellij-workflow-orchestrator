package com.workflow.orchestrator.pullrequest.service

import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.settings.ConnectionSettings

/**
 * Thread-safe cache wrapper for [BitbucketBranchClient] shared by all PR services.
 *
 * Each PR service ([PrDetailService], [PrActionService], [PrListService],
 * [BitbucketServiceImpl]) previously maintained its own identical caching pattern:
 * re-create the client when the configured Bitbucket URL changes, reuse otherwise,
 * return null when the URL is blank.
 *
 * This wrapper centralizes that logic. Construct one instance per service
 * (`private val clientCache = BitbucketBranchClientCache()`) and call [get] whenever
 * a client is needed; do not share a single cache across services, since that would
 * couple their independent lifecycles. The cache self-invalidates when the URL
 * changes, so no explicit invalidation is required in normal operation. Call
 * [invalidate] only when you need to force a fresh client on the next [get].
 *
 * URL resolution matches the pre-existing behavior of all 4 PR services exactly:
 * `ConnectionSettings.getInstance().state.bitbucketUrl.trimEnd('/')`.
 *
 * Observable invariants (unchanged from the inlined pattern this replaces):
 *  - Returns null when Bitbucket URL is not configured.
 *  - Returns a fresh client the first time [get] is called with a configured URL.
 *  - Returns the same cached client on subsequent calls while the URL is stable.
 *  - Returns a newly constructed client whenever the URL changes.
 *  - Does not clear stale fields when the URL becomes blank — the next non-blank
 *    call will rebuild anyway because `url != cachedBaseUrl`.
 */
internal class BitbucketBranchClientCache {

    @Volatile private var cachedClient: BitbucketBranchClient? = null
    @Volatile private var cachedBaseUrl: String? = null

    /**
     * Returns a cached [BitbucketBranchClient], rebuilding it if the configured
     * Bitbucket URL has changed since the last call. Returns null if Bitbucket
     * is not configured (URL blank).
     */
    fun get(): BitbucketBranchClient? {
        val url = ConnectionSettings.getInstance().state.bitbucketUrl.trimEnd('/')
        if (url.isBlank()) return null
        if (url != cachedBaseUrl || cachedClient == null) {
            cachedBaseUrl = url
            cachedClient = BitbucketBranchClient.fromConfiguredSettings()
        }
        return cachedClient
    }

    /**
     * The last successfully cached (trimmed) Bitbucket URL, or null if [get] has
     * never been called with a configured URL. Used by [BitbucketServiceImpl] to
     * compose fallback links when the Bitbucket API response omits self-links.
     */
    val cachedUrl: String?
        get() = cachedBaseUrl

    /**
     * Drop the cached client and URL so the next [get] rebuilds from scratch.
     * Not needed for URL changes (handled automatically); use when forcing a
     * rebuild for other reasons (e.g. credential rotation).
     */
    fun invalidate() {
        cachedClient = null
        cachedBaseUrl = null
    }
}
