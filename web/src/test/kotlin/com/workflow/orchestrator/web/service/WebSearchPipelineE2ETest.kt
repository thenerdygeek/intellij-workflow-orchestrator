package com.workflow.orchestrator.web.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.web.SearchHit
import com.workflow.orchestrator.core.security.UrlSafetyGuard
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.web.SearchProvider
import com.workflow.orchestrator.core.web.SubagentSpawner
import com.workflow.orchestrator.core.web.WebSearchService.WebSearchRequest
import com.workflow.orchestrator.web.audit.WebAuditLog
import com.workflow.orchestrator.web.service.sanitizer.JsoupReadability
import com.workflow.orchestrator.web.service.sanitizer.SanitizerSubagent
import com.workflow.orchestrator.web.service.search.SearchProviderRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.nio.file.Files

/**
 * End-to-end pipeline tests for [WebSearchEngine] covering 6 scenarios.
 *
 * Uses stub [SearchProvider] + mock [SubagentSpawner]; NO [okhttp3.mockwebserver.MockWebServer]
 * — providers are mocked at the [SearchProvider] interface boundary.
 */
class WebSearchPipelineE2ETest {

    private lateinit var engine: WebSearchEngine
    private lateinit var settings: PluginSettings
    private lateinit var state: PluginSettings.State
    private lateinit var spawner: SubagentSpawner
    private val project = mockk<Project>(relaxed = true)

    // ── Stub provider ──────────────────────────────────────────────────────────

    /** Stub search provider: configure [hits] before each test. */
    private class StubSearchProvider(
        private val hits: List<SearchProvider.RawHit> = emptyList(),
        private val failWith: Exception? = null,
    ) : SearchProvider {
        override val id: SearchProvider.ProviderId = SearchProvider.ProviderId.SEARXNG
        override suspend fun validate(): Result<Unit> = Result.success(Unit)
        override suspend fun search(query: String, maxResults: Int): Result<List<SearchProvider.RawHit>> =
            if (failWith != null) Result.failure(failWith)
            else Result.success(hits.take(maxResults))
    }

    /**
     * A resolver that always says the given host resolves to a benign public IP (93.184.216.34 —
     * the real example.com address). Prevents live DNS in tests.
     */
    private val allowAllResolver = UrlSafetyGuard.Resolver { _ ->
        arrayOf(InetAddress.getByName("93.184.216.34"))
    }

    private fun buildEngine(
        provider: SearchProvider?,
        baseUrl: String = "https://example.com",
        allowLoopback: Boolean = false,
        ssrfResolver: UrlSafetyGuard.Resolver = allowAllResolver,
    ): WebSearchEngine {
        val auditLog = WebAuditLog(Files.createTempDirectory("search-e2e"))
        val registry = object : SearchProviderRegistry(project, mockk<okhttp3.OkHttpClient>()) {
            override fun resolve(pinnedDns: okhttp3.Dns): SearchProviderRegistry.ResolvedProvider? =
                provider?.let { SearchProviderRegistry.ResolvedProvider(it, baseUrl, allowLoopback) }
        }
        return WebSearchEngine(
            project = project,
            settings = settings,
            sanitizerSubagent = SanitizerSubagent(spawner),
            jsoupReadability = JsoupReadability(),
            registry = registry,
            auditLog = auditLog,
            ssrfResolver = ssrfResolver,
        )
    }

    private fun freshState() = PluginSettings.State().apply {
        webSearchProviderType = "SEARXNG"
        webSearchSnippetMaxChars = 500
        webSearchMaxResults = 10
    }

    @BeforeEach
    fun setUp() {
        state = freshState()
        settings = mockk(relaxed = true)
        every { settings.state } returns state

        spawner = mockk()
        // Default: batch sanitizer returns SAFE results echoing cleaned text
        coEvery {
            spawner.runSanitizerBatch(any(), any(), any(), any(), any(), any())
        } answers {
            val count = arg<Int>(5)
            List(count) { SubagentSpawner.SanitizerResult(SubagentSpawner.Verdict.SAFE, "sanitized snippet", null) }
        }
    }

    // ── Test cases ─────────────────────────────────────────────────────────────

    @Test
    fun `no provider configured returns NoProviderConfigured`() = runTest {
        engine = buildEngine(provider = null)
        val result = engine.search(WebSearchRequest(query = "kotlin coroutines"))
        assertTrue(result.isError)
        assertTrue(result.summary.contains("NO_PROVIDER_CONFIGURED"), "summary: ${result.summary}")
    }

    @Test
    fun `provider returns happy path - 3 SearchHits with sanitized snippets`() = runTest {
        val rawHits = listOf(
            SearchProvider.RawHit(title = "Result 1", url = "https://example.com/1", snippet = "snippet one", rank = 0),
            SearchProvider.RawHit(title = "Result 2", url = "https://example.com/2", snippet = "snippet two", rank = 1),
            SearchProvider.RawHit(title = "Result 3", url = "https://example.com/3", snippet = "snippet three", rank = 2),
        )
        coEvery {
            spawner.runSanitizerBatch(any(), any(), any(), any(), any(), 3)
        } returns listOf(
            SubagentSpawner.SanitizerResult(SubagentSpawner.Verdict.SAFE, "clean one", null),
            SubagentSpawner.SanitizerResult(SubagentSpawner.Verdict.SAFE, "clean two", null),
            SubagentSpawner.SanitizerResult(SubagentSpawner.Verdict.SAFE, "clean three", null),
        )
        engine = buildEngine(StubSearchProvider(rawHits))

        val result = engine.search(WebSearchRequest(query = "test query", maxResults = 5))
        assertFalse(result.isError)
        val hits = result.data!!
        assertEquals(3, hits.size)
        assertEquals("clean one", hits[0].snippet)
        assertEquals("clean two", hits[1].snippet)
        assertEquals("clean three", hits[2].snippet)
        assertTrue(result.summary.contains("3 results"))
    }

    @Test
    fun `plan mode blocked when planMode = true`() = runTest {
        engine = buildEngine(StubSearchProvider())
        val result = engine.search(WebSearchRequest(query = "anything", planMode = true))
        assertTrue(result.isError)
        assertTrue(result.summary.contains("PLAN_MODE_BLOCKED"), "summary: ${result.summary}")
    }

    @Test
    fun `query with Bearer token gets redacted before reaching provider`() = runTest {
        // We verify the cleaned query (stripped of Bearer token) reaches the provider.
        var capturedQuery: String? = null
        val capturingProvider = object : SearchProvider {
            override val id = SearchProvider.ProviderId.SEARXNG
            override suspend fun validate() = Result.success(Unit)
            override suspend fun search(query: String, maxResults: Int): Result<List<SearchProvider.RawHit>> {
                capturedQuery = query
                return Result.success(emptyList())
            }
        }
        engine = buildEngine(capturingProvider)

        val raw = "Bearer eyJhbGciOiJSUzI1NiJ9.secret kotlin tutorial"
        val result = engine.search(WebSearchRequest(query = raw))
        assertFalse(result.isError, "Expected success but got: ${result.summary}")
        // Provider must NOT receive the raw token
        assertTrue(capturedQuery != null)
        assertFalse(capturedQuery!!.contains("eyJhbGciOiJSUzI1NiJ9.secret"), "Token leaked to provider: $capturedQuery")
        assertTrue(capturedQuery!!.contains("<"), "Redaction placeholder missing: $capturedQuery")
    }

    @Test
    fun `provider auth fail returns ProviderAuthFailed`() = runTest {
        val authFailProvider = StubSearchProvider(failWith = IllegalStateException("PROVIDER_AUTH_FAILED"))
        engine = buildEngine(authFailProvider)

        val result = engine.search(WebSearchRequest(query = "search term"))
        assertTrue(result.isError)
        assertTrue(result.summary.contains("PROVIDER_AUTH_FAILED"), "summary: ${result.summary}")
    }

    @Test
    fun `batch sanitization SAFE verdict returns clean snippets in order`() = runTest {
        val rawHits = listOf(
            SearchProvider.RawHit(title = "A", url = "https://a.com", snippet = "raw a", rank = 0),
            SearchProvider.RawHit(title = "B", url = "https://b.com", snippet = "raw b", rank = 1),
            SearchProvider.RawHit(title = "C", url = "https://c.com", snippet = "raw c", rank = 2),
        )
        coEvery {
            spawner.runSanitizerBatch(any(), any(), any(), any(), any(), 3)
        } returns listOf(
            SubagentSpawner.SanitizerResult(SubagentSpawner.Verdict.SAFE, "safe-a", null),
            SubagentSpawner.SanitizerResult(SubagentSpawner.Verdict.SAFE, "safe-b", null),
            SubagentSpawner.SanitizerResult(SubagentSpawner.Verdict.SAFE, "safe-c", null),
        )
        engine = buildEngine(StubSearchProvider(rawHits))

        val result = engine.search(WebSearchRequest(query = "ordered results", maxResults = 10))
        assertFalse(result.isError)
        val hits: List<SearchHit> = result.data!!
        assertEquals(3, hits.size)
        // Order must be preserved
        assertEquals("safe-a", hits[0].snippet)
        assertEquals("safe-b", hits[1].snippet)
        assertEquals("safe-c", hits[2].snippet)
    }

    // ── B3 regression: provider URL screened through UrlSafetyGuard ───────────

    /**
     * B3: A SearXNG provider configured with an AWS metadata URL must be rejected
     * by UrlSafetyGuard before any HTTP call to the provider is made.
     *
     * allowLoopback=true applies for SearXNG (local instances), but link-local
     * (169.254.0.0/16) is always blocked regardless — so 169.254.169.254 is still rejected.
     */
    @Test
    fun `SearXNG provider configured with link-local URL is rejected as PROVIDER_URL_UNSAFE`() = runTest {
        // Resolver that says 169.254.169.254 resolves to itself (link-local — always blocked)
        val linkLocalResolver = UrlSafetyGuard.Resolver { _ ->
            arrayOf(InetAddress.getByName("169.254.169.254"))
        }
        engine = buildEngine(
            provider = StubSearchProvider(),
            baseUrl = "http://169.254.169.254/search",
            allowLoopback = true,   // SearXNG — loopback is allowed, but link-local is not
            ssrfResolver = linkLocalResolver,
        )

        val result = engine.search(WebSearchRequest(query = "test"))
        assertTrue(result.isError, "Expected SSRF block but got success")
        assertTrue(
            result.summary.contains("PROVIDER_URL_UNSAFE"),
            "Expected PROVIDER_URL_UNSAFE but got: ${result.summary}"
        )
    }

    /**
     * B3: A Brave provider configured with a private-LAN URL must be rejected.
     * allowLoopback=false for Brave, so private LAN ranges are blocked.
     */
    @Test
    fun `Brave provider configured with private-LAN URL is rejected as PROVIDER_URL_UNSAFE`() = runTest {
        val privateLanResolver = UrlSafetyGuard.Resolver { _ ->
            arrayOf(InetAddress.getByName("192.168.1.1"))
        }
        engine = buildEngine(
            provider = StubSearchProvider(),
            baseUrl = "http://192.168.1.1/api",
            allowLoopback = false,  // Brave — remote provider, no loopback exception
            ssrfResolver = privateLanResolver,
        )

        val result = engine.search(WebSearchRequest(query = "test"))
        assertTrue(result.isError, "Expected SSRF block but got success")
        assertTrue(
            result.summary.contains("PROVIDER_URL_UNSAFE"),
            "Expected PROVIDER_URL_UNSAFE but got: ${result.summary}"
        )
    }

    /**
     * B3 (sanity): SearXNG configured with http://localhost:8080 succeeds because
     * allowLoopback=true is set for SearXNG and the provider returns results.
     */
    @Test
    fun `SearXNG with localhost base URL succeeds when allowLoopback is true`() = runTest {
        // Resolver that says localhost resolves to 127.0.0.1
        val loopbackResolver = UrlSafetyGuard.Resolver { _ ->
            arrayOf(InetAddress.getByName("127.0.0.1"))
        }
        val rawHits = listOf(
            SearchProvider.RawHit(title = "Local result", url = "https://example.com", snippet = "local", rank = 0)
        )
        engine = buildEngine(
            provider = StubSearchProvider(rawHits),
            baseUrl = "http://localhost:8080",
            allowLoopback = true,  // SearXNG loopback exception
            ssrfResolver = loopbackResolver,
        )

        val result = engine.search(WebSearchRequest(query = "local test"))
        assertFalse(result.isError, "Expected success with SearXNG localhost but got: ${result.summary}")
    }

    // ── I5 regression: search passes resolved sanitizer brainId to subagent ───

    @Test
    fun `search passes resolved sanitizer brainId to subagent`() = runTest {
        // Configure a non-blank webSanitizerBrainId and verify it reaches runSanitizerBatch.
        state.webSanitizerBrainId = "haiku-4-5"
        // Capture brainId via a mutable reference (nullable String can't use slot<String?> with capture())
        val capturedBrainIds = mutableListOf<String?>()
        coEvery {
            spawner.runSanitizerBatch(any(), any(), any(), any(), any(), any())
        } answers {
            capturedBrainIds.add(arg<String?>(1))
            val count = arg<Int>(5)
            List(count) { SubagentSpawner.SanitizerResult(SubagentSpawner.Verdict.SAFE, "ok", null) }
        }

        val rawHits = listOf(
            SearchProvider.RawHit(title = "T", url = "https://example.com", snippet = "s", rank = 0)
        )
        engine = buildEngine(StubSearchProvider(rawHits))
        // resolveSanitizerBrainId() reads settings.state; wire it on the mock.
        every { settings.state } returns state

        val result = engine.search(WebSearchRequest(query = "brain id test"))
        assertFalse(result.isError, "Expected success but got: ${result.summary}")
        assertTrue(capturedBrainIds.isNotEmpty(), "runSanitizerBatch was never called")
        assertEquals("haiku-4-5", capturedBrainIds.first(),
            "runSanitizerBatch must receive the configured brainId")
    }
}
