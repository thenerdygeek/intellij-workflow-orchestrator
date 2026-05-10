package com.workflow.orchestrator.pullrequest.ui

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
import org.junit.jupiter.api.Test

/**
 * Phase 5 T12 cross-tab characterization test: a PR row-click handler invokes
 * `service.focusPr(...)` which emits a single new [com.workflow.orchestrator.core.model.workflow.WorkflowContext]
 * snapshot. Every subscriber (Build, Quality, ActiveTicketBar) reads from the same flow,
 * so a row click propagates to all tabs in one tick.
 *
 * The test exercises the service directly — `PrDashboardPanel` construction needs the
 * IntelliJ test platform (overkill here). Mirrors the structural pattern locked down in
 * T10's `BuildDashboardPanelCoherenceTest`. See spec §4.4 / §5.1 / §9.2.
 */
class PrDashboardCrossTabTest {

    @AfterEach fun teardown() {
        unmockkObject(LatestBuildLookup.Companion)
        unmockkObject(ChainKeyResolver.Companion)
        unmockkObject(OpenPrLister.Companion)
    }

    @Test fun `row click propagates to service state focusPr`() = runTest {
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
        val pr = PrRef(42, "feat/abc", "main", "repo", null, null)

        // Direct service call — the panel's row-click handler delegates here.
        service.focusPr(pr)

        assertEquals(pr, service.state.value.focusPr)
    }
}
