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
    // Delegates to the WorkflowConfig seam so the ServiceType→URL mapping lives in one place
    // (DefaultWorkflowConfig) and forks can override config without editing :core. The default
    // impl reads the same ConnectionSettings, so behavior is unchanged. try/catch stays defensive
    // (PasswordSafe cache-key resolution must never throw).
    private fun serverUrlFor(service: ServiceType): String = try {
        com.workflow.orchestrator.core.config.WorkflowConfig.resolve().baseUrl(service)
    } catch (_: Exception) {
        ""
    }

    /**
     * Persists [token] for [service] and verifies the write actually landed.
     *
     * Returns `true` when the value was confirmed readable from PasswordSafe afterwards, `false`
     * when the backing store silently rejected the write. `PasswordSafe.set()` can no-op WITHOUT
     * throwing (e.g. a locked/denied KeePass database or native keychain), which previously made a
     * Settings → Apply token change look successful while persisting nothing — the user only found
     * out later via a 401. Callers should surface a `false` result to the user.
     */
    fun storeToken(service: ServiceType, token: String): Boolean {
        val attributes = credentialAttributes(service)
        val credentials = Credentials(service.name, token)
        safe().set(attributes, credentials)
        val key = service to serverUrlFor(service)
        tokenCache[key] = CachedToken(token, System.currentTimeMillis() + CACHE_TTL_MS)

        // Read straight back from the store (bypassing the cache) to confirm the write persisted.
        val readBack = try {
            safe().get(attributes)?.getPasswordAsString()
        } catch (e: Exception) {
            log.warn("[Core:Credentials] Read-back after store threw for ${service.name}", e)
            null
        }
        if (readBack != token) {
            tokenCache.remove(key)
            // warn, not error: this is a user-environment condition (password store rejected the
            // write) that the caller handles by notifying the user — not an internal assertion.
            log.warn(
                "[Core:Credentials] Write verification FAILED for ${service.name} — " +
                    "password storage did not persist the token"
            )
            return false
        }
        log.info("[Core:Credentials] Stored and verified credential for ${service.name}")
        return true
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
        // The username MUST match the one carried by Credentials(service.name, token) below.
        // With a null username here, PasswordSafe's KeePass backend could not reliably target the
        // existing entry (whose username is service.name) on set(), so a changed token persisted
        // ambiguously and a later read returned the stale value — i.e. the Settings → Apply token
        // change appeared to do nothing. Including service.name makes set() and get() address the
        // same unique entry. (Existing entries already carry this username, so no migration.)
        return CredentialAttributes(
            generateServiceName("WorkflowOrchestrator", service.name),
            service.name
        )
    }
}
