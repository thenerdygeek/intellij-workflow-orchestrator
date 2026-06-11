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
        override fun showAnswering(question: String) { calls += "showAnswering:$question" }
        override fun showAnswer(bodyMarkdown: String) { calls += "showAnswer:$bodyMarkdown" }
        override fun showAnswerFallbackNote() { calls += "showAnswerFallbackNote" }
        override fun updateCounter(counter: String) { calls += "updateCounter:$counter" }
        override fun dispose() { calls += "dispose" }
    }

    private class FakeGateway : WalkthroughService.ControllerGateway {
        val submitted = mutableListOf<Pair<String, String>>()
        var chatAwaitingReply = false
        override fun submitUserTurn(modelText: String, displayText: String) { submitted += modelText to displayText }
        override fun isChatAwaitingUserReply(): Boolean = chatAwaitingReply
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
    fun `submitQuestion routes the envelope to the controller and shows answering state`() {
        service.startTour("T", listOf(step(1)))
        assertTrue(service.submitQuestion("Why?"))
        val (model, display) = gateway.submitted.single()
        assertTrue(model.startsWith("[Walkthrough question about step 1 — f1.kt:1-2] Why?"))
        assertEquals("Why?", display)
        assertEquals("showAnswering:Why?", ui.calls.last())
        assertFalse(service.submitQuestion("Second?")) // one at a time
    }

    @Test
    fun `deliverAnswer renders inline, errors when nothing pending`() {
        service.startTour("T", listOf(step(1)))
        assertFalse(service.deliverAnswer("orphan").ok)
        service.submitQuestion("Why?")
        assertTrue(service.deliverAnswer("Because.").ok)
        assertEquals("showAnswer:Because.", ui.calls.last())
    }

    @Test
    fun `canAsk respects wizard-pending gate and pending question`() {
        service.startTour("T", listOf(step(1)))
        assertTrue(service.canAsk())
        gateway.chatAwaitingReply = true
        assertFalse(service.canAsk())
        gateway.chatAwaitingReply = false
        service.submitQuestion("Why?")
        assertFalse(service.canAsk())
    }

    @Test
    fun `markGenerationEnded resolves loading, completes counter, and falls back pending question`() {
        service.startTour("T", listOf(step(1)))
        service.submitQuestion("Why?")
        service.onNext() // loading
        service.markGenerationEnded()
        assertTrue(ui.calls.contains("showAnswerFallbackNote"))
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
}
