package com.workflow.orchestrator.handover.ui.chips

import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.handover.model.HandoverPlaceholderValue
import com.workflow.orchestrator.handover.model.HandoverTemplateAction
import com.workflow.orchestrator.handover.service.HandoverPlaceholderResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QuickValueChipsPanelTest {

    private val resolver: HandoverPlaceholderResolver = mockk()
    private val eventBus: EventBus = mockk(relaxed = true)
    private val keys = listOf("docker.tag", "ticket.id")

    private fun newPanel(scope: kotlinx.coroutines.CoroutineScope) = QuickValueChipsPanel.forTest(
        resolver = resolver,
        eventBus = eventBus,
        chipKeys = { keys },
        cs = scope,
    )

    @Test
    fun `chips render one per key in settings`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { resolver.resolve("docker.tag", any()) } returns HandoverPlaceholderValue.available("orch-api:rc1")
        coEvery { resolver.resolve("ticket.id", any()) } returns HandoverPlaceholderValue.available("AFTER8TE-912")

        val panel = newPanel(this)
        panel.refresh()
        advanceUntilIdle()

        val chipKeys = panel.testGetChipKeys()
        assertEquals(listOf("docker.tag", "ticket.id"), chipKeys)
    }

    @Test
    fun `clicking a chip emits HandoverChipCopied with the chip key`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { resolver.resolve(any(), any()) } returns HandoverPlaceholderValue.available("v")

        val panel = newPanel(this)
        panel.refresh()
        advanceUntilIdle()

        panel.testClickChip("ticket.id")
        advanceUntilIdle()

        coVerify(timeout = 200) {
            eventBus.emit(match<WorkflowEvent.HandoverChipCopied> { it.chipKey == "ticket.id" })
        }
    }

    @Test
    fun `unavailable values are still rendered as chips with em-dash placeholder`() = runTest(UnconfinedTestDispatcher()) {
        coEvery { resolver.resolve(any(), any()) } returns HandoverPlaceholderValue.unavailable("missing")

        val panel = newPanel(this)
        panel.refresh()
        advanceUntilIdle()

        val labels = panel.testGetChipValueLabels()
        assertTrue(labels.all { it == "—" }, "expected em-dash for unavailable, got: $labels")
    }
}
