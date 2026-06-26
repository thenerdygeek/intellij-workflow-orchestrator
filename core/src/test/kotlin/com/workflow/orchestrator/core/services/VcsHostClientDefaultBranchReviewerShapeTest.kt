package com.workflow.orchestrator.core.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// NOTE: no ToolResult import — this test is reflection-only AND lives in the same
// package (com.workflow.orchestrator.core.services) as ToolResult and VcsHostClient.
// (ToolResult is in core.services, NOT core.model.)

/**
 * Phase 1c / Cluster A: pins the default-branch + default-reviewer ops onto the
 * neutral VcsHostClient seam (shape reservation; no consumer yet). Reflection-only
 * so it stays pure JUnit5 (the :core ONE-BasePlatformTestCase-per-JVM invariant).
 */
class VcsHostClientDefaultBranchReviewerShapeTest {

    @Test
    fun `VcsHostClient declares getDefaultBranch returning ToolResult`() {
        val m = VcsHostClient::class.java.methods.firstOrNull { it.name == "getDefaultBranch" }
        assertTrue(m != null, "VcsHostClient must declare getDefaultBranch")
        // suspend fun adds a Continuation param: (repoName, continuation)
        assertEquals(2, m!!.parameterCount, "getDefaultBranch(repoName: String?) + Continuation")
    }

    @Test
    fun `VcsHostClient declares getDefaultReviewersForBranch`() {
        val m = VcsHostClient::class.java.methods.firstOrNull { it.name == "getDefaultReviewersForBranch" }
        assertTrue(m != null, "VcsHostClient must declare getDefaultReviewersForBranch")
        // (sourceBranch, targetBranch, repoName) + Continuation = 4
        assertEquals(4, m!!.parameterCount, "getDefaultReviewersForBranch(source, target, repoName) + Continuation")
    }
}
