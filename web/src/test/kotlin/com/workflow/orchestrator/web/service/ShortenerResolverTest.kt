package com.workflow.orchestrator.web.service

import com.workflow.orchestrator.core.web.WebError
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import kotlinx.coroutines.test.runTest

class ShortenerResolverTest {
    private lateinit var server: MockWebServer
    private lateinit var sut: ShortenerResolver

    @BeforeEach fun setUp() {
        server = MockWebServer().apply { start() }
        sut = ShortenerResolver(OkHttpClient.Builder().followRedirects(false).build())
    }

    @AfterEach fun tearDown() { server.shutdown() }

    @Test fun `301 with Location returns destination`() = runTest {
        server.enqueue(MockResponse().setResponseCode(301).addHeader("Location", "https://docs.example.com/page"))
        val result = sut.resolve(server.url("/abc").toString())
        assertEquals("https://docs.example.com/page", (result as ShortenerResolver.Result.Resolved).finalUrl)
    }

    @Test fun `meta-refresh fallback when no Location`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html")
                .setBody("""<html><head><meta http-equiv="refresh" content="0; url=https://target.example.com/x"></head></html>""")
        )
        val result = sut.resolve(server.url("/abc").toString())
        assertEquals("https://target.example.com/x", (result as ShortenerResolver.Result.Resolved).finalUrl)
    }

    @Test fun `404 returns ShortenerUnresolved error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = sut.resolve(server.url("/abc").toString())
        assertTrue(result is ShortenerResolver.Result.Failed)
        assertEquals("SHORTENER_UNRESOLVED", (result as ShortenerResolver.Result.Failed).error.code)
    }
}
