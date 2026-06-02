package com.workflow.orchestrator.web.service

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-text contract test: the web HTTP clients must route through the IDE proxy.
 * Live-proxy behavior can't be unit-tested, so we pin that every client builder calls
 * IdeProxy.selector(). If a future edit drops it, external requests silently bypass the
 * corporate proxy and time out — this guards that regression.
 */
class WebClientProxyWiringTest {
    private fun src(path: String) = File(path).readText()

    @Test
    fun `fetch and shortener clients use the IDE proxy selector`() {
        val s = src("src/main/kotlin/com/workflow/orchestrator/web/service/WebFetchServiceImpl.kt")
        val count = Regex("""\.proxySelector\(IdeProxy\.selector\(\)\)""").findAll(s).count()
        assertTrue(count >= 2, "expected >=2 proxySelector(IdeProxy.selector()) calls (fetch + shortener), found $count")
    }

    @Test
    fun `search client uses the IDE proxy selector`() {
        val s = src("src/main/kotlin/com/workflow/orchestrator/web/service/WebSearchServiceImpl.kt")
        assertTrue(
            s.contains(".proxySelector(IdeProxy.selector())"),
            "search client must call .proxySelector(IdeProxy.selector())",
        )
    }

    @Test
    fun `fetch and shortener clients install the IDE proxy authenticator`() {
        val s = src("src/main/kotlin/com/workflow/orchestrator/web/service/WebFetchServiceImpl.kt")
        val count = Regex("""\.proxyAuthenticator\(IdeProxy\.proxyAuthenticator\(\)\)""").findAll(s).count()
        assertTrue(count >= 2, "expected >=2 proxyAuthenticator(IdeProxy.proxyAuthenticator()) calls (fetch + shortener), found $count")
    }

    @Test
    fun `search client installs the IDE proxy authenticator`() {
        val s = src("src/main/kotlin/com/workflow/orchestrator/web/service/WebSearchServiceImpl.kt")
        assertTrue(
            s.contains(".proxyAuthenticator(IdeProxy.proxyAuthenticator())"),
            "search client must call .proxyAuthenticator(IdeProxy.proxyAuthenticator())",
        )
    }

    @Test
    fun `settings Test client uses the IDE proxy selector`() {
        val s = src("src/main/kotlin/com/workflow/orchestrator/web/ui/WebSettingsConfigurable.kt")
        assertTrue(
            s.contains(".proxySelector(IdeProxy.selector())"),
            "settings Test client must call .proxySelector(IdeProxy.selector())",
        )
    }
}
