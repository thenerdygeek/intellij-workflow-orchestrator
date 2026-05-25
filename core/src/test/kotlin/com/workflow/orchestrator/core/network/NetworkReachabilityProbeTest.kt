package com.workflow.orchestrator.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkReachabilityProbeTest {

    @Test
    fun `reachable when server answers (even 404)`() = runTest {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(404)); start() }
        try {
            val probe = NetworkReachabilityProbe()
            assertTrue(probe.isReachable(server.url("/").toString()))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `unreachable when host does not resolve`() = runTest {
        val probe = NetworkReachabilityProbe()
        // RFC 6761 reserved TLD .invalid never resolves
        assertFalse(probe.isReachable("https://this-host-does-not-exist.invalid"))
    }
}
