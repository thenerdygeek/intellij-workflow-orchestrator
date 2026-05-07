package com.workflow.orchestrator.core.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.model.ServiceType
import java.util.concurrent.ConcurrentHashMap

class CredentialStore(
    private val passwordSafe: PasswordSafe? = null
) {

    private val log = Logger.getInstance(CredentialStore::class.java)

    companion object {
        /**
         * In-memory token cache shared across ALL CredentialStore instances.
         * CredentialStore is NOT a singleton — it's instantiated directly in 45+ locations.
         * The cache MUST be static so all instances share the same cached tokens.
         * PasswordSafe.get() can block for 1-2s on first access (especially on Windows).
         *
         * Tokens are cached with a TTL to avoid indefinite retention in memory,
         * reducing risk of stale tokens and cross-project leakage.
         *
         * SEC-15 accepted risk: Tokens are stored as immutable JVM Strings, which cannot
         * be securely zeroed from memory. This is a JVM platform limitation. Mitigation:
         * TTL-based eviction (1 hour) limits the window of exposure. CharArray-based
         * storage is not practical because PasswordSafe and OkHttp both require String.
         */
        private data class CachedToken(val token: String, val expiresAt: Long)

        private val tokenCache = ConcurrentHashMap<ServiceType, CachedToken>()

        /** Cache TTL: tokens are re-read from PasswordSafe after 1 hour. */
        private const val CACHE_TTL_MS = 3_600_000L  // 1 hour

        /** Clear all cached tokens. Call when credentials may have changed externally. */
        fun clearGlobalCache() {
            val size = tokenCache.size
            tokenCache.clear()
            if (size > 0) {
                Logger.getInstance(CredentialStore::class.java)
                    .info("[Core:Credentials] Cleared global token cache ($size entries)")
            }
        }
    }

    private fun safe(): PasswordSafe = passwordSafe ?: PasswordSafe.instance

    fun storeToken(service: ServiceType, token: String) {
        val attributes = credentialAttributes(service)
        val credentials = Credentials(service.name, token)
        safe().set(attributes, credentials)
        tokenCache[service] = CachedToken(token, System.currentTimeMillis() + CACHE_TTL_MS)
        log.info("[Core:Credentials] Stored credential for ${service.name}")
    }

    fun getToken(service: ServiceType): String? {
        tokenCache[service]?.let { cached ->
            if (System.currentTimeMillis() < cached.expiresAt) {
                log.debug("[Core:Credentials] Retrieved credential for ${service.name} (cached)")
                return cached.token
            } else {
                tokenCache.remove(service)
                log.debug("[Core:Credentials] Cached token expired for ${service.name}")
            }
        }
        val attributes = credentialAttributes(service)
        val result = safe().get(attributes)?.getPasswordAsString()
        if (result != null) {
            tokenCache[service] = CachedToken(result, System.currentTimeMillis() + CACHE_TTL_MS)
            log.debug("[Core:Credentials] Retrieved credential for ${service.name} (from PasswordSafe)")
        } else {
            log.debug("[Core:Credentials] No credential found for ${service.name}")
        }
        return result
    }

    fun hasToken(service: ServiceType): Boolean {
        return getToken(service) != null
    }

    private fun credentialAttributes(service: ServiceType): CredentialAttributes {
        return CredentialAttributes(
            generateServiceName("WorkflowOrchestrator", service.name)
        )
    }
}
