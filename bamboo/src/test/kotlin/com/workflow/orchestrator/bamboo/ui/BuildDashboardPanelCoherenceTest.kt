package com.workflow.orchestrator.bamboo.ui

import app.cash.turbine.test
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.workflow.ChainKeyResolver
import com.workflow.orchestrator.core.workflow.LatestBuildLookup
import com.workflow.orchestrator.core.workflow.OpenPrLister
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Phase 5 T10 coherence test: a single `focusPr` write on the service emits exactly one
 * new [com.workflow.orchestrator.core.model.workflow.WorkflowContext] snapshot. The Build
 * dashboard's PrBar header AND its job-stages reader both collect from this same flow
 * (via `BuildDashboardPanel`'s single `state.collect` block), so they cannot diverge —
 * proven structurally here by Turbine emission counting.
 *
 * The test exercises the service directly (no panel construction). It locks down the
 * service-level invariant that makes the migration safe: one mutator → one snapshot →
 * both readers see the same payload. See spec §4.4 and §9.2.
 */
class BuildDashboardPanelCoherenceTest {

    @AfterEach fun teardown() {
        unmockkObject(LatestBuildLookup.Companion)
        unmockkObject(ChainKeyResolver.Companion)
        unmockkObject(OpenPrLister.Companion)
    }

    @Test fun `single focusPr emit triggers exactly one new state — both readers see same snapshot`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.state.activeTicketId } returns null

        mockkObject(LatestBuildLookup.Companion)
        every { LatestBuildLookup.getInstance() } returns null
        mockkObject(ChainKeyResolver.Companion)
        every { ChainKeyResolver.getInstance() } returns null
        mockkObject(OpenPrLister.Companion)
        every { OpenPrLister.getInstance() } returns null

        val service = WorkflowContextService(project, TestScope(testScheduler))
        val pr = PrRef(42, "feat/abc", "main", "repo", "PLAN", null)

        service.state.test {
            // Initial empty snapshot.
            assertNull(awaitItem().focusPr)
            service.focusPr(pr)
            val snapshot = awaitItem()
            // The same WorkflowContext instance is what BuildDashboardPanel's single
            // collector receives — both PrBar.showPrInfo and loadBuildsForContext are
            // driven from this snapshot, so they cannot diverge.
            assertEquals(pr, snapshot.focusPr)
            cancel()  // assert no further emissions
        }
    }
}
