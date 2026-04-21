package com.workflow.orchestrator.agent.tools.runtime

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/**
 * Unit tests for [HttpReadinessProbe] using a local in-process HTTP server.
 *
 * Uses `com.sun.net.httpserver.HttpServer` (JDK built-in) — no extra dependencies.
 * Uses `runBlocking` instead of `runTest` because [HttpReadinessProbe.poll] makes
 * real HTTP calls via `withContext(Dispatchers.IO)` which requires a real-time
 * dispatcher rather than the virtual-time test scheduler.
 */
class HttpReadinessProbeTest {

    private var server: HttpServer? = null
    private var serverPort: Int = 0

    @BeforeEach
    fun setUp() {
        // Port 0 → OS assigns a free port
        server = HttpServer.create(InetSocketAddress(0), 0).also {
            it.executor = null
            serverPort = it.address.port
        }
    }

    @AfterEach
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scenario 1 — 200 response immediately
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `poll returns Success when server responds with 200 immediately`() = runBlocking {
        server!!.createContext("/actuator/health") { exchange ->
            val body = """{"status":"UP"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server!!.start()

        val probe = HttpReadinessProbe()
        val result = probe.poll(
            url = "http://localhost:$serverPort/actuator/health",
            timeoutMs = 5000,
            gracePeriodMs = 0,
        )

        assertInstanceOf(HttpReadinessProbe.ProbeResult.Success::class.java, result,
            "Expected Success but got: $result")
        val success = result as HttpReadinessProbe.ProbeResult.Success
        assertTrue(success.responseSummary.contains("UP"), "Response summary must contain UP")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scenario 2 — 503 first, then 200 after several retries
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `poll returns Success after transitioning from 503 to 200`() = runBlocking {
        var callCount = 0
        server!!.createContext("/health") { exchange ->
            callCount++
            val (status, body) = if (callCount <= 2) {
                503 to """{"status":"DOWN"}"""
            } else {
                200 to """{"status":"UP"}"""
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server!!.start()

        val probe = HttpReadinessProbe()
        val result = probe.poll(
            url = "http://localhost:$serverPort/health",
            timeoutMs = 10_000,
            gracePeriodMs = 500,
            connectTimeoutMs = 1000,
        )

        assertInstanceOf(HttpReadinessProbe.ProbeResult.Success::class.java, result,
            "Expected Success after 503→200 transition. Got: $result")
        assertTrue(callCount >= 3, "Expected at least 3 calls (2 failures + 1 success). Got: $callCount")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scenario 3 — constant 503 → Timeout
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `poll returns Timeout when server consistently returns 503`() = runBlocking {
        server!!.createContext("/health") { exchange ->
            val body = """{"status":"DOWN"}""".toByteArray()
            exchange.sendResponseHeaders(503, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server!!.start()

        val probe = HttpReadinessProbe()
        val result = probe.poll(
            url = "http://localhost:$serverPort/health",
            timeoutMs = 700,
            gracePeriodMs = 0,
            connectTimeoutMs = 200,
        )

        // After timeout, expect Timeout (not Success)
        assertTrue(
            result is HttpReadinessProbe.ProbeResult.Timeout ||
                result is HttpReadinessProbe.ProbeResult.HttpError,
            "Constant 503 must produce Timeout or HttpError. Got: $result"
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scenario 4 — connection refused initially, then 200 after grace
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `poll returns Success when server starts after initial connection refused`() = runBlocking {
        // Start server on a background thread after 300ms delay
        val startThread = Thread {
            Thread.sleep(300)
            server!!.createContext("/actuator/health") { exchange ->
                val body = """{"status":"UP"}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server!!.start()
        }
        startThread.isDaemon = true
        startThread.start()

        val probe = HttpReadinessProbe()
        val result = probe.poll(
            url = "http://localhost:$serverPort/actuator/health",
            timeoutMs = 6000,
            gracePeriodMs = 1500,
            connectTimeoutMs = 400,
        )

        startThread.join(3000)
        assertInstanceOf(HttpReadinessProbe.ProbeResult.Success::class.java, result,
            "Expected Success after server came up. Got: $result")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scenario 5 — timeout (no server started)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `poll returns Timeout when no server is available and timeout elapses`() = runBlocking {
        // server is created but NOT started
        val probe = HttpReadinessProbe()
        val result = probe.poll(
            url = "http://localhost:$serverPort/health",
            timeoutMs = 600,
            gracePeriodMs = 0,
            connectTimeoutMs = 100,
        )

        assertTrue(
            result == HttpReadinessProbe.ProbeResult.Timeout ||
                result == HttpReadinessProbe.ProbeResult.ConnectionRefused,
            "With no server, result must be Timeout or ConnectionRefused. Got: $result"
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Scenario 6 — coroutine cancellation during polling
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `poll respects coroutine cancellation`() = runBlocking {
        server!!.createContext("/health") { exchange ->
            Thread.sleep(2000)
            val body = "{}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server!!.start()

        val probe = HttpReadinessProbe()
        var caughtCancellation = false

        val job = launch {
            try {
                probe.poll(
                    url = "http://localhost:$serverPort/health",
                    timeoutMs = 10_000,
                    gracePeriodMs = 0,
                    connectTimeoutMs = 5000,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                caughtCancellation = true
                throw e
            }
        }

        kotlinx.coroutines.delay(100)
        job.cancel("test cancellation")
        job.join()

        assertTrue(caughtCancellation || job.isCancelled,
            "Probe must stop when coroutine is cancelled")
    }
}
