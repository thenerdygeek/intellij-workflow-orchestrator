package com.workflow.orchestrator.core.http

import com.intellij.openapi.diagnostic.Logger
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * Diagnostic OkHttp [EventListener] for the agent's chat client.
 *
 * Captures the connection lifecycle of every LLM call so we can prove or disprove
 * the OkHttp 4.x `IDLE_CONNECTION_HEALTHY_NS` stale-pool race
 * (https://github.com/square/okhttp/issues/7007).
 *
 * On every failure we want to know:
 *  - Was the connection pulled from the pool or freshly opened?
 *    -> Pooled reuse skips [connectStart]/[connectEnd], so the absence of [connectStart]
 *       between [callStart] and [connectionAcquired] is the diagnostic signal.
 *  - How long ago was the pooled connection last released?
 *    -> Anything < 10000ms means OkHttp's [Connection.isHealthy] short-circuit
 *       likely fired and skipped the real socket probe.
 *  - How many internal OkHttp retries were burned before the failure surfaced?
 *    -> [IOException.suppressed].size on [callFailed] tells us.
 *  - How long did the request live before failing?
 *    -> Wall-clock from [callStart] to [callFailed].
 *
 * After one captured failure, the log lines for the failing call's id should answer
 * the hypothesis with no further investigation needed.
 */
class ChatHttpEventListener : EventListener() {

    private val log = Logger.getInstance(ChatHttpEventListener::class.java)

    /** Wall-clock start time per call, used to compute call duration on failure/end. */
    private val callStartNanos = ConcurrentHashMap<Int, Long>()

    /** True if [connectStart] fired for this call (i.e. a fresh connection was opened). */
    private val sawConnectStart = ConcurrentHashMap<Int, Boolean>()

    /** Last [connectionReleased] time per connection identity, used to compute idleness. */
    private val connectionLastReleaseNanos = ConcurrentHashMap<Int, Long>()

    private fun callId(call: Call): Int = System.identityHashCode(call)
    private fun connId(connection: Connection): Int = System.identityHashCode(connection)

    override fun callStart(call: Call) {
        val id = callId(call)
        callStartNanos[id] = System.nanoTime()
        sawConnectStart[id] = false
        log.info("[OkHttp] callStart id=$id ${call.request().method} ${call.request().url}")
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        sawConnectStart[callId(call)] = true
        log.info("[OkHttp] connectStart id=${callId(call)} addr=$inetSocketAddress (FRESH connection)")
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?
    ) {
        log.info("[OkHttp] connectEnd id=${callId(call)} protocol=$protocol")
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException
    ) {
        log.warn("[OkHttp] connectFailed id=${callId(call)} addr=$inetSocketAddress msg='${ioe.message}'")
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        val cid = callId(call)
        val connKey = connId(connection)
        val fresh = sawConnectStart[cid] == true
        val sinceReleaseMs = connectionLastReleaseNanos[connKey]?.let {
            (System.nanoTime() - it) / 1_000_000
        }
        val isHealthyShortcut = sinceReleaseMs != null && sinceReleaseMs < 10_000
        log.info(
            "[OkHttp] connectionAcquired id=$cid conn=$connKey fresh=$fresh " +
                "sinceLastReleaseMs=${sinceReleaseMs ?: "n/a"} " +
                "isHealthyShortcutWindow=$isHealthyShortcut"
        )
    }

    override fun connectionReleased(call: Call, connection: Connection) {
        connectionLastReleaseNanos[connId(connection)] = System.nanoTime()
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        log.info("[OkHttp] requestHeadersEnd id=${callId(call)} bodyLen=${request.body?.contentLength() ?: -1}")
    }

    override fun responseHeadersStart(call: Call) {
        log.info("[OkHttp] responseHeadersStart id=${callId(call)} (waiting for server)")
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        log.info("[OkHttp] responseHeadersEnd id=${callId(call)} code=${response.code}")
    }

    override fun callEnd(call: Call) {
        val id = callId(call)
        val durationMs = callStartNanos.remove(id)?.let { (System.nanoTime() - it) / 1_000_000 }
        sawConnectStart.remove(id)
        log.info("[OkHttp] callEnd id=$id durationMs=$durationMs")
    }

    override fun callFailed(call: Call, ioe: IOException) {
        val id = callId(call)
        val durationMs = callStartNanos.remove(id)?.let { (System.nanoTime() - it) / 1_000_000 }
        val wasFresh = sawConnectStart.remove(id) == true
        val rootCause = generateSequence(ioe as Throwable?) { it.cause }.last()
        log.warn(
            "[OkHttp] callFailed id=$id durationMs=$durationMs " +
                "fresh=$wasFresh " +
                "suppressed=${ioe.suppressed.size} " +
                "ex=${ioe::class.simpleName}('${ioe.message}') " +
                "rootCause=${rootCause::class.simpleName}('${rootCause.message}')"
        )
    }
}
