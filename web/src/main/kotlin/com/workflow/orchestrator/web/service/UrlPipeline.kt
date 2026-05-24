package com.workflow.orchestrator.web.service

import com.workflow.orchestrator.core.security.UrlSafetyGuard
import com.workflow.orchestrator.core.web.UrlScreener
import com.workflow.orchestrator.core.web.WebError
import java.net.URI

/**
 * Composes [UrlScreener] (Stage 1), [ShortenerResolver] (Stage 2), and [UrlSafetyGuard]
 * (Stage 3) into a single screening pass.
 *
 * The allowlist decision (Stage 4) and approval dialog (Stage 5) live in [WebFetchEngine].
 *
 * The [resolveShorteners] flag is set to `false` when re-screening a redirect [Location]
 * header during [WebFetchEngine.fetchWithSafeRedirects] — we don't want to follow another
 * shortener hop from inside a redirect chain.
 */
class UrlPipeline(
    private val shortener: ShortenerResolver,
    private val resolver: UrlSafetyGuard.Resolver = UrlSafetyGuard.SystemResolver,
) {

    suspend fun run(
        url: String,
        httpsRequired: Boolean,
        allowIpLiteral: Boolean,
        resolveShorteners: Boolean,
        allowLoopback: Boolean = false,
    ): Result {
        // Stage 1: static URL screening (scheme, credentials, IP literal, IDN, shortener flag)
        val screen = UrlScreener.screen(url, httpsRequired, allowIpLiteral)
        if (screen is UrlScreener.Result.Reject) return Result.Reject(screen.error)
        var pass = screen as UrlScreener.Result.Pass
        var originalUrl: String? = null

        // Stage 2: shortener resolution (optional)
        if (resolveShorteners && UrlScreener.Flag.SHORTENER in pass.flags) {
            originalUrl = pass.finalUrl
            when (val resolved = shortener.resolve(pass.finalUrl)) {
                is ShortenerResolver.Result.Failed  -> return Result.Reject(resolved.error)
                is ShortenerResolver.Result.Resolved -> {
                    // Re-screen the resolved destination (no further shortener resolution)
                    val rescreen = UrlScreener.screen(resolved.finalUrl, httpsRequired, allowIpLiteral)
                    if (rescreen is UrlScreener.Result.Reject) return Result.Reject(rescreen.error)
                    pass = rescreen as UrlScreener.Result.Pass
                }
            }
        }

        // Stage 3: SSRF guard — DNS-resolved host check
        val ssrf = UrlSafetyGuard.isUrlSafe(pass.finalUrl, allowLoopback = allowLoopback, resolver = resolver)
        if (ssrf.isFailure) {
            val ex = ssrf.exceptionOrNull() as? UrlSafetyGuard.UrlBlockedException
                ?: return Result.Reject(WebError.MalformedUrl(pass.finalUrl))
            return Result.Reject(WebError.UrlBlocked(ex.reason, ex.host))
        }

        // Capture the first resolved IP for the approval prompt (spec §3 Stage 5 + §5).
        // The SSRF guard already resolved the host above; we re-use the resolver here to
        // surface the first returned address without a second DNS round-trip.
        // If resolution throws (shouldn't, since UrlSafetyGuard just succeeded), leave null.
        val resolvedIp: String? = try {
            val host = URI(pass.finalUrl).host ?: pass.host
            resolver.resolve(host).firstOrNull()?.hostAddress
        } catch (_: Exception) {
            null
        }

        return Result.Pass(
            originalUrl = originalUrl,
            finalUrl = pass.finalUrl,
            host = pass.host,
            flags = pass.flags,
            resolvedIp = resolvedIp,
        )
    }

    sealed class Result {
        data class Pass(
            val originalUrl: String?,
            val finalUrl: String,
            val host: String,
            val flags: Set<UrlScreener.Flag>,
            /**
             * First IP address the host resolved to during SSRF screening.
             * Null when resolution could not be determined (non-fatal).
             * Populated so the approval dialog can show "Resolves to: X.X.X.X".
             */
            val resolvedIp: String? = null,
        ) : Result()

        data class Reject(val error: WebError) : Result()
    }
}
