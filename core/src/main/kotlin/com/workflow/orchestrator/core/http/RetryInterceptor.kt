package com.workflow.orchestrator.core.http

import okhttp3.Interceptor
import okhttp3.Response

class RetryInterceptor(
    private val maxRetries: Int = 3,
    private val baseDelayMs: Long = 1000
) : Interceptor {

    private val retryableCodes = setOf(429, 500, 502, 503, 504)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        var attempt = 0

        while (response.code in retryableCodes && attempt < maxRetries) {
            response.close()
            attempt++
            val delay = baseDelayMs * (1L shl (attempt - 1)) // exponential: 1x, 2x, 4x
            Thread.sleep(delay.coerceAtMost(60_000))
            response = chain.proceed(request)
        }

        return response
    }
}
