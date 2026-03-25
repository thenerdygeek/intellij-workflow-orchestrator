package com.workflow.orchestrator.core.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DefaultBranchResolverTest {

    @Test
    fun `parseOverrides returns map from valid JSON`() {
        val json = """{"repo||feature/ABC":"develop","repo||bugfix/XYZ":"main"}"""
        val result = DefaultBranchResolver.parseOverrides(json)
        assertEquals("develop", result["repo||feature/ABC"])
        assertEquals("main", result["repo||bugfix/XYZ"])
    }

    @Test
    fun `parseOverrides returns empty map for blank string`() {
        assertTrue(DefaultBranchResolver.parseOverrides("").isEmpty())
        assertTrue(DefaultBranchResolver.parseOverrides("  ").isEmpty())
    }

    @Test
    fun `parseOverrides returns empty map for malformed JSON`() {
        assertTrue(DefaultBranchResolver.parseOverrides("{invalid").isEmpty())
        assertTrue(DefaultBranchResolver.parseOverrides("null").isEmpty())
    }

    @Test
    fun `serializeOverrides produces valid JSON`() {
        val map = mapOf("repo||branch" to "develop")
        val json = DefaultBranchResolver.serializeOverrides(map)
        assertTrue(json.contains("repo||branch"))
        assertTrue(json.contains("develop"))
        val parsed = DefaultBranchResolver.parseOverrides(json)
        assertEquals("develop", parsed["repo||branch"])
    }

    @Test
    fun `buildOverrideKey uses double-pipe separator`() {
        val key = DefaultBranchResolver.buildOverrideKey("/path/to/repo", "feature/ABC")
        assertEquals("/path/to/repo||feature/ABC", key)
    }

    @Test
    fun `buildOverrideKey handles Windows paths with colon`() {
        val key = DefaultBranchResolver.buildOverrideKey("C:\\Users\\dev\\repo", "main")
        assertEquals("C:\\Users\\dev\\repo||main", key)
    }

    @Test
    fun `orderCandidates prioritises branches targeting originHead`() {
        data class PrBranch(val from: String, val to: String)
        val prs = listOf(
            PrBranch("feature/A", "develop"),
            PrBranch("feature/B", "release/1.0"),
            PrBranch("feature/C", "develop"),
            PrBranch("hotfix/D", "main")
        )
        val currentBranch = "feature/X"
        val originHead = "develop"

        val allBranches = prs.flatMap { listOf(it.from, it.to) }
            .filter { it != currentBranch }
            .distinct()

        val (prioritised, others) = allBranches.partition { branch ->
            prs.any { it.from == branch && it.to == originHead }
        }

        assertTrue(prioritised.contains("feature/A"))
        assertTrue(prioritised.contains("feature/C"))
        assertTrue(others.contains("release/1.0"))
    }
}
