package com.workflow.orchestrator.sonar.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [IssueDetailPanel.resolveRepoRoot] — the shared multi-repo
 * path resolver introduced for sonar:F-12 (buildCodeSnippet) and sonar:F-13
 * (navigateToItem hotspot/issue paths).
 *
 * The companion-object method is isolated from all IntelliJ platform APIs and
 * file-system I/O via the [fileExistsFn] lambda, so these run as plain JUnit 5.
 */
class IssueDetailPanelRepoRootResolverTest {

    /** Never-exists stub — forces tier-2 to always miss. */
    private val neverExists: (String, String) -> Boolean = { _, _ -> false }

    /** Always-exists stub — tier-2 always returns the first root. */
    private val alwaysExists: (String, String) -> Boolean = { _, _ -> true }

    // -----------------------------------------------------------------------
    // Tier 1: sonarProjectKey exact match
    // -----------------------------------------------------------------------

    @Test
    fun `tier1 — exact sonarProjectKey match returns that repo root`() {
        val result = IssueDetailPanel.resolveRepoRoot(
            filePath = "src/main/kotlin/Foo.kt",
            sonarProjectKey = "secondary-service",
            repoPairs = listOf("primary-service" to "/repos/primary", "secondary-service" to "/repos/secondary"),
            repoRoots = listOf("/repos/primary", "/repos/secondary"),
            projectBasePath = "/projects/aggregator",
            fileExistsFn = neverExists,
        )
        assertEquals("/repos/secondary", result)
    }

    @Test
    fun `tier1 — first matching pair wins when multiple repos share the same sonarProjectKey`() {
        val result = IssueDetailPanel.resolveRepoRoot(
            filePath = "src/Foo.kt",
            sonarProjectKey = "dup-key",
            repoPairs = listOf("dup-key" to "/repos/first", "dup-key" to "/repos/second"),
            repoRoots = listOf("/repos/first", "/repos/second"),
            projectBasePath = "/projects/aggregator",
            fileExistsFn = neverExists,
        )
        assertEquals("/repos/first", result)
    }

    // -----------------------------------------------------------------------
    // Tier 2: file-existence scan
    // -----------------------------------------------------------------------

    @Test
    fun `tier2 — no sonarProjectKey match but file exists under second root`() {
        // Simulate: /repos/repoA/src/Foo.kt does NOT exist; /repos/repoB/src/Foo.kt DOES
        val result = IssueDetailPanel.resolveRepoRoot(
            filePath = "src/Foo.kt",
            sonarProjectKey = "unknown-key",
            repoPairs = listOf("repoA-key" to "/repos/repoA"),
            repoRoots = listOf("/repos/repoA", "/repos/repoB"),
            projectBasePath = "/projects/aggregator",
            fileExistsFn = { root, _ -> root == "/repos/repoB" },
        )
        assertEquals("/repos/repoB", result)
    }

    @Test
    fun `tier2 — first matching root wins when file exists under multiple roots`() {
        val result = IssueDetailPanel.resolveRepoRoot(
            filePath = "src/Shared.kt",
            sonarProjectKey = "no-match",
            repoPairs = emptyList(),
            repoRoots = listOf("/repos/alpha", "/repos/beta"),
            projectBasePath = "/projects/aggregator",
            fileExistsFn = alwaysExists,
        )
        assertEquals("/repos/alpha", result)
    }

    // -----------------------------------------------------------------------
    // Tier 3: projectBasePath fallback
    // -----------------------------------------------------------------------

    @Test
    fun `tier3 — falls back to projectBasePath when no key match and file absent everywhere`() {
        val result = IssueDetailPanel.resolveRepoRoot(
            filePath = "src/Foo.kt",
            sonarProjectKey = "no-match",
            repoPairs = listOf("other-key" to "/repos/other"),
            repoRoots = listOf("/repos/other"),
            projectBasePath = "/projects/single",
            fileExistsFn = neverExists,
        )
        assertEquals("/projects/single", result)
    }

    @Test
    fun `tier3 — returns null when projectBasePath is null and all tiers miss`() {
        val result = IssueDetailPanel.resolveRepoRoot(
            filePath = "src/Foo.kt",
            sonarProjectKey = "no-match",
            repoPairs = emptyList(),
            repoRoots = emptyList(),
            projectBasePath = null,
            fileExistsFn = neverExists,
        )
        assertNull(result)
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    fun `single-repo project — no repos configured falls straight to projectBasePath`() {
        val result = IssueDetailPanel.resolveRepoRoot(
            filePath = "src/Foo.kt",
            sonarProjectKey = "proj",
            repoPairs = emptyList(),
            repoRoots = emptyList(),
            projectBasePath = "/projects/single",
            fileExistsFn = neverExists,
        )
        assertEquals("/projects/single", result)
    }

    @Test
    fun `hotspot component parsing — projectKey extracted correctly before resolution`() {
        // Simulate the hotspot branch: component = "project-key:src/Foo.kt"
        val component = "project-key:src/main/java/Foo.java"
        val filePath = component.substringAfterLast(':')
        val hotspotProjectKey = component.substringBeforeLast(':')

        assertEquals("src/main/java/Foo.java", filePath)
        assertEquals("project-key", hotspotProjectKey)

        val result = IssueDetailPanel.resolveRepoRoot(
            filePath = filePath,
            sonarProjectKey = hotspotProjectKey,
            repoPairs = listOf("project-key" to "/repos/project"),
            repoRoots = listOf("/repos/project"),
            projectBasePath = "/projects/aggregator",
            fileExistsFn = neverExists,
        )
        assertEquals("/repos/project", result)
    }
}
