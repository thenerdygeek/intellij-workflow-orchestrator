package com.workflow.orchestrator.core.http

import com.intellij.openapi.application.PathManager
import com.workflow.orchestrator.core.auth.AuthProvider
import com.workflow.orchestrator.core.auth.DefaultAuthProvider
import com.workflow.orchestrator.core.model.ServiceType
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Connect/read timeout pair (in seconds) shared between all API clients in the plugin.
 * Centralised here so that callers don't have to read both
 * `settings.state.httpConnectTimeoutSeconds` and `httpReadTimeoutSeconds` and convert
 * them to `Long` at every construction site.
 *
 * Use [HttpClientFactory.timeoutsFromSettings] to read the configured pair from
 * [com.workflow.orchestrator.core.settings.PluginSettings].
 */
data class HttpTimeouts(val connectSeconds: Long, val readSeconds: Long) {
    companion object {
        val DEFAULT = HttpTimeouts(10, 30)
    }
}

class HttpClientFactory(
    private val tokenProvider: (ServiceType) -> String?,
    private val connectTimeoutSeconds: Long = 10,
    private val readTimeoutSeconds: Long = 30
) {
    private val clients = ConcurrentHashMap<ServiceType, OkHttpClient>()

    private val baseClient: OkHttpClient by lazy {
        sharedPool.newBuilder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            // Disable redirect-following so the Authorization header is never forwarded
            // to a redirect target that may be on a different host/domain.
            // Callers that need redirect support must open a new request themselves.
            .followRedirects(false)
            .followSslRedirects(false)
            // IdeTrust: validate server certs against the OS / IDE truststore (corporate
            // SSL-inspection CAs live there) in addition to the JBR cacerts, matching the
            // rest of the IDE's networking. ConfirmingTrustManager is a superset of the JVM
            // default trust, so hosts that validated before still validate. No-op when the
            // platform is unavailable (tests).
            .let { IdeTrust.applyTo(it) }
            .addInterceptor(NetworkStateReportingInterceptor())
            .addInterceptor(RetryInterceptor())
            .addNetworkInterceptor(SensitiveEndpointNoCacheInterceptor())
            .build()
    }

    fun clientFor(service: ServiceType): OkHttpClient {
        return clients.getOrPut(service) {
            baseClient.newBuilder()
                .addInterceptor(CachingInterceptor(service))
                .addInterceptor(MutationInvalidationInterceptor(service))
                .addInterceptor(HttpMetricsInterceptor())
                .addInterceptor(authInterceptorFor(service))
                .build()
        }
    }

    /**
     * Resolve the auth interceptor for [service]. The base ships [DefaultAuthProvider], so the
     * default path is byte-identical to before: this factory's injected [tokenProvider] supplies
     * the token and the per-service scheme (Sourcegraph = `token`, else `Bearer`) is applied. If a
     * company fork registers its own `AuthProvider` (SSO/SAML/OAuth2) via the `authProvider` EP,
     * that provider wins and supplies the [com.workflow.orchestrator.core.auth.Credential] instead.
     */
    private fun authInterceptorFor(service: ServiceType): AuthInterceptor {
        val provider = AuthProvider.resolve(service)
        return if (provider is DefaultAuthProvider) {
            val scheme = when (service) {
                ServiceType.SOURCEGRAPH -> AuthScheme.TOKEN
                else -> AuthScheme.BEARER
            }
            AuthInterceptor({ tokenProvider(service) }, scheme)
        } else {
            AuthInterceptor.fromCredential { provider.credentialFor(service) }
        }
    }

    /**
     * Prevents the HTTP cache from storing responses for authentication
     * and user-info endpoints that may contain sensitive data.
     */
    class SensitiveEndpointNoCacheInterceptor : Interceptor {
        private val noCachePaths = setOf(
            "/rest/api/2/myself",
            "/rest/auth",
            "/_api/graphql",
            "/api/user"
        )

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val path = request.url.encodedPath
            val response = chain.proceed(request)
            return if (noCachePaths.any { path.contains(it, ignoreCase = true) }) {
                response.newBuilder()
                    .header("Cache-Control", "no-store")
                    .removeHeader("ETag")
                    .build()
            } else {
                response
            }
        }
    }

    companion object {
        /**
         * Reads the connect/read HTTP timeouts from the project's [com.workflow.orchestrator.core.settings.PluginSettings].
         * Centralises the `settings.state.httpConnectTimeoutSeconds.toLong() / .httpReadTimeoutSeconds.toLong()`
         * pattern that was previously duplicated by every API client construction site.
         */
        fun timeoutsFromSettings(project: com.intellij.openapi.project.Project): HttpTimeouts {
            val state = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project).state
            return HttpTimeouts(
                connectSeconds = state.httpConnectTimeoutSeconds.toLong(),
                readSeconds = state.httpReadTimeoutSeconds.toLong()
            )
        }

        /**
         * Shared connection pool across all OkHttpClient instances in the plugin.
         *
         * 5 idle connections × 3 min keep-alive matches the ~5 upstream services
         * (Jira, Bamboo, Bitbucket, SonarQube, Sourcegraph). The previous value of
         * 15/5min was 3× over-allocated: it held unnecessary file descriptors and
         * risked connection resets on enterprise firewalls whose idle-RST timers are
         * typically ≤ 4 min. Aligned with core/CLAUDE.md ("ConnectionPool(5, 3min)").
         *
         * Audit finding core:F-15.
         */
        private val sharedConnectionPool = ConnectionPool(5, 3, TimeUnit.MINUTES)

        /** Shared HTTP response cache (10 MB) for ETag/304 support. */
        private val sharedCacheDelegate = lazy {
            val cacheDir = File(PathManager.getSystemPath(), "workflow-orchestrator/http-cache")
            Cache(cacheDir, 10L * 1024 * 1024)
        }
        private val sharedCache: Cache by sharedCacheDelegate

        /**
         * Base client with shared connection pool and cache.
         * Use [OkHttpClient.newBuilder] to customize per-service.
         */
        private val sharedPoolDelegate = lazy {
            OkHttpClient.Builder()
                .connectionPool(sharedConnectionPool)
                .cache(sharedCache)
                .build()
        }
        val sharedPool: OkHttpClient by sharedPoolDelegate

        /**
         * Releases the shared HTTP resources: evicts idle pooled connections, shuts down
         * the OkHttp dispatcher's executor (shared by every per-service client derived via
         * `newBuilder()`), and closes the response cache. Called from
         * [HttpResourceCleanupListener] on plugin unload so idle connections + dispatcher
         * threads don't survive in the classloader across a dev hot-reload (audit core:F-7).
         * Idempotent and only touches resources that were actually initialised.
         */
        fun shutdownSharedResources() {
            runCatching { sharedConnectionPool.evictAll() }
            if (sharedPoolDelegate.isInitialized()) {
                runCatching { sharedPool.dispatcher.executorService.shutdown() }
                runCatching { sharedPool.connectionPool.evictAll() }
            }
            if (sharedCacheDelegate.isInitialized()) {
                runCatching { sharedCache.close() }
            }
        }
    }
}
