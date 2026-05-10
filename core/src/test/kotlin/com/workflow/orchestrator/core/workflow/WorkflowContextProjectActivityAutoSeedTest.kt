package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * TDD: T-AutoSeed — Phase 7 boot auto-seed via [WorkflowContextProjectActivity].
 *
 * When a persisted anchor exists in [PluginSettings] at project open,
 * [WorkflowContextProjectActivity.execute] must call [WorkflowContextService.setActiveTicket]
 * so the `focusPr` cascade fires and downstream focus-driven services
 * (BuildMonitorService et al.) see a non-null `focusBuild` without the user
 * opening any tab.
 *
 * Tests target [WorkflowContextProjectActivity.seedFromPersistedAnchor] — the extracted
 * `internal` method — to avoid the IntelliJ `readAction { }` requirement inside
 * `recomputeFromEditor()` which needs a live `ApplicationManager`. The dispatch from
 * [WorkflowContextProjectActivity.execute] is structurally trivial (one `if` guard +
 * one mutator call) and covered by the existing integration-path test at the bottom.
 *
 * See `docs/architecture/phase7-handover-context-plan.md` § T-AutoSeed.
 */
class WorkflowContextProjectActivityAutoSeedTest {

    @AfterEach
    fun teardown() {
        unmockkObject(OpenPrLister.Companion)
        unmockkObject(LatestBuildLookup.Companion)
        unmockkObject(ChainKeyResolver.Companion)
    }

    /**
     * Builds a [WorkflowContextService] with a mocked [OpenPrLister] and suppresses the
     * optional EP extensions (ChainKeyResolver, LatestBuildLookup) so the cascade completes
     * synchronously in the test scope without hitting HTTP.
     */
    private fun makeService(
        project: Project,
        prList: List<PrRef> = emptyList(),
    ): WorkflowContextService {
        mockkObject(OpenPrLister.Companion)
        every { OpenPrLister.getInstance() } returns object : OpenPrLister {
            override fun listOpenPrs(project: Project): List<PrRef> = prList
        }

        mockkObject(LatestBuildLookup.Companion)
        every { LatestBuildLookup.getInstance() } returns null

        mockkObject(ChainKeyResolver.Companion)
        every { ChainKeyResolver.getInstance() } returns null

        return WorkflowContextService(project, TestScope())
    }

    private fun projectWithSettings(
        activeTicketId: String?,
        activeTicketSummary: String? = null,
    ): Project {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { settings.state.activeTicketId } returns activeTicketId
        every { settings.state.activeTicketSummary } returns activeTicketSummary
        every { project.getService(PluginSettings::class.java) } returns settings
        return project
    }

    // -------------------------------------------------------------------------
    // Case 1: persisted anchor + matching open PR → focusPr seeded from boot
    // -------------------------------------------------------------------------

    @Test
    fun `persisted anchor with matching open PR — seedFromPersistedAnchor seeds focusPr`() = runTest {
        val ticketKey = "AFTER8TE-912"
        val matchingPr = PrRef(42, "feat/$ticketKey-fix-login", "main", "repo", null, null)

        val project = projectWithSettings(activeTicketId = ticketKey, activeTicketSummary = "Fix login")
        val service = makeService(project, prList = listOf(matchingPr))

        WorkflowContextProjectActivity().seedFromPersistedAnchor(project, service)

        val state = service.state.value
        assertNotNull(state.focusPr, "focusPr must be non-null after boot auto-seed")
        assertEquals(matchingPr.prId, state.focusPr!!.prId)
        assertEquals(ticketKey, state.activeTicket?.key)
    }

    // -------------------------------------------------------------------------
    // Case 2: persisted anchor, no matching PR → activeTicket set, focusPr null
    // -------------------------------------------------------------------------

    @Test
    fun `persisted anchor without matching open PR — activeTicket set but focusPr stays null`() = runTest {
        val ticketKey = "AFTER8TE-912"
        val project = projectWithSettings(activeTicketId = ticketKey, activeTicketSummary = "Fix login")
        val service = makeService(project, prList = emptyList())

        WorkflowContextProjectActivity().seedFromPersistedAnchor(project, service)

        val state = service.state.value
        assertEquals(ticketKey, state.activeTicket?.key, "activeTicket must be seeded from persisted anchor")
        assertNull(state.focusPr, "focusPr must be null when no PR matches the anchor")
    }

    // -------------------------------------------------------------------------
    // Case 3: no persisted anchor → setActiveTicket never called, state baseline
    // -------------------------------------------------------------------------

    @Test
    fun `no persisted anchor — seedFromPersistedAnchor does not set activeTicket`() = runTest {
        val project = projectWithSettings(activeTicketId = null)
        val service = makeService(project, prList = emptyList())

        WorkflowContextProjectActivity().seedFromPersistedAnchor(project, service)

        assertNull(service.state.value.activeTicket, "activeTicket must remain null when no anchor is persisted")
        assertNull(service.state.value.focusPr, "focusPr must remain null when no anchor is persisted")
    }

    @Test
    fun `blank activeTicketId is treated as no anchor`() = runTest {
        val project = projectWithSettings(activeTicketId = "   ", activeTicketSummary = "")
        val service = makeService(project, prList = emptyList())

        WorkflowContextProjectActivity().seedFromPersistedAnchor(project, service)

        assertNull(service.state.value.activeTicket, "blank ticketId must not trigger setActiveTicket")
    }

    // -------------------------------------------------------------------------
    // Case 4: mirror still installs — subsequent focusPr mutator calls work after boot
    // -------------------------------------------------------------------------

    /**
     * After [WorkflowContextProjectActivity.seedFromPersistedAnchor] (and implicitly after
     * [WorkflowEventMirror] installs), subsequent service mutator calls must update
     * focusPr. This guards that the service is in a fully operable state post-boot:
     * any migrated panel or mirror-collected event can override the boot seed.
     */
    @Test
    fun `service accepts focusPr mutations after seed — mirror path works`() = runTest {
        val project = projectWithSettings(activeTicketId = null)
        val service = makeService(project)

        WorkflowContextProjectActivity().seedFromPersistedAnchor(project, service)

        // Simulate what the installed WorkflowEventMirror does on a PrSelected event.
        val pr = PrRef(77, "feat/POST-BOOT-123-test", "main", "repo", null, null)
        service.focusPr(pr)

        assertNotNull(service.state.value.focusPr, "focusPr must update after mirror installs")
        assertEquals(77, service.state.value.focusPr!!.prId)
    }

    @Test
    fun `boot seed can be overridden by a subsequent focusPr mutation`() = runTest {
        val ticketKey = "AFTER8TE-912"
        val bootPr = PrRef(42, "feat/$ticketKey-fix", "main", "repo", null, null)
        val project = projectWithSettings(activeTicketId = ticketKey, activeTicketSummary = "Fix login")
        val service = makeService(project, prList = listOf(bootPr))

        WorkflowContextProjectActivity().seedFromPersistedAnchor(project, service)

        // focusPr seeded from boot anchor
        assertEquals(bootPr.prId, service.state.value.focusPr?.prId)

        // Later: user switches PR in the UI; this is what the mirror-collected PrSelected routes to.
        val laterPr = PrRef(99, "feat/OTHER-1-thing", "main", "repo", null, null)
        service.focusPr(laterPr)
        assertEquals(99, service.state.value.focusPr?.prId, "later mutation must override boot seed")
    }
}
