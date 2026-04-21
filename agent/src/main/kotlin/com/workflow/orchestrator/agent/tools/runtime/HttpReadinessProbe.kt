package com.workflow.orchestrator.agent.tools.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.coroutines.coroutineContext

/**
 * HTTP-based readiness probe — polls an endpoint until 200 OK, timeout, or cancellation.
 *
 * Backoff: start 200ms, cap 2000ms, multiplier 1.5 (Kubernetes-inspired).
 * Grace period: failures are tolerated during the initial grace window.
 * Never throws; returns sealed [ProbeResult].
 */
class HttpReadinessProbe(private val httpClient: HttpClient = DEFAULT_CLIENT) {

    /** Result of a completed probe attempt. */
    sealed class ProbeResult {
        /** HTTP 200 received — app is ready. [responseSummary] is the first 200 chars of body. */
        data class Success(val responseSummary: String) : ProbeResult()
        /** Probe did not succeed within [timeoutMs]. */
        object Timeout : ProbeResult()
        /** TCP connection was refused throughout the probe window. */
        object ConnectionRefused : ProbeResult()
        /** Server responded with a non-200 status code throughout. [status] is the last seen code. */
        data class HttpError(val status: Int) : ProbeResult()
    }

    /**
     * Poll [url] until a 200 response is received, [timeoutMs] elapses, or the coroutine
     * is cancelled.
     *
     * @param url            Full URL to probe (e.g. `http://localhost:8080/actuator/health`).
     * @param timeoutMs      Overall probe budget in milliseconds.
     * @param gracePeriodMs  Failures during this window are ignored (Docker --start-period pattern).
     * @param connectTimeoutMs Per-request connect timeout.
     */
    suspend fun poll(
        url: String,
        timeoutMs: Long,
        gracePeriodMs: Long = DEFAULT_GRACE_MS,
        connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    ): ProbeResult {
        var lastStatus = -1
        var lastConnRefused = false
        var backoffMs = BACKOFF_START_MS
        val startMs = System.currentTimeMillis()

        val outcome = withTimeoutOrNull(timeoutMs) {
            while (true) {
                coroutineContext.ensureActive()

                val probeResult = executeSingleProbe(url, connectTimeoutMs)
                val elapsedMs = System.currentTimeMillis() - startMs
                val inGrace = elapsedMs < gracePeriodMs

                when (probeResult) {
                    is SingleResult.Ok -> return@withTimeoutOrNull ProbeResult.Success(probeResult.summary)
                    is SingleResult.BadStatus -> {
                        lastStatus = probeResult.status
                        lastConnRefused = false
                        if (!inGrace && lastStatus >= 400) {
                            // Non-grace non-200 — keep trying (actuator may return 503 while
                            // starting, then transition to 200 after init).
                        }
                    }
                    is SingleResult.Refused -> {
                        lastConnRefused = true
                        lastStatus = -1
                    }
                    is SingleResult.OtherError -> {
                        // Network hiccup — keep retrying
                    }
                }

                coroutineContext.ensureActive()
                delay(backoffMs)
                backoffMs = (backoffMs * BACKOFF_MULTIPLIER).toLong().coerceAtMost(BACKOFF_CAP_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }

        return when {
            outcome != null -> outcome
            else -> ProbeResult.Timeout
        }
    }

    private suspend fun executeSingleProbe(
        url: String,
        connectTimeoutMs: Long,
    ): SingleResult = withContext(Dispatchers.IO) {
        try {
            val client = if (connectTimeoutMs > 0) {
                HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                    .build()
            } else {
                httpClient
            }
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofMillis(connectTimeoutMs.coerceAtLeast(REQUEST_TIMEOUT_MS)))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val status = response.statusCode()
            if (status in 200..299) {
                val body = response.body() ?: ""
                val summary = body.take(RESPONSE_SUMMARY_CHARS)
                SingleResult.Ok(summary)
            } else {
                SingleResult.BadStatus(status)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConnectException) {
            SingleResult.Refused
        } catch (e: Exception) {
            // ConnectException subclasses, SocketTimeoutException, etc.
            if (e.cause is ConnectException ||
                e.message?.contains("refused", ignoreCase = true) == true ||
                e.message?.contains("Connection refused", ignoreCase = true) == true
            ) {
                SingleResult.Refused
            } else {
                SingleResult.OtherError(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // Internal result type for single probe attempts
    private sealed class SingleResult {
        data class Ok(val summary: String) : SingleResult()
        data class BadStatus(val status: Int) : SingleResult()
        object Refused : SingleResult()
        data class OtherError(val message: String) : SingleResult()
    }

    companion object {
        private const val BACKOFF_START_MS = 200L
        private const val BACKOFF_CAP_MS = 2000L
        private const val BACKOFF_MULTIPLIER = 1.5
        private const val DEFAULT_GRACE_MS = 5000L
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 3000L
        private const val REQUEST_TIMEOUT_MS = 3000L
        private const val RESPONSE_SUMMARY_CHARS = 200

        /** Shared default client (no connect timeout — per-call client used instead). */
        private val DEFAULT_CLIENT: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build()
    }
}
