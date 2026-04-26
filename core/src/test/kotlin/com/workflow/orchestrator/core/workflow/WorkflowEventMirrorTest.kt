package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorkflowEventMirrorTest {

    @AfterEach fun teardown() {
        unmockkObject(LatestBuildLookup.Companion)
        unmockkObject(OpenPrLister.Companion)
    }

    private fun setup(scheduler: TestCoroutineScheduler): Triple<Project, EventBus, WorkflowContextService> {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { project.getService(PluginSettings::class.java) } returns settings
        every { settings.state.activeTicketId } returns null

        val bus = EventBus()
        every { project.getService(EventBus::class.java) } returns bus

        mockkObject(LatestBuildLookup.Companion)
        every { LatestBuildLookup.getInstance() } returns null

        mockkObject(OpenPrLister.Companion)
        every { OpenPrLister.getInstance() } returns null

        val service = WorkflowContextService(project, TestScope(scheduler))
        WorkflowEventMirror(project, service).install()
        return Triple(project, bus, service)
    }

    @Test fun `mirror translates PrSelected into focusPr`() = runTest {
        val (_, bus, service) = setup(testScheduler)
        testScheduler.runCurrent()
        bus.emit(WorkflowEvent.PrSelected(42, "feat/abc", "main", "repo", "PLAN", "SONAR"))
        delay(100)
        val focus = service.state.value.focusPr
        assertNotNull(focus)
        assertEquals(42, focus!!.prId)
    }

    @Test fun `mirror no-ops on duplicate event (state-equality guard)`() = runTest {
        val (_, bus, service) = setup(testScheduler)
        testScheduler.runCurrent()
        val event = WorkflowEvent.PrSelected(42, "a", "main", "r", null, null)
        bus.emit(event); delay(100)
        val firstWrite = service.state.value
        bus.emit(event); delay(100)
        val secondWrite = service.state.value
        assertSame(firstWrite, secondWrite)
    }

    @Test fun `mirror translates TicketChanged into setActiveTicket`() = runTest {
        val (_, bus, service) = setup(testScheduler)
        testScheduler.runCurrent()
        bus.emit(WorkflowEvent.TicketChanged("AFTER8TE-912", "Fix login"))
        delay(100)
        assertEquals("AFTER8TE-912", service.state.value.activeTicket?.key)
    }

    @Test fun `mirror processes events in FIFO order — last write wins`() = runTest {
        val (_, bus, service) = setup(testScheduler)
        testScheduler.runCurrent()
        listOf(
            WorkflowEvent.PrSelected(42, "a", "m", "r", null, null),
            WorkflowEvent.PrSelected(43, "b", "m", "r", null, null),
            WorkflowEvent.PrSelected(44, "c", "m", "r", null, null),
        ).forEach { bus.emit(it) }
        delay(500)
        assertEquals(44, service.state.value.focusPr?.prId)
    }

    @Test fun `mirror does not loop on migrated-panel re-emit (spec §5_3)`() = runTest {
        val (_, bus, service) = setup(testScheduler)
        testScheduler.runCurrent()
        val pr = PrRef(42, "feat/abc", "main", "repo", null, null)

        // 1. Migrated panel path: direct mutator call.
        service.focusPr(pr)
        val afterMutator = service.state.value

        // 2. Same panel re-emits the legacy event for unmigrated subscribers.
        bus.emit(WorkflowEvent.PrSelected(42, "feat/abc", "main", "repo", null, null))
        delay(100)
        val afterReEmit = service.state.value

        // Equality guard prevents the mirror from re-running the cascade.
        assertSame(afterMutator, afterReEmit)
        assertEquals(42, afterReEmit.focusPr?.prId)
    }
}
