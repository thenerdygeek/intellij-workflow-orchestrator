package com.workflow.orchestrator.web.service

import com.workflow.orchestrator.core.security.UrlSafetyGuard
import com.workflow.orchestrator.core.web.WebError
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.net.InetAddress

class ShortenerResolverTest {
    private lateinit var server: MockWebServer
    private lateinit var sut: ShortenerResolver

    /**
     * MockWebServer binds on localhost; resolve loopback as 203.0.113.1 (TEST-NET-3)
     * so the I12 SSRF screen lets the call through to MockWebServer for the
     * existing happy-path tests. The dedicated I12 test below uses its own resolver
     * that DOES return loopback to prove the screen fires.
     */
    private val passthroughResolver = UrlSafetyGuard.Resolver { host ->
        if (host == "localhost" || host.matches(Regex("""^127\.\d+\.\d+\.\d+$"""))) {
            arrayOf(InetAddress.getByName("203.0.113.1"))
        } else {
            InetAddress.getAllByName(host)
        }
    }

    @BeforeEach fun setUp() {
        server = MockWebServer().apply { start() }
        sut = ShortenerResolver(
            client = OkHttpClient.Builder().followRedirects(false).build(),
            ssrfResolver = passthroughResolver,
            allowLoopback = true,    // MockWebServer always binds on loopback
        )
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

    // ── I12: SSRF defense in depth — the resolver screens the shortener URL ───

    @Test
    fun `resolver rejects shortener URL pointing at loopback`() = runTest {
        // Inject a resolver that returns 127.0.0.1 for the shortener host. The SSRF
        // screen must catch this and the resolver must return a UrlBlocked error
        // WITHOUT ever issuing the HTTP call.
        val loopbackResolver = UrlSafetyGuard.Resolver { _ ->
            arrayOf(InetAddress.getByName("127.0.0.1"))
        }
        val ssrfSut = ShortenerResolver(
            client = OkHttpClient.Builder().followRedirects(false).build(),
            ssrfResolver = loopbackResolver,
        )
        val result = ssrfSut.resolve("https://evil-shortener.example.com/abc")
        assertTrue(result is ShortenerResolver.Result.Failed)
        val err = (result as ShortenerResolver.Result.Failed).error
        assertTrue(err is WebError.UrlBlocked, "Expected UrlBlocked but got: ${err::class.simpleName} (${err.code})")
        assertTrue(err.code.startsWith("URL_BLOCKED_"), "Expected URL_BLOCKED_* code, got: ${err.code}")
    }
}
