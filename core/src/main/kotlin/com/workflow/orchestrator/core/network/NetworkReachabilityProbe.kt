package com.workflow.orchestrator.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Short-timeout HEAD probe. Deliberately uses its OWN OkHttpClient — never
 * HttpClientFactory — so it has no RetryInterceptor and no reporting interceptor
 * (a probe must not feed itself back into the detector).
 */
class NetworkReachabilityProbe(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
) : ReachabilityProbe {

    override suspend fun isReachable(targetUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(normalize(targetUrl)).head().build()
            client.newCall(req).execute().use { true } // any response = host answered
        } catch (e: Exception) {
            // IOException (connect/timeout) or IllegalArgumentException (bad URL) = unreachable.
            // CancellationException is an Exception subtype but withContext rethrows it for us.
            false
        }
    }

    private fun normalize(s: String): String {
        val withScheme = if (s.startsWith("http://") || s.startsWith("https://")) s else "https://$s"
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }
}
