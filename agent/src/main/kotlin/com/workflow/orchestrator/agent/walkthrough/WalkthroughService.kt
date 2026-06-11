package com.workflow.orchestrator.agent.walkthrough

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.ui.AgentControllerRegistry

/** Agent-facing surface, implemented by [WalkthroughService]; the tool depends on this seam. */
interface WalkthroughServiceApi {
    fun startTour(title: String?, steps: List<WalkthroughStep>): WalkthroughFeedback
    fun appendSteps(steps: List<WalkthroughStep>): WalkthroughFeedback
    fun finishTour(): WalkthroughFeedback
    fun deliverAnswer(bodyMarkdown: String): WalkthroughFeedback
}

data class WalkthroughFeedback(val ok: Boolean, val message: String)

/**
 * Project-level owner of the single active tour. EDT-confined: the tool hops via
 * Dispatchers.EDT before calling; popup clicks arrive on EDT. The owning session is
 * implicit — AgentController ends the tour on newChat/showSession, so a live tour
 * always belongs to the currently shown interactive session.
 */
@Service(Service.Level.PROJECT)
class WalkthroughService(private val project: Project) : WalkthroughServiceApi, Disposable {

    /** Two AgentController touchpoints, abstracted for headless tests. */
    interface ControllerGateway {
        fun submitUserTurn(modelText: String, displayText: String)
        fun isChatAwaitingUserReply(): Boolean
    }

    internal val machine = WalkthroughStateMachine()
    internal var uiFactory: (Project, WalkthroughService) -> WalkthroughUi =
        { p, s -> WalkthroughUiImpl(p, s) }
    internal var controllerGateway: ControllerGateway = object : ControllerGateway {
        override fun submitUserTurn(modelText: String, displayText: String) {
            AgentControllerRegistry.getInstance(project).controller
                ?.executeTask(modelText, displayText = displayText)
        }
        override fun isChatAwaitingUserReply(): Boolean = false // Task 10 wires the real query
    }
    private var ui: WalkthroughUi? = null

    // ── Agent side (via WalkthroughTool, on EDT) ─────────────────────────────

    override fun startTour(title: String?, steps: List<WalkthroughStep>): WalkthroughFeedback {
        if (machine.isActive) {
            return WalkthroughFeedback(
                false,
                "A walkthrough is already active — append to it, call action=finish, " +
                    "or wait for the user to end it before starting a new one."
            )
        }
        machine.start(title, steps)
        ui = uiFactory(project, this)
        showCurrent()
        return WalkthroughFeedback(true, machine.toolStatusLine())
    }

    override fun appendSteps(steps: List<WalkthroughStep>): WalkthroughFeedback {
        notActive()?.let { return it }
        val advancedTo = machine.append(steps)
        if (advancedTo != null) showCurrent() else ui?.updateCounter(machine.counterText())
        return WalkthroughFeedback(true, machine.toolStatusLine())
    }

    override fun finishTour(): WalkthroughFeedback {
        notActive()?.let { return it }
        machine.finish()
        showCurrent()
        return WalkthroughFeedback(true, machine.toolStatusLine())
    }

    override fun deliverAnswer(bodyMarkdown: String): WalkthroughFeedback {
        notActive()?.let { return it }
        if (!machine.answerDelivered()) {
            return WalkthroughFeedback(false, "No pending walkthrough question — the user has not asked anything.")
        }
        ui?.showAnswer(bodyMarkdown)
        return WalkthroughFeedback(true, machine.toolStatusLine())
    }

    // ── User side (popup callbacks, already on EDT) ──────────────────────────

    fun onNext() {
        if (!machine.isActive) return
        when (machine.next()) {
            is WalkthroughStateMachine.NextOutcome.Advanced -> showCurrent()
            WalkthroughStateMachine.NextOutcome.AwaitingMore -> {
                if (machine.generationPaused) {
                    ui?.showPaused(machine.counterText())
                } else {
                    ui?.showLoading(machine.counterText())
                }
            }
            WalkthroughStateMachine.NextOutcome.TourFinished -> endTour(byUser = true)
        }
    }

    fun onBack() {
        if (!machine.isActive) return
        if (machine.back() != null) showCurrent()
    }

    fun endTour(byUser: Boolean) {
        if (!machine.isActive) return
        machine.end(byUser)
        ui?.dispose()
        ui = null
    }

    fun canAsk(): Boolean =
        machine.isActive && machine.pendingQuestion == null && !controllerGateway.isChatAwaitingUserReply()

    /** @return false when gated (question already pending / no active tour). */
    fun submitQuestion(question: String): Boolean {
        val step = machine.currentStep() ?: return false
        if (!machine.askQuestion(question)) return false
        val envelope = QuestionEnvelope.format(machine.cursor + 1, step, question)
        controllerGateway.submitUserTurn(envelope, question)
        ui?.showAnswering(question)
        return true
    }

    // ── Lifecycle hooks (AgentController, on EDT) ────────────────────────────

    /** Run teardown: Completed/Failed/Cancelled/SessionHandoff all funnel here. Idempotent. */
    fun markGenerationEnded() {
        if (!machine.isActive) return
        val hadQuestion = machine.markGenerationEnded()
        if (hadQuestion) ui?.showAnswerFallbackNote()
        showCurrent() // resolves a parked loading spinner; counter drops the "+"
    }

    /** Loop parked on / released from userInputChannel (plan dialogue etc.). */
    fun setGenerationPaused(paused: Boolean) {
        if (!machine.isActive) return
        machine.setGenerationPaused(paused)
        if (machine.pendingNext) {
            if (paused && machine.generationPaused) {
                ui?.showPaused(machine.counterText())
            } else if (!paused) {
                ui?.showLoading(machine.counterText())
            }
        }
    }

    override fun dispose() {
        endTour(byUser = false)
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun showCurrent() {
        val step = machine.currentStep() ?: return
        ui?.showStep(
            step = step,
            counter = machine.counterText(),
            nextIsDone = machine.isOnLastStep() && machine.status == WalkthroughStateMachine.Status.COMPLETE,
            backEnabled = machine.cursor > 0,
        )
    }

    private fun notActive(): WalkthroughFeedback? = when {
        machine.isActive -> null
        machine.endedByUser -> WalkthroughFeedback(
            ok = false,
            message = "The user ended the walkthrough — stop appending steps and continue without the tour.",
        )
        else -> WalkthroughFeedback(false, "No active walkthrough — call action=start first.")
    }

    private companion object {
        @Suppress("unused")
        val LOG = Logger.getInstance(WalkthroughService::class.java)
    }
}
