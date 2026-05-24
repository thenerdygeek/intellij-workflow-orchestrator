package com.workflow.orchestrator.web.service

import com.workflow.orchestrator.core.security.UrlSafetyGuard
import okhttp3.Dns
import java.net.InetAddress

/**
 * DNS resolver that caches addresses per-host. Prevents DNS rebinding TOCTOU between
 * the SSRF safety check and OkHttp's subsequent resolution: the first lookup screens
 * via [UrlSafetyGuard.Resolver]; subsequent lookups for the same host (across redirects
 * within the same call) return the cached address rather than re-resolving.
 *
 * Construct one [PinnedDns] per fetch call — never share across calls (would allow
 * stale entries to persist beyond the TTL window and re-introduce the rebinding window
 * the class exists to close).
 *
 * Thread-safety: synchronized writes; reads can race on the underlying map but the only
 * impact of a race is an extra resolver call. Acceptable since each instance is per-call.
 */
class PinnedDns(
    private val resolver: UrlSafetyGuard.Resolver = UrlSafetyGuard.SystemResolver,
) : Dns {

    private val cache = HashMap<String, List<InetAddress>>()

    @Synchronized
    override fun lookup(hostname: String): List<InetAddress> {
        cache[hostname]?.let { return it }
        val resolved = resolver.resolve(hostname).toList()
        cache[hostname] = resolved
        return resolved
    }
}
