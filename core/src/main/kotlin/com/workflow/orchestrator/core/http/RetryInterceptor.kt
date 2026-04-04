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
 */
class RetryInterceptor(
    private val maxRetries: Int = 3,
    private val baseDelayMs: Long = 1000,
    private val maxDelayMs: Long = 10_000
) : Interceptor {

    private val log = Logger.getInstance(RetryInterceptor::class.java)
    private val retryableCodes = setOf(429, 500, 502, 503, 504)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        var attempt = 0

        while (response.code in retryableCodes && attempt < maxRetries) {
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

            log.warn("[Core:HTTP] Retry attempt $attempt/$maxRetries for ${request.url} — status ${response.code}, waiting ${delay}ms" +
                if (headerDelay != null) " (from retry-after header)" else " (exponential backoff)")
            response.close()
            Thread.sleep(delay)
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
