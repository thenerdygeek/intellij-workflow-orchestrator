package com.workflow.orchestrator.core.http

import com.intellij.openapi.diagnostic.Logger
import okhttp3.Interceptor
import okhttp3.Response

class RetryInterceptor(
    private val maxRetries: Int = 3,
    private val baseDelayMs: Long = 1000
) : Interceptor {

    private val log = Logger.getInstance(RetryInterceptor::class.java)
    private val retryableCodes = setOf(429, 500, 502, 503, 504)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        var attempt = 0

        while (response.code in retryableCodes && attempt < maxRetries) {
            attempt++
            val delay = baseDelayMs * (1L shl (attempt - 1)) // exponential: 1x, 2x, 4x
            log.warn("[Core:HTTP] Retry attempt $attempt/$maxRetries for ${request.url} — status ${response.code}, waiting ${delay.coerceAtMost(60_000)}ms")
            response.close()
            Thread.sleep(delay.coerceAtMost(60_000))
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
}
