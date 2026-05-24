package com.workflow.orchestrator.web.service

import com.workflow.orchestrator.core.security.UrlSafetyGuard
import com.workflow.orchestrator.core.web.WebError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves shortened URLs by following one HEAD/GET hop.
 *
 * I12 — defense in depth:
 *  - The OkHttp client is constructed in [WebFetchServiceImpl] with
 *    [StripAuthHeadersInterceptor] attached so a stray Authorization / Cookie header
 *    on the shortener call cannot leak credentials to a third-party redirector.
 *  - Every resolve call screens the input URL via [UrlSafetyGuard] BEFORE the HTTP call,
 *    so a shortener-flagged-host that turns out to resolve to loopback / link-local /
 *    private-LAN is rejected before any outbound request.
 */
open class ShortenerResolver(
    private val client: OkHttpClient,
    private val ssrfResolver: UrlSafetyGuard.Resolver = UrlSafetyGuard.SystemResolver,
    /**
     * Production default false — loopback / private-LAN destinations are rejected.
     * Tests that point ShortenerResolver at MockWebServer (which always binds loopback)
     * set true so the SSRF screen lets the test traffic through.
     */
    private val allowLoopback: Boolean = false,
) {

    sealed class Result {
        data class Resolved(val finalUrl: String) : Result()
        data class Failed(val error: WebError) : Result()
    }

    open suspend fun resolve(url: String): Result = withContext(Dispatchers.IO) {
        // I12 — SSRF screen the shortener URL itself before issuing any HTTP call.
        // The shortener-flag gate is currently the only entry point but this is
        // defense-in-depth — future reuses must not bypass the safety guard.
        val ssrf = UrlSafetyGuard.isUrlSafe(url, allowLoopback = allowLoopback, resolver = ssrfResolver)
        if (ssrf.isFailure) {
            val ex = ssrf.exceptionOrNull() as? UrlSafetyGuard.UrlBlockedException
            return@withContext Result.Failed(
                if (ex != null) WebError.UrlBlocked(ex.reason, ex.host)
                else WebError.ShortenerUnresolved(url)
            )
        }

        // Single GET with followRedirects(false): check Location header first,
        // then fall back to reading up to 1024 bytes for a meta-refresh tag.
        val req = Request.Builder().url(url).get().build()
        try {
            client.newCall(req).execute().use { resp ->
                // 3xx with Location header — primary redirect signal
                resp.header("Location")?.let { return@withContext Result.Resolved(it) }

                // 2xx HTML response — probe body for meta-refresh
                if (resp.code in 200..299 && (resp.header("Content-Type") ?: "").contains("html")) {
                    val body = resp.body?.source()?.let {
                        val buf = okio.Buffer()
                        it.read(buf, 1024)
                        buf.readUtf8()
                    } ?: ""
                    val match = Regex(
                        """<meta\s+http-equiv=["']refresh["']\s+content=["']\d+;\s*url=([^"']+)["']""",
                        RegexOption.IGNORE_CASE,
                    ).find(body)
                    if (match != null) return@withContext Result.Resolved(match.groupValues[1].trim())
                }

                Result.Failed(WebError.ShortenerUnresolved(url))
            }
        } catch (_: Exception) {
            Result.Failed(WebError.ShortenerUnresolved(url))
        }
    }
}
