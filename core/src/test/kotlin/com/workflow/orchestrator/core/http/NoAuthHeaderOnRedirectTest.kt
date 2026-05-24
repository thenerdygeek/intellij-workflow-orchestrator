package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.model.ServiceType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Security test: verifies that the Authorization header is NOT forwarded when a
 * server returns a 3xx redirect.
 *
 * With followRedirects(false) on the base client, OkHttp stops at the redirect
 * response and never issues a second request to the redirect target. The redirect
 * target therefore never sees the Bearer token.
 *
 * Closes audit finding core:F-2.
 */
class NoAuthHeaderOnRedirectTest {

    /** Primary server — returns a 302 redirect to [redirectTarget]. */
    private lateinit var primaryServer: MockWebServer

    /** Redirect target — must NOT receive Authorization header. */
    private lateinit var redirectTarget: MockWebServer

    private lateinit var factory: HttpClientFactory

    @BeforeEach
    fun setUp() {
        primaryServer = MockWebServer()
        primaryServer.start()

        redirectTarget = MockWebServer()
        redirectTarget.start()

        factory = HttpClientFactory(
            tokenProvider = { "super-secret-bearer-token" }
        )
    }

    @AfterEach
    fun tearDown() {
        primaryServer.shutdown()
        redirectTarget.shutdown()
    }

    @Test
    fun `Authorization header is not forwarded to redirect target`() {
        // Primary server replies with 302 → redirect target
        primaryServer.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", redirectTarget.url("/redirect-landing").toString())
        )
        // Redirect target serves a 200 (only reached if OkHttp follows the redirect)
        redirectTarget.enqueue(MockResponse().setBody("redirect-landing-body"))

        val client = factory.clientFor(ServiceType.JIRA)
        val response = client.newCall(
            okhttp3.Request.Builder().url(primaryServer.url("/start")).build()
        ).execute()
        response.close()

        // With followRedirects(false), OkHttp stops at the 302 — redirect target gets no request.
        // If the redirect target DID receive a request, we assert it lacks the Authorization header.
        val redirectRequest = redirectTarget.requestCount.let { count ->
            if (count == 0) return@let null   // expected: no request made
            redirectTarget.takeRequest()
        }

        // Primary assertion: redirect target received NO request at all.
        // If followRedirects were true, requestCount would be 1.
        assertNull(redirectRequest, "Redirect target must NOT receive any request when followRedirects=false")
    }

    @Test
    fun `client returns 302 response directly when followRedirects is disabled`() {
        primaryServer.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", redirectTarget.url("/landing").toString())
        )

        val client = factory.clientFor(ServiceType.BAMBOO)
        val response = client.newCall(
            okhttp3.Request.Builder().url(primaryServer.url("/api")).build()
        ).execute()

        // The 302 is returned as-is, not transparently followed
        assert(response.code == 302) { "Expected 302 response code, got ${response.code}" }
        response.close()
    }
}
