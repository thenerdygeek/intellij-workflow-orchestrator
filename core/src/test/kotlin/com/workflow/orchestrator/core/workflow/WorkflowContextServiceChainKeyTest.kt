package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.BuildRef
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Phase A — verifies the `WorkflowContextService.focusPr` cascade:
 *  - calls [ChainKeyResolver] with `(parentPlanKey, branchName)` and stores the
 *    resolved chain key in [BuildRef.chainKey];
 *  - leaves `focusBuild = null` (NO master substitution) when the resolver returns null;
 *  - bounds the resolver call with a 5s `withTimeoutOrNull` so a stalled HTTP call
 *    can never wedge the cascade.
 */
class WorkflowContextServiceChainKeyTest {

    @AfterEach fun teardown() {
        unmockkObject(ChainKeyResolver.Companion)
        unmockkObject(LatestBuildLookup.Companion)
    }

    private fun stubSettings(project: Project) {
        val settings = mockk<PluginSettings>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.state.activeTicketId } returns null
    }

    @Test
    fun `cascade populates BuildRef chainKey when resolver returns chain key`() = runTest {
        val project = mockk<Project>(relaxed = true)
        stubSettings(project)

        mockkObject(ChainKeyResolver.Companion)
        val resolver = mockk<ChainKeyResolver>()
        coEvery {
            resolver.resolveChainKey(project, "PROJ-PLANKEY", "feature/PROJ-123-fix")
        } returns "PROJ-PLANKEY523"
        every { ChainKeyResolver.getInstance() } returns resolver

        mockkObject(LatestBuildLookup.Companion)
        val lookup = mockk<LatestBuildLookup>()
        // EP impl returns a BuildRef whose branch is blank — cascade overwrites with fromBranch.
        coEvery { lookup.fetchLatestBuild(project, "PROJ-PLANKEY523") } returns BuildRef(
            planKey = "PROJ-PLANKEY523",
            buildNumber = 99,
            branch = "",
            selectedJobKey = null,
            chainKey = "PROJ-PLANKEY523",
        )
        every { LatestBuildLookup.getInstance() } returns lookup

        val service = WorkflowContextService(project, TestScope())
        val pr = PrRef(
            prId = 7,
            fromBranch = "feature/PROJ-123-fix",
            toBranch = "develop",
            repoName = "repo-1",
            bambooPlanKey = "PROJ-PLANKEY",
            sonarProjectKey = null,
        )
        service.focusPr(pr)

        val build = service.state.value.focusBuild
        assertNotNull(build, "focusBuild should be populated after a successful cascade")
        assertEquals("PROJ-PLANKEY523", build!!.chainKey)
        assertEquals(99, build.buildNumber)
        // Cascade overwrites EP impl's blank branch with the PR's fromBranch.
        assertEquals("feature/PROJ-123-fix", build.branch)

        // Resolver was called with parent + branch (NOT the master plan key).
        coVerify(exactly = 1) {
            resolver.resolveChainKey(project, "PROJ-PLANKEY", "feature/PROJ-123-fix")
        }
        // LatestBuildLookup was called with the resolved chain key (single arg).
        coVerify(exactly = 1) { lookup.fetchLatestBuild(project, "PROJ-PLANKEY523") }
    }

    @Test
    fun `cascade leaves focusBuild null when ChainKeyResolver returns null - no master substitution`() = runTest {
        val project = mockk<Project>(relaxed = true)
        stubSettings(project)

        mockkObject(ChainKeyResolver.Companion)
        val resolver = mockk<ChainKeyResolver>()
        coEvery { resolver.resolveChainKey(any(), any(), any()) } returns null
        every { ChainKeyResolver.getInstance() } returns resolver

        mockkObject(LatestBuildLookup.Companion)
        val lookup = mockk<LatestBuildLookup>()
        every { LatestBuildLookup.getInstance() } returns lookup

        val service = WorkflowContextService(project, TestScope())
        val pr = PrRef(
            prId = 7,
            fromBranch = "feature/no-bamboo-branch",
            toBranch = "develop",
            repoName = "repo-1",
            bambooPlanKey = "PROJ-PLANKEY",
            sonarProjectKey = null,
        )
        service.focusPr(pr)

        // No focusBuild — and crucially, no fallback call to LatestBuildLookup with the
        // parent plan key (that would be the master-substitution bug Phase A unblocks).
        assertNull(
            service.state.value.focusBuild,
            "focusBuild must stay null when chain-key resolution fails; no master substitution.",
        )
        coVerify(exactly = 0) { lookup.fetchLatestBuild(any(), any()) }
    }

    @Test
    fun `cascade times out gracefully when resolver hangs - 5s bound`() = runTest {
        val project = mockk<Project>(relaxed = true)
        stubSettings(project)

        mockkObject(ChainKeyResolver.Companion)
        val resolver = mockk<ChainKeyResolver>()
        // Hang past the 5s `withTimeoutOrNull` bound — runTest's virtual scheduler advances
        // the clock without real-world waiting, so this still completes instantly.
        coEvery { resolver.resolveChainKey(any(), any(), any()) } coAnswers {
            delay(60_000) // 60s — well past the 5s bound
            "WOULD-HAVE-BEEN-CHAIN"
        }
        every { ChainKeyResolver.getInstance() } returns resolver

        mockkObject(LatestBuildLookup.Companion)
        val lookup = mockk<LatestBuildLookup>()
        every { LatestBuildLookup.getInstance() } returns lookup

        val service = WorkflowContextService(project, TestScope())
        val pr = PrRef(
            prId = 7,
            fromBranch = "feature/slow-bamboo",
            toBranch = "develop",
            repoName = "repo-1",
            bambooPlanKey = "PROJ-PLANKEY",
            sonarProjectKey = null,
        )
        service.focusPr(pr)

        // Timeout → null chainKey → no build lookup, focusBuild stays null.
        assertNull(service.state.value.focusBuild)
        coVerify(exactly = 0) { lookup.fetchLatestBuild(any(), any()) }
    }
}
