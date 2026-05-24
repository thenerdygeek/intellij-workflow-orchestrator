package com.workflow.orchestrator.pullrequest.service

import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.settings.ConnectionSettings
import java.util.concurrent.atomic.AtomicReference

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
 * changes, so no explicit invalidation is required in normal operation.
 *
 * URL resolution matches the pre-existing behavior of all 4 PR services exactly:
 * `ConnectionSettings.getInstance().state.bitbucketUrl.trimEnd('/')`.
 *
 * D3 (audit finding pullrequest:F-4) — atomic state ref:
 * Previously two separate @Volatile fields (`cachedClient` and `cachedBaseUrl`) were
 * used together in a non-atomic check-then-act: a thread could read one field, be
 * preempted, then see the other field updated by a concurrent call, producing a
 * (url₁, client₂) pair where client₂ was built for url₂ ≠ url₁. This corrupts
 * PR browser links (the wrong base URL is composed into fallback links).
 *
 * Fix: combine both into a single [AtomicReference<Pair<String, BitbucketBranchClient>?>].
 * The compare-and-set loop ensures exactly one client is built per distinct URL even
 * under concurrent access.
 *
 * Observable invariants (unchanged from the inlined pattern this replaces):
 *  - Returns null when Bitbucket URL is not configured.
 *  - Returns a fresh client the first time [get] is called with a configured URL.
 *  - Returns the same cached client on subsequent calls while the URL is stable.
 *  - Returns a newly constructed client whenever the URL changes.
 *  - Every returned (url, client) pair is internally consistent — the client was
 *    built for that exact url.
 */
internal class BitbucketBranchClientCache {

    /**
     * Atomically holds (trimmedBaseUrl, client) together. null means no entry cached yet.
     *
     * Invariant: if non-null, `first` is the trimmed URL for which `second` was built.
     */
    private val cached = AtomicReference<Pair<String, BitbucketBranchClient>?>(null)

    /**
     * Returns a cached [BitbucketBranchClient], rebuilding it if the configured
     * Bitbucket URL has changed since the last call. Returns null if Bitbucket
     * is not configured (URL blank).
     *
     * Thread-safe: uses a compare-and-set loop so exactly one client is built
     * per distinct URL even when multiple coroutines call [get] concurrently.
     */
    fun get(): BitbucketBranchClient? {
        val url = ConnectionSettings.getInstance().state.bitbucketUrl.trimEnd('/')
        if (url.isBlank()) return null

        // Fast path — URL unchanged, return cached client.
        cached.get()?.let { (cachedUrl, client) ->
            if (cachedUrl == url) return client
        }

        // Slow path — URL changed or cache is empty. Build a new client and CAS it in.
        // Another thread may win the race and set a consistent (url, client) pair first;
        // in that case we discard our newly constructed client and use theirs.
        val newClient = BitbucketBranchClient.fromConfiguredSettings() ?: return null
        val newEntry: Pair<String, BitbucketBranchClient> = Pair(url, newClient)
        while (true) {
            val current = cached.get()
            // If another thread already stored an entry for this URL, use it.
            if (current != null && current.first == url) return current.second
            // Otherwise try to install our entry.
            if (cached.compareAndSet(current, newEntry)) return newClient
            // CAS failed — loop and re-check.
        }
    }

    /**
     * The last successfully cached (trimmed) Bitbucket URL, or null if [get] has
     * never been called with a configured URL. Used by [BitbucketServiceImpl] to
     * compose fallback links when the Bitbucket API response omits self-links.
     *
     * Reading the snapshot once is safe — the invariant guarantees the stored url
     * matches the stored client.
     */
    val cachedUrl: String?
        get() = cached.get()?.first
}
