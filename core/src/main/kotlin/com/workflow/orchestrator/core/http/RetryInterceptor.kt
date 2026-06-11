package com.workflow.orchestrator.core.http

import com.intellij.openapi.diagnostic.Logger
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that retries on transient HTTP errors (429, 5xx)
 * with rate-limit header awareness.
 *
 * Rate-limit header parsing ported from Cline's retry.ts:
 * - Checks `retry-after`, `x-ratelimit-reset`, and `ratelimit-reset` headers
 * - Handles both delta-seconds and Unix timestamp formats
 * - Falls back to exponential backoff when no headers are present
 *
 * @see <a href="https://github.com/cline/cline/blob/main/src/core/api/retry.ts">Cline retry.ts</a>
 *
 * P2-10 (2026-06-10 perf audit): [maxTotalDelayMs] caps the CUMULATIVE sleep across all
 * retry attempts of one call. Without it, sustained 429s with `retry-after: 10` could hold
 * an OkHttp dispatcher thread for ~30s (3 x 10s per-attempt cap). Per-attempt caps are
 * unchanged; once the cumulative budget is spent, the last sleep is truncated to the
 * remainder and no further retry fires (worst case now ~= maxTotalDelayMs).
 */
class RetryInterceptor(
    private val maxRetries: Int = 3,
    private val baseDelayMs: Long = 1000,
    private val maxDelayMs: Long = 10_000,
    private val maxTotalDelayMs: Long = 15_000
) : Interceptor {

    private val log = Logger.getInstance(RetryInterceptor::class.java)
    private val retryableCodes = setOf(429, 500, 502, 503, 504)

    /**
     * Only idempotent methods are safe to retry. Retrying POST/PUT/DELETE/PATCH
     * on a 5xx can duplicate side effects (Bamboo builds, PR merges, Jira
     * comments) because the server may have processed the request before the
     * transient failure surfaced. HTTP method names are uppercase per RFC 9110.
     */
    private val safeMethods = setOf("GET", "HEAD", "OPTIONS")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // Idempotency guard: non-idempotent methods get a single attempt, no retry.
        if (request.method !in safeMethods) {
            return response
        }

        return retryIdempotent(chain, request, response)
    }

    private fun retryIdempotent(
        chain: Interceptor.Chain,
        request: okhttp3.Request,
        firstResponse: Response
    ): Response {
        var response = firstResponse
        var attempt = 0
        var totalSleptMs = 0L

        while (response.code in retryableCodes && attempt < maxRetries) {
            // Cumulative budget guard (P2-10): never hold the OkHttp thread longer
            // than maxTotalDelayMs in total across all attempts of this call.
            val remainingBudgetMs = maxTotalDelayMs - totalSleptMs
            if (remainingBudgetMs <= 0) {
                log.warn(
                    "[Core:HTTP] Cumulative retry-delay budget (${maxTotalDelayMs}ms) exhausted for " +
                        "${request.url} after $attempt attempt(s) — returning status ${response.code}"
                )
                break
            }
            attempt++

            // Parse retry-after headers (ported from Cline's retry.ts)
            val headerDelay = parseRetryAfterHeaders(response)
            val delay = if (headerDelay != null && headerDelay > 0) {
                // Use server-provided delay, capped at maxDelayMs
                headerDelay.coerceAtMost(maxDelayMs)
            } else {
                // Exponential backoff: 1x, 2x, 4x (Cline: baseDelay * 2^attempt)
                val exponential = baseDelayMs * (1L shl (attempt - 1))
                exponential.coerceAtMost(maxDelayMs)
            }
            val boundedDelay = delay.coerceAtMost(remainingBudgetMs)

            log.warn(
                "[Core:HTTP] Retry attempt $attempt/$maxRetries for ${request.url} — " +
                    "status ${response.code}, waiting ${boundedDelay}ms" +
                    if (headerDelay != null) " (from retry-after header)" else " (exponential backoff)"
            )
            response.close()
            Thread.sleep(boundedDelay)
            totalSleptMs += boundedDelay
            response = chain.proceed(request)
        }

        if (attempt > 0) {
            if (response.code in retryableCodes) {
                log.error("[Core:HTTP] All $maxRetries retries exhausted for ${request.url} — final status ${response.code}")
            } else {
                log.info("[Core:HTTP] Request to ${request.url} succeeded after $attempt retry attempt(s) — status ${response.code}")
            }
        }

        return response
    }

    /**
     * Parse rate-limit headers from a 429 response.
     *
     * Faithful port of Cline's retry.ts header parsing logic:
     * ```js
     * const retryAfter = error.headers?.["retry-after"]
     *     || error.headers?.["x-ratelimit-reset"]
     *     || error.headers?.["ratelimit-reset"]
     * ```
     *
     * Handles both formats:
     * - Delta-seconds: `retry-after: 30` -> wait 30 seconds
     * - Unix timestamp: `retry-after: 1703980800` -> wait until that time
     *
     * @return delay in milliseconds, or null if no retry-after info found
     */
    internal fun parseRetryAfterHeaders(response: Response): Long? {
        // Check headers in Cline's priority order
        val headerValue = response.header("retry-after")
            ?: response.header("x-ratelimit-reset")
            ?: response.header("ratelimit-reset")
            ?: return null

        val retryValue = headerValue.trim().toLongOrNull() ?: return null

        // Ported from Cline: distinguish Unix timestamp vs delta-seconds
        // If the value is larger than current time in seconds, it's a Unix timestamp
        val nowSeconds = System.currentTimeMillis() / 1000
        return if (retryValue > nowSeconds) {
            // Unix timestamp — calculate delay from now
            (retryValue * 1000 - System.currentTimeMillis()).coerceAtLeast(0)
        } else {
            // Delta seconds — convert to milliseconds
            retryValue * 1000
        }
    }
}
