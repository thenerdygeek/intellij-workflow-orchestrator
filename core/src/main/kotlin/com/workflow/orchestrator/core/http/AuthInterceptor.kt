package com.workflow.orchestrator.core.http

import okhttp3.Interceptor
import okhttp3.Response

enum class AuthScheme { BEARER, BASIC }

class AuthInterceptor(
    private val tokenProvider: () -> String?,
    private val scheme: AuthScheme = AuthScheme.BEARER
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val token = tokenProvider()

        val request = if (token != null) {
            val headerValue = when (scheme) {
                AuthScheme.BEARER -> "Bearer $token"
                AuthScheme.BASIC -> "Basic " + java.util.Base64.getEncoder()
                    .encodeToString("$token:".toByteArray())
            }
            originalRequest.newBuilder()
                .header("Authorization", headerValue)
                .build()
        } else {
            originalRequest
        }

        return chain.proceed(request)
    }
}
