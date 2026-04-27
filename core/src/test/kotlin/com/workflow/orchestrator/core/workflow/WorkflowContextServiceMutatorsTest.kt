package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WorkflowContextServiceMutatorsTest {

    @AfterEach fun teardown() = unmockkObject(OpenPrLister.Companion)

    private fun makeService(project: Project, prList: List<PrRef> = emptyList()): WorkflowContextService {
        mockkObject(OpenPrLister.Companion)
        every { OpenPrLister.getInstance() } returns object : OpenPrLister {
            override fun listOpenPrs(project: Project): List<PrRef> = prList
        }
        return WorkflowContextService(project, TestScope())
    }

    @Test fun `setActiveTicket persists to settings before any suspend point`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.state.activeTicketId } returns null

        val service = makeService(project)
        service.setActiveTicket(TicketRef("AFTER8TE-912", "Fix login"))

        verify { settings.state.activeTicketId = "AFTER8TE-912" }
        verify { settings.state.activeTicketSummary = "Fix login" }
    }

    @Test fun `setActiveTicket emits new state with activeTicket populated`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.state.activeTicketId } returns null

        val service = makeService(project)
        val ticket = TicketRef("AFTER8TE-912", "Fix login")
        service.setActiveTicket(ticket)
        assertEquals(ticket, service.state.value.activeTicket)
    }

    @Test fun `setActiveTicket auto-seeds focusPr when matching open PR exists`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.state.activeTicketId } returns null
        val matchingPr = PrRef(42, "feat/AFTER8TE-912-fix", "main", "repo", null, null)

        val service = makeService(project, prList = listOf(matchingPr))
        service.setActiveTicket(TicketRef("AFTER8TE-912", "Fix login"))

        assertEquals(matchingPr, service.state.value.focusPr)
    }

    @Test fun `setActiveTicket null clears anchor`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.state.activeTicketId } returns null

        val service = makeService(project)
        service.setActiveTicket(TicketRef("AFTER8TE-912", "Fix login"))
        service.setActiveTicket(null)

        assertNull(service.state.value.activeTicket)
        verify { settings.state.activeTicketId = "" }
    }

    @Test fun `reconcileFocusPrWithRepos clears focusPr when its repo no longer configured`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.state.activeTicketId } returns null
        every { settings.getRepos() } returns emptyList()

        val pr = PrRef(42, "feat/AFTER8TE-912-fix", "main", "R1", null, null)
        val service = makeService(project)
        service.focusPr(pr)
        assertEquals(pr, service.state.value.focusPr)

        every { settings.getRepos() } returns listOf(RepoConfig().apply { name = "R2" })
        service.reconcileFocusPrWithRepos()

        assertNull(service.state.value.focusPr)
        assertNull(service.state.value.focusBuild)
        assertNull(service.state.value.focusQualityScope)
    }

    @Test fun `reconcileFocusPrWithRepos keeps focusPr when its repo still configured`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.state.activeTicketId } returns null
        every { settings.getRepos() } returns emptyList()

        val pr = PrRef(42, "feat/AFTER8TE-912-fix", "main", "R1", null, null)
        val service = makeService(project)
        service.focusPr(pr)

        every { settings.getRepos() } returns listOf(
            RepoConfig().apply { name = "R1" },
            RepoConfig().apply { name = "R2" },
        )
        service.reconcileFocusPrWithRepos()

        assertEquals(pr, service.state.value.focusPr)
    }
}
