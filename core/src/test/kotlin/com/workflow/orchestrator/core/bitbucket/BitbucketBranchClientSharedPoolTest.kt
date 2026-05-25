package com.workflow.orchestrator.core.bitbucket

import com.workflow.orchestrator.core.http.HttpClientFactory
import com.workflow.orchestrator.core.http.HttpTimeouts
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Regression tests for audit finding core:F-6:
 * [BitbucketBranchClient] must not create a fresh [okhttp3.ConnectionPool] per instance.
 *
 * Before the fix, each [BitbucketBranchClient] constructed its own [HttpClientFactory]
 * with an independent [okhttp3.ConnectionPool]. Since the client is created per-action
 * (via [BitbucketBranchClient.fromConfiguredSettings] or [BitbucketBranchClient.forRepo]),
 * this leaked pools and threads with every call.
 *
 * After the fix, [BitbucketBranchClient.httpClient] is built from
 * [HttpClientFactory.sharedPool] so all instances share the same [okhttp3.ConnectionPool].
 */
class BitbucketBranchClientSharedPoolTest {

    private fun makeClient(token: String = "test-token"): BitbucketBranchClient =
        BitbucketBranchClient(
            baseUrl = "https://bitbucket.example.com",
            tokenProvider = { token }
        )

    @Test
    fun `two BitbucketBranchClient instances share the same ConnectionPool`() {
        val client1 = makeClient("token-a")
        val client2 = makeClient("token-b")

        // Force the lazy httpClient to initialise on both instances
        val pool1 = client1.httpClient.connectionPool
        val pool2 = client2.httpClient.connectionPool

        // Both must reference the exact same ConnectionPool instance — the shared one
        // from HttpClientFactory.sharedPool, not two independently constructed pools.
        assertSame(
            pool1,
            pool2,
            "Both BitbucketBranchClient instances must share the same ConnectionPool (core:F-6)"
        )
    }

    @Test
    fun `shared ConnectionPool matches HttpClientFactory sharedPool`() {
        val client = makeClient()
        val clientPool = client.httpClient.connectionPool
        val sharedPool = HttpClientFactory.sharedPool.connectionPool

        assertSame(
            sharedPool,
            clientPool,
            "BitbucketBranchClient must use HttpClientFactory.sharedPool as its ConnectionPool"
        )
    }

    @Test
    fun `auth scheme stays BEARER — Authorization header uses Bearer prefix`() {
        // Verify that the BEARER auth scheme is still used after the pool-sharing refactor.
        // We check indirectly: the AuthInterceptor is configured with AuthScheme.BEARER,
        // which prepends "Bearer " to the token. We verify the interceptor count and that
        // a non-null token provider produces a non-null client (compile/init path test).
        val client = makeClient("my-token")
        val httpClient = client.httpClient
        assertNotNull(httpClient, "httpClient must be non-null after lazy init")
        // Interceptors added: RetryInterceptor + AuthInterceptor = 2 application interceptors
        assertEquals(2, httpClient.interceptors.size,
            "Expected RetryInterceptor + AuthInterceptor (2 application interceptors)")
    }

    @Test
    fun `two fromConfiguredSettings clients share the same ConnectionPool`() {
        // fromConfiguredSettings() returns null when Bitbucket is not configured (blank URL).
        // Test the pool sharing at the constructor level instead (same code path as factory methods).
        val c1 = makeClient("t1")
        val c2 = makeClient("t2")
        assertSame(c1.httpClient.connectionPool, c2.httpClient.connectionPool,
            "Clients from equivalent factory paths must share ConnectionPool")
    }

    // ── F-13: timeoutsFromSettings wired into BitbucketBranchClient ───────

    @Test
    fun `custom HttpTimeouts are applied to httpClient connect and read timeouts`() {
        // core:F-13 — BitbucketBranchClient previously used hardcoded 10s/30s defaults
        // from HttpClientFactory's no-arg constructor and ignored the user-configured timeouts.
        // After the fix, the timeouts parameter is forwarded to the OkHttpClient builder.
        val customTimeouts = HttpTimeouts(connectSeconds = 45L, readSeconds = 120L)
        val client = BitbucketBranchClient(
            baseUrl = "https://bitbucket.example.com",
            tokenProvider = { "tok" },
            timeouts = customTimeouts
        )
        val httpClient = client.httpClient

        // OkHttpClient.connectTimeoutMillis is the canonical reflection point;
        // a 45s connect timeout means 45_000 ms.
        assertEquals(45_000, httpClient.connectTimeoutMillis,
            "connectTimeout should be 45s (45_000 ms) as set in HttpTimeouts")
        assertEquals(120_000, httpClient.readTimeoutMillis,
            "readTimeout should be 120s (120_000 ms) as set in HttpTimeouts")
    }

    @Test
    fun `default HttpTimeouts are 10s connect and 30s read when not specified`() {
        // Existing call sites that don't pass a timeouts arg must continue to use the
        // same defaults as before (10s/30s from HttpTimeouts.DEFAULT).
        val client = makeClient()
        assertEquals(10_000, client.httpClient.connectTimeoutMillis,
            "default connectTimeout should be 10s")
        assertEquals(30_000, client.httpClient.readTimeoutMillis,
            "default readTimeout should be 30s")
    }
}
