package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.network.NetworkProbe
import com.workflow.orchestrator.core.network.NetworkStateService
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Feeds the connectivity authority from every service client. A completed response
 * (even 4xx/5xx) means the server was reached -> reportSuccess. An IOException means
 * the transport is down -> reportFailure. Added OUTERMOST (before RetryInterceptor)
 * so it reports the final outcome after any retries.
 */
class NetworkStateReportingInterceptor(
    private val probeProvider: () -> NetworkProbe? = { NetworkStateService.getInstanceOrNull() }
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        val origin = "${url.scheme}://${url.host}:${url.port}"
        return try {
            val response = chain.proceed(chain.request())
            probeProvider()?.reportSuccess()
            response
        } catch (e: IOException) {
            probeProvider()?.reportFailure(origin)
            throw e
        }
    }
}
