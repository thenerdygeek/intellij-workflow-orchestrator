package com.workflow.orchestrator.agent.walkthrough

import com.intellij.openapi.project.Project
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WalkthroughServiceTest {

    private class FakeUi : WalkthroughUi {
        val calls = mutableListOf<String>()
        override fun showStep(step: WalkthroughStep, counter: String, nextIsDone: Boolean, backEnabled: Boolean) {
            calls += "showStep:${step.file}:$counter:done=$nextIsDone:back=$backEnabled"
        }
        override fun showLoading(counter: String) { calls += "showLoading:$counter" }
        override fun showPaused(counter: String) { calls += "showPaused:$counter" }
        override fun showDiscussingInChat() { calls += "showDiscussingInChat" }
        override fun updateCounter(counter: String) { calls += "updateCounter:$counter" }
        override fun dispose() { calls += "dispose" }
    }

    private class FakeGateway : WalkthroughService.ControllerGateway {
        val armed = mutableListOf<String>()
        var focused = 0
        override fun armWalkthroughQuestion(stepRef: String) { armed += stepRef }
        override fun focusChatInput() { focused++ }
    }

    private lateinit var ui: FakeUi
    private lateinit var gateway: FakeGateway
    private lateinit var service: WalkthroughService

    private fun step(n: Int) = WalkthroughStep("f$n.kt", n, n + 1, null, "body $n")

    @BeforeEach
    fun setUp() {
        ui = FakeUi()
        gateway = FakeGateway()
        service = WalkthroughService(mockk<Project>(relaxed = true))
        service.uiFactory = { _, _ -> ui }
        service.controllerGateway = gateway
    }

    @Test
    fun `startTour shows step 1 and double start errors without touching the live tour`() {
        assertTrue(service.startTour("T", listOf(step(1))).ok)
        assertEquals("showStep:f1.kt:Step 1 of 1+:done=false:back=false", ui.calls.single())
        val second = service.startTour("T2", listOf(step(9)))
        assertFalse(second.ok)
        assertTrue(second.message.contains("already active"))
    }

    @Test
    fun `append while user waits on loading auto-advances`() {
        service.startTour("T", listOf(step(1)))
        service.onNext() // queue end -> loading
        assertTrue(ui.calls.last().startsWith("showLoading"))
        service.appendSteps(listOf(step(2)))
        assertTrue(ui.calls.last().startsWith("showStep:f2.kt"))
    }

    @Test
    fun `finish then Next on last step ends the tour and disposes UI`() {
        service.startTour("T", listOf(step(1)))
        service.finishTour()
        service.onNext()
        assertEquals("dispose", ui.calls.last())
        assertFalse(service.machine.isActive)
    }

    @Test
    fun `append and finish after user ended the tour return the ended-by-user error`() {
        service.startTour("T", listOf(step(1)))
        service.endTour(byUser = true)
        val feedback = service.appendSteps(listOf(step(2)))
        assertFalse(feedback.ok)
        assertTrue(feedback.message.contains("ended the walkthrough"))
    }

    @Test
    fun `askInChat arms step context, focuses chat, and shows the discussing hint`() {
        service.startTour("T", listOf(step(1)))
        assertTrue(service.askInChat())
        assertEquals("f1.kt:1-2", gateway.armed.single())
        assertEquals(1, gateway.focused)
        assertEquals("showDiscussingInChat", ui.calls.last())
        // no active tour -> false, nothing armed
        service.endTour(byUser = true)
        assertFalse(service.askInChat())
        assertEquals(1, gateway.armed.size)
    }

    @Test
    fun `updateStep re-renders only when it hits the current step`() {
        service.startTour("T", listOf(step(1), step(2)))
        val before = ui.calls.size
        // not current (user is on step 1) -> no re-render
        assertTrue(service.updateStep(2, "note", append = true).ok)
        assertEquals(before, ui.calls.size)
        // current step -> re-render
        assertTrue(service.updateStep(1, "note", append = true).ok)
        assertTrue(ui.calls.last().startsWith("showStep:f1.kt"))
    }

    @Test
    fun `markGenerationEnded resolves loading and completes the counter`() {
        service.startTour("T", listOf(step(1)))
        service.onNext() // loading
        service.markGenerationEnded()
        assertTrue(ui.calls.last().startsWith("showStep:f1.kt:Step 1 of 1:done=true"))
    }

    @Test
    fun `setGenerationPaused while user is waiting switches loading to paused and back`() {
        service.startTour("T", listOf(step(1)))
        service.onNext()
        service.setGenerationPaused(true)
        assertTrue(ui.calls.last().startsWith("showPaused"))
        service.setGenerationPaused(false)
        assertTrue(ui.calls.last().startsWith("showLoading"))
    }

    @Test
    fun `onBack re-shows the previous step`() {
        service.startTour("T", listOf(step(1), step(2)))
        service.onNext()
        service.onBack()
        assertTrue(ui.calls.last().startsWith("showStep:f1.kt"))
    }

    @Test
    fun `updateStep on a non-existent index returns an error and does not re-render`() {
        service.startTour("T", listOf(step(1)))
        val before = ui.calls.size
        val feedback = service.updateStep(99, "x", append = true)
        assertFalse(feedback.ok)
        assertEquals(before, ui.calls.size)
    }

    @Test
    fun `askInChat and updateStep are inert after endTour`() {
        service.startTour("T", listOf(step(1)))
        service.endTour(byUser = true)
        assertFalse(service.askInChat())
        assertFalse(service.updateStep(1, "x", append = true).ok)
    }

    @Test
    fun `appendSteps mid-step refreshes counter only`() {
        service.startTour("T", listOf(step(1), step(2)))
        service.appendSteps(listOf(step(3)))
        assertTrue(ui.calls.last().startsWith("updateCounter"))
    }
}
