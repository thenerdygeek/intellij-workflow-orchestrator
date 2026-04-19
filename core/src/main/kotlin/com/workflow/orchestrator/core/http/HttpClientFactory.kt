package com.workflow.orchestrator.core.http

import com.intellij.openapi.application.PathManager
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
            .addInterceptor(RetryInterceptor())
            .addNetworkInterceptor(SensitiveEndpointNoCacheInterceptor())
            .build()
    }

    fun clientFor(service: ServiceType): OkHttpClient {
        return clients.getOrPut(service) {
            val scheme = when (service) {
                ServiceType.NEXUS -> AuthScheme.BASIC
                ServiceType.SOURCEGRAPH -> AuthScheme.TOKEN
                else -> AuthScheme.BEARER
            }
            baseClient.newBuilder()
                .addInterceptor(HttpMetricsInterceptor())
                .addInterceptor(AuthInterceptor({ tokenProvider(service) }, scheme))
                .build()
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

        /** Shared connection pool across all OkHttpClient instances in the plugin. */
        private val sharedConnectionPool = ConnectionPool(15, 5, TimeUnit.MINUTES)

        /** Shared HTTP response cache (10 MB) for ETag/304 support. */
        private val sharedCache: Cache by lazy {
            val cacheDir = File(PathManager.getSystemPath(), "workflow-orchestrator/http-cache")
            Cache(cacheDir, 10L * 1024 * 1024)
        }

        /**
         * Base client with shared connection pool and cache.
         * Use [OkHttpClient.newBuilder] to customize per-service.
         */
        val sharedPool: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(sharedConnectionPool)
                .cache(sharedCache)
                .build()
        }
    }
}
