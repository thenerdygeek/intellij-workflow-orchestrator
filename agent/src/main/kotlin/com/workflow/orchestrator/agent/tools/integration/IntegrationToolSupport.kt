package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.http.AuthInterceptor
import com.workflow.orchestrator.core.http.AuthScheme
import com.workflow.orchestrator.core.http.RetryInterceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared utilities for integration tools to avoid duplicated HTTP client
 * construction and credential resolution logic.
 */
object IntegrationToolSupport {

    /**
     * Build an OkHttpClient with the given token and auth scheme.
     * Uses standard timeouts and retry interceptor.
     */
    fun buildClient(token: String, authScheme: AuthScheme = AuthScheme.BEARER): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor({ token }, authScheme))
            .addInterceptor(RetryInterceptor(maxRetries = 2))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Resolve credentials from providers, returning (baseUrl, token, client) or null if invalid.
     * Returns null and the caller should return the appropriate error via [credentialError].
     */
    fun resolveCredentials(
        urlProvider: () -> String?,
        tokenProvider: () -> String?,
        serviceName: String,
        authScheme: AuthScheme = AuthScheme.BEARER
    ): Triple<String, String, OkHttpClient>? {
        val baseUrl = urlProvider()?.trimEnd('/') ?: ""
        if (baseUrl.isBlank()) return null

        val token = tokenProvider()
        if (token.isNullOrBlank()) return null

        return Triple(baseUrl, token, buildClient(token, authScheme))
    }

    /**
     * Produce a standardized credential error result for a service.
     */
    fun credentialError(serviceName: String, field: String): ToolResult {
        return ToolResult(
            content = "Error: $serviceName $field not configured",
            summary = "Error: $serviceName $field not configured",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }
}
