package com.workflow.orchestrator.bamboo.api

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Tests for [BambooApiClient.downloadArtifact].
 *
 * Coverage gaps addressed (BAMBOO-COV-7):
 * 1. Same-origin 200 → returns true and writes file content.
 * 2. Non-2xx (404) → returns false.
 * 3. Cross-origin request has no Authorization header (proves sharedPool is used, not
 *    the authenticated httpClient — the security boundary that prevents token leakage).
 */
class BambooApiClientDownloadArtifactTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BambooApiClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = BambooApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `downloadArtifact returns true and writes content for same-origin 200`(@TempDir tmp: Path) = runTest {
        val artifactContent = "artifact-binary-content-12345"
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(artifactContent)
        )

        val targetFile = tmp.resolve("artifact.zip").toFile()
        val result = client.downloadArtifact(
            artifactUrl = server.url("/artifact/myfile.zip").toString(),
            targetFile = targetFile
        )

        assertTrue(result, "downloadArtifact should return true on 200")
        assertTrue(targetFile.exists(), "Target file should exist after successful download")
        assertEquals(artifactContent, targetFile.readText(),
            "File content should match the response body")
    }

    @Test
    fun `downloadArtifact returns false on non-2xx response`(@TempDir tmp: Path) = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val targetFile = tmp.resolve("notfound.zip").toFile()
        val result = client.downloadArtifact(
            artifactUrl = server.url("/artifact/missing.zip").toString(),
            targetFile = targetFile
        )

        assertFalse(result, "downloadArtifact should return false on 404")
    }

    @Test
    fun `downloadArtifact sends no Authorization header for cross-origin URL`(@TempDir tmp: Path) = runTest {
        // Cross-origin artifact: a separate server whose URL does NOT start with
        // the client's baseUrl. BambooApiClient.downloadArtifact() must use
        // HttpClientFactory.sharedPool (no auth interceptor) rather than
        // httpClient (which carries an Authorization header for every request).
        // This is the security boundary that prevents token leakage to third-party hosts.
        val externalServer = MockWebServer()
        externalServer.start()
        try {
            externalServer.enqueue(
                MockResponse().setResponseCode(200).setBody("external artifact")
            )

            val targetFile = tmp.resolve("external.zip").toFile()
            val externalUrl = externalServer.url("/external/artifact.zip").toString()
            val clientBaseUrl = server.url("/").toString().trimEnd('/')

            // Verify the external URL does NOT start with the client's baseUrl
            // (test precondition: the two servers are on different ports).
            assertFalse(
                externalUrl.startsWith(clientBaseUrl),
                "Test precondition: external URL must not start with the client baseUrl"
            )

            client.downloadArtifact(artifactUrl = externalUrl, targetFile = targetFile)

            val recorded = externalServer.takeRequest()
            assertNull(
                recorded.getHeader("Authorization"),
                "Cross-origin download must NOT send Authorization header " +
                    "(would leak the Bamboo PAT to a third-party host)"
            )
        } finally {
            externalServer.shutdown()
        }
    }
}
