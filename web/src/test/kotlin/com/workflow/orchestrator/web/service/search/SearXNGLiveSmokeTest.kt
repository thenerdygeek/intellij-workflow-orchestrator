package com.workflow.orchestrator.web.service.search

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * Live smoke test for [SearXNGProvider] against a real SearXNG instance.
 *
 * Disabled by default — only runs when `-Dsearxng.live.url=<url>` is passed to gradle.
 * Example:
 *   docker run -d --rm --name searxng-test -p 8888:8080 \
 *       -v /tmp/searxng-config:/etc/searxng searxng/searxng
 *   ./gradlew :web:test --tests com.workflow.orchestrator.web.service.search.SearXNGLiveSmokeTest \
 *       -Dsearxng.live.url=http://localhost:8888 --dependency-verification lenient
 *
 * Verifies the production Kotlin class — not a MockWebServer stand-in — actually parses
 * SearXNG's real `/search?format=json` response and returns RawHit objects with real
 * title/url/snippet content. Complements the MockWebServer-based [SearXNGProviderTest].
 */
@EnabledIfSystemProperty(named = "searxng.live.url", matches = ".+")
class SearXNGLiveSmokeTest {

    @Test
    fun `live search against real SearXNG returns real results`() = runTest {
        val baseUrl = System.getProperty("searxng.live.url").trimEnd('/')
        val sut = SearXNGProvider(baseUrl = baseUrl, client = OkHttpClient())

        val result = sut.search(query = "kotlin coroutines", maxResults = 5)

        assertTrue(result.isSuccess, "search failed: ${result.exceptionOrNull()?.message}")
        val hits = result.getOrThrow()
        assertTrue(hits.isNotEmpty(), "expected at least 1 hit, got 0")
        assertTrue(hits.size <= 5, "asked for 5, got ${hits.size}")

        // Spot-check the first hit has real content
        val first = hits.first()
        assertTrue(first.title.isNotBlank(), "first hit title is blank")
        assertTrue(first.url.startsWith("http"), "first hit url is not an HTTP URL: ${first.url}")
        assertTrue(first.snippet.isNotBlank(), "first hit snippet is blank")
        assertTrue(first.rank == 0, "first hit rank should be 0, got ${first.rank}")

        // Print results so a human running this can see what SearXNG actually returned
        println()
        println("=== Live SearXNG smoke — $baseUrl ===")
        println("Query: kotlin coroutines")
        println("Got ${hits.size} hits:")
        hits.forEachIndexed { i, hit ->
            println("  [${i + 1}] ${hit.title}")
            println("      ${hit.url}")
            println("      ${hit.snippet.take(120)}${if (hit.snippet.length > 120) "..." else ""}")
        }
        println()
    }
}
