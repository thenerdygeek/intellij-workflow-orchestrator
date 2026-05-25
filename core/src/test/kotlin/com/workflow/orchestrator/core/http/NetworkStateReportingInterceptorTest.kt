package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.network.NetworkProbe
import com.workflow.orchestrator.core.network.NetworkState
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test

class NetworkStateReportingInterceptorTest {

    private fun probe(): NetworkProbe = mockk(relaxed = true) {
        io.mockk.every { state } returns MutableStateFlow(NetworkState.ONLINE)
    }

    @Test
    fun `reports success on any HTTP response`() {
        val server = MockWebServer().apply { enqueue(MockResponse().setResponseCode(500)); start() }
        val p = probe()
        val client = OkHttpClient.Builder().addInterceptor(NetworkStateReportingInterceptor { p }).build()
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
        verify { p.reportSuccess() }
        server.shutdown()
    }

    @Test
    fun `reports failure on transport IOException`() {
        // Point at a closed port so connect throws.
        val server = MockWebServer().apply { start() }
        val url = server.url("/").toString()
        server.shutdown() // now nothing is listening
        val p = probe()
        val client = OkHttpClient.Builder().addInterceptor(NetworkStateReportingInterceptor { p }).build()
        runCatching { client.newCall(Request.Builder().url(url).build()).execute() }
        verify { p.reportFailure(any()) }
    }
}
