package com.workflow.orchestrator.core.http

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class HttpMetricsInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val tag = ServiceUrlTagger.tag(request.url)
        val startMs = System.currentTimeMillis()
        val response: Response
        try {
            response = chain.proceed(request)
        } catch (e: IOException) {
            HttpMetricsRegistry.record(tag, -1, System.currentTimeMillis() - startMs, true)
            throw e
        } catch (e: Exception) {
            HttpMetricsRegistry.record(tag, -1, System.currentTimeMillis() - startMs, true)
            throw e
        }
        HttpMetricsRegistry.record(tag, response.code, System.currentTimeMillis() - startMs, response.code >= 400)
        return response
    }

    object ServiceUrlTagger {
        fun tag(url: HttpUrl): String {
            val host = url.host
            val path = url.encodedPath
            return when {
                host.contains("atlassian") || path.contains("/rest/api/2") || path.contains("/rest/agile") -> "jira"
                path.contains("/rest/api/latest/result") || path.contains("/rest/api/latest/plan") -> "bamboo"
                path.contains("/rest/api/1.0") -> "bitbucket"
                host.contains("sonar") || path.contains("/api/measures") || path.contains("/api/issues") -> "sonar"
                path.contains("/.api/llm") || host.contains("sourcegraph") -> "sourcegraph"
                else -> "unknown"
            }
        }
    }
}
