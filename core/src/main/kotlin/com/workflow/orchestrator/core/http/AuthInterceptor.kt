package com.workflow.orchestrator.core.http

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.auth.Credential
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Legacy scheme selector. Retained for the many call sites that still build an interceptor from a
 * raw token + scheme. New code should pass a [Credential] via [AuthInterceptor.fromCredential] (or
 * register an `AuthProvider`); [Credential] is the extensible replacement (Bearer/Token/Basic/Custom).
 */
enum class AuthScheme { BEARER, TOKEN }

/**
 * Attaches a [Credential] to outbound requests. The single place where auth decoration happens —
 * the credential type owns the actual header logic (see [Credential.applyTo]).
 */
class AuthInterceptor private constructor(
    private val credentialProvider: () -> Credential?,
    private val schemeLabelForLog: String,
) : Interceptor {

    /**
     * Back-compatible constructor: build from a raw token + [scheme]. Preserves the exact prior
     * behavior (`BEARER` → `Bearer <token>`, `TOKEN` → `token <token>`).
     */
    constructor(tokenProvider: () -> String?, scheme: AuthScheme = AuthScheme.BEARER) : this(
        credentialProvider = {
            tokenProvider()?.let { token ->
                when (scheme) {
                    AuthScheme.BEARER -> Credential.Bearer(token)
                    AuthScheme.TOKEN -> Credential.Token(token)
                }
            }
        },
        schemeLabelForLog = scheme.name,
    )

    private val log = Logger.getInstance(AuthInterceptor::class.java)

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val credential = credentialProvider()

        val request = if (credential != null) {
            log.debug("[Core:Auth] Adding $schemeLabelForLog auth header for request to ${originalRequest.url.host}")
            originalRequest.newBuilder().apply { credential.applyTo(this) }.build()
        } else {
            log.warn(
                "[Core:Auth] No credential available for $schemeLabelForLog auth, " +
                    "proceeding without auth header for ${originalRequest.url.host}"
            )
            originalRequest
        }

        return chain.proceed(request)
    }

    companion object {
        /**
         * Credential-native constructor — the extensible path. Used when a credential is resolved
         * via an `AuthProvider` (lets forks supply OAuth/SSO/SAML credentials without a scheme enum).
         */
        fun fromCredential(credentialProvider: () -> Credential?): AuthInterceptor =
            AuthInterceptor(credentialProvider, schemeLabelForLog = "credential")
    }
}
