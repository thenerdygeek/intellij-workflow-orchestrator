package com.workflow.orchestrator.pullrequest.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.services.VcsHostClient
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Phase 0b-2: BitbucketServiceImpl satisfies the neutral VcsHostClient seam. Because every
 * VcsHostClient method has a JVM signature identical to its BitbucketService counterpart (only
 * param names differ + a VcsUserData typealias), the existing overrides conform with no new code —
 * so the compile-time IS-A relationship is the real guarantee, asserted here at runtime.
 */
class BitbucketServiceImplVcsHostClientTest {

    @Test fun `BitbucketServiceImpl is a VcsHostClient`() {
        val impl = BitbucketServiceImpl(mockk<Project>(relaxed = true))
        val vcs: VcsHostClient = impl
        assertEquals(true, vcs is BitbucketServiceImpl)
    }
}
