package com.workflow.orchestrator.core.http

import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Headless contract for [IdeTrust]. In a plain JUnit run there is no IntelliJ
 * application container, so [com.intellij.util.net.ssl.CertificateManager] cannot
 * be obtained. The helper MUST degrade gracefully (never throw) — exactly how
 * [IdeProxyTest] verifies [IdeProxy]'s fallback. The "wire the real cert manager"
 * path is integration-only and not exercised here.
 */
class IdeTrustTest {

    @Test
    fun `sslConfig returns null when CertificateManager unavailable`() {
        // No platform container in unit tests → CertificateManager.getInstance() fails.
        // Contract: swallow the failure and return null rather than propagate it.
        val config = assertDoesNotThrow { IdeTrust.sslConfig() }
        assertNull(config, "expected null sslConfig when the IDE platform is unavailable")
    }

    @Test
    fun `applyTo is a no-op that returns the same builder when trust is unavailable`() {
        val builder = OkHttpClient.Builder()
        val returned = assertDoesNotThrow { IdeTrust.applyTo(builder) }
        // No SSL config to apply → caller's builder is handed straight back, unmodified.
        assertSame(builder, returned)
        // And the builder must still produce a working client.
        assertDoesNotThrow { returned.build() }
    }
}
