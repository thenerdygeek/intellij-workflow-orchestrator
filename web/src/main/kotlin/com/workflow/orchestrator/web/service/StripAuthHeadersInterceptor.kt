package com.workflow.orchestrator.web.service

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that strips authentication and session headers from every
 * outbound request. Guarantees no credential leak even when code elsewhere
 * inadvertently adds auth headers, or if a future change introduces them.
 *
 * This interceptor is added to the fetch-only OkHttpClient in [WebFetchEngine].
 * The search-provider clients (BraveProvider, etc.) set X-Subscription-Token
 * AFTER routing through a separate client that does NOT use this interceptor —
 * see SearchProviderRegistry for the split.
 */
class StripAuthHeadersInterceptor : Interceptor {

    private val STRIPPED = setOf(
        "authorization",
        "cookie",
        "x-auth-token",
        "x-api-key",
        "x-subscription-token",
        "proxy-authorization",
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()
        STRIPPED.forEach { header -> builder.removeHeader(header) }
        return chain.proceed(builder.build())
    }
}
