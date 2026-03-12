package com.workflow.orchestrator.core.http

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class HttpClientFactory(
    private val tokenProvider: (ServiceType) -> String?,
    private val connectTimeoutSeconds: Long = 10,
    private val readTimeoutSeconds: Long = 30
) {
    private val clients = ConcurrentHashMap<ServiceType, OkHttpClient>()

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor())
            .build()
    }

    fun clientFor(service: ServiceType): OkHttpClient {
        return clients.getOrPut(service) {
            val scheme = when (service) {
                ServiceType.NEXUS -> AuthScheme.BASIC
                else -> AuthScheme.BEARER
            }
            baseClient.newBuilder()
                .addInterceptor(AuthInterceptor({ tokenProvider(service) }, scheme))
                .build()
        }
    }

    companion object {
        /**
         * Creates an [HttpClientFactory] with timeouts read from [PluginSettings].
         */
        fun fromSettings(project: Project, tokenProvider: (ServiceType) -> String?): HttpClientFactory {
            val settings = PluginSettings.getInstance(project)
            return HttpClientFactory(
                tokenProvider = tokenProvider,
                connectTimeoutSeconds = settings.state.httpConnectTimeoutSeconds.toLong(),
                readTimeoutSeconds = settings.state.httpReadTimeoutSeconds.toLong()
            )
        }
    }
}
