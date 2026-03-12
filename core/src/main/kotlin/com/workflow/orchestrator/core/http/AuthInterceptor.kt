package com.workflow.orchestrator.core.http

import com.intellij.openapi.diagnostic.Logger
import okhttp3.Interceptor
import okhttp3.Response

enum class AuthScheme { BEARER, BASIC, TOKEN }

class AuthInterceptor(
    private val tokenProvider: () -> String?,
    private val scheme: AuthScheme = AuthScheme.BEARER
) : Interceptor {

    private val log = Logger.getInstance(AuthInterceptor::class.java)

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val token = tokenProvider()

        val request = if (token != null) {
            log.debug("[Core:Auth] Adding ${scheme.name} auth header for request to ${originalRequest.url.host}")
            val headerValue = when (scheme) {
                AuthScheme.BEARER -> "Bearer $token"
                AuthScheme.BASIC -> "Basic " + java.util.Base64.getEncoder()
                    .encodeToString("$token:".toByteArray())
                AuthScheme.TOKEN -> "token $token"
            }
            originalRequest.newBuilder()
                .header("Authorization", headerValue)
                .build()
        } else {
            log.warn("[Core:Auth] No token available for ${scheme.name} auth, proceeding without auth header for ${originalRequest.url.host}")
            originalRequest
        }

        return chain.proceed(request)
    }
}
