package com.workflow.orchestrator.web.service

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that strips authentication and session headers from every
 * outbound request. Guarantees no credential leak even when code elsewhere
 * inadvertently adds auth headers, or if a future change introduces them.
 *
 * This interceptor is added to the fetch-only OkHttpClient in [WebFetchServiceImpl].
 * The search-provider client in [WebSearchServiceImpl] does NOT carry this interceptor
 * because providers like CustomHttpProvider authenticate via custom headers
 * that must reach the upstream API. Plan rev R5: auth-stripping is fetch-only.
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
