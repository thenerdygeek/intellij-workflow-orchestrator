package com.workflow.orchestrator.sonar.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pins the C2 invalidation contract of the P1-18 per-file header cache:
 * - partially-resolved headers (null branch / blank projectKey) are never cached, so
 *   the startup race (git repo mappings not yet initialized) self-heals instead of
 *   permanently suppressing markers;
 * - the cache is cleared whenever SonarDataService.clearLineCoverageCache() runs,
 *   so branch/scope/repo-config changes cannot leave stale headers feeding the next
 *   coverage fetch.
 */
class CoverageFileHeaderCacheTest {

    @Test
    fun `header with null branch is never cached`() {
        assertFalse(CoverageFileHeaderCache.shouldCache("proj:key", null))
    }

    @Test
    fun `header with blank projectKey is never cached`() {
        assertFalse(CoverageFileHeaderCache.shouldCache("", "main"))
        assertFalse(CoverageFileHeaderCache.shouldCache("   ", "main"))
    }

    @Test
    fun `fully resolved header is cached`() {
        assertTrue(CoverageFileHeaderCache.shouldCache("proj:key", "main"))
    }

    @Test
    fun `clearKey drops all cached headers for the project`() {
        val key = 0x5EED
        CoverageFileHeaderCache.forKey(key)["/repo/src/A.kt"] =
            CoverageFileHeaderCache.Entry("src/A.kt", "proj:key", "main")
        CoverageFileHeaderCache.forKey(key)["/repo/src/B.kt"] =
            CoverageFileHeaderCache.Entry("src/B.kt", "proj:key", "main")

        CoverageFileHeaderCache.clearKey(key)

        assertTrue(CoverageFileHeaderCache.forKey(key).isEmpty())
    }

    @Test
    fun `clearKey scopes to one project and leaves other projects intact`() {
        val keyA = 0xA11CE
        val keyB = 0xB0B
        CoverageFileHeaderCache.forKey(keyA)["/a.kt"] = CoverageFileHeaderCache.Entry("a.kt", "p", "main")
        CoverageFileHeaderCache.forKey(keyB)["/b.kt"] = CoverageFileHeaderCache.Entry("b.kt", "p", "main")

        CoverageFileHeaderCache.clearKey(keyA)

        assertTrue(CoverageFileHeaderCache.forKey(keyA).isEmpty())
        assertTrue(CoverageFileHeaderCache.forKey(keyB).containsKey("/b.kt"))
        CoverageFileHeaderCache.clearKey(keyB)
    }

    /**
     * Source-text contract (SonarDataService is a @Service and not unit-instantiable):
     * clearLineCoverageCache() must clear the gutter header cache so every existing
     * call site (quality-scope flow, branch switch, scope reset) invalidates headers
     * together with line statuses.
     */
    @Test
    fun `clearLineCoverageCache clears the gutter header cache at every call site`() {
        val src = File(
            "src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt"
        ).readText()
        val body = src
            .substringAfter("fun clearLineCoverageCache()")
            .substringBefore("internal suspend fun refreshWith")
        assertTrue(
            body.contains("CoverageLineMarkerProvider.clearFileHeaderCaches(project)"),
            "clearLineCoverageCache must also drop CoverageFileHeaderCache entries (C2)"
        )
    }
}
