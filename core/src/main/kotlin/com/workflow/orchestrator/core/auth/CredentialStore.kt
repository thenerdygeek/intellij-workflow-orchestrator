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
         * Cache key investigation (F-8): ConnectionSettings is @Service(Service.Level.APP) —
         * service URLs are application-global, shared across all projects in the same IDE
         * instance. There is therefore at most one URL per ServiceType and no cross-project
         * token leakage from the ServiceType-only key. However, when a user changes a URL
         * in Settings → Apply, the old cached entry remains valid for up to 1 hour, causing
         * the new URL's requests to be authenticated with the old token. Fix: key the cache
         * by (ServiceType, serverUrl) so a URL change produces a cache miss immediately.
         * ConnectionsConfigurable.apply() also calls clearGlobalCache() for defence in depth.
         *
         * SEC-15 accepted risk: Tokens are stored as immutable JVM Strings, which cannot
         * be securely zeroed from memory. This is a JVM platform limitation. Mitigation:
         * TTL-based eviction (1 hour) limits the window of exposure. CharArray-based
         * storage is not practical because PasswordSafe and OkHttp both require String.
         */
        private data class CachedToken(val token: String, val expiresAt: Long)

        /**
         * Cache keyed by (ServiceType, serverUrl). The serverUrl component ensures that
         * changing a service URL in settings immediately misses the cache — the next lookup
         * will re-read from PasswordSafe and cache under the new key.
         *
         * URL is the empty string when no URL is available (fallback to legacy behaviour).
         */
        private val tokenCache = ConcurrentHashMap<Pair<ServiceType, String>, CachedToken>()

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

    /**
     * Derives the server URL for [service] from application-level [ConnectionSettings].
     * Used as the second component of the URL-keyed cache key so that changing a service
     * URL in settings immediately invalidates the cached token (F-8 fix).
     * Returns empty string if the settings are unavailable (test context without IDE).
     */
    private fun serverUrlFor(service: ServiceType): String = try {
        val s = com.workflow.orchestrator.core.settings.ConnectionSettings.getInstance().state
        when (service) {
            ServiceType.JIRA -> s.jiraUrl
            ServiceType.BAMBOO -> s.bambooUrl
            ServiceType.BITBUCKET -> s.bitbucketUrl
            ServiceType.SONARQUBE -> s.sonarUrl
            ServiceType.SOURCEGRAPH -> s.sourcegraphUrl
        }
    } catch (_: Exception) {
        ""
    }

    fun storeToken(service: ServiceType, token: String) {
        val attributes = credentialAttributes(service)
        val credentials = Credentials(service.name, token)
        safe().set(attributes, credentials)
        val key = service to serverUrlFor(service)
        tokenCache[key] = CachedToken(token, System.currentTimeMillis() + CACHE_TTL_MS)
        log.info("[Core:Credentials] Stored credential for ${service.name}")
    }

    fun getToken(service: ServiceType): String? {
        val key = service to serverUrlFor(service)
        tokenCache[key]?.let { cached ->
            if (System.currentTimeMillis() < cached.expiresAt) {
                log.debug("[Core:Credentials] Retrieved credential for ${service.name} (cached)")
                return cached.token
            } else {
                tokenCache.remove(key)
                log.debug("[Core:Credentials] Cached token expired for ${service.name}")
            }
        }
        val attributes = credentialAttributes(service)
        val result = safe().get(attributes)?.getPasswordAsString()
        if (result != null) {
            tokenCache[key] = CachedToken(result, System.currentTimeMillis() + CACHE_TTL_MS)
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
