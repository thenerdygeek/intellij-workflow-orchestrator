package com.workflow.orchestrator.agent.walkthrough

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.ui.AgentControllerRegistry

/** Agent-facing surface, implemented by [WalkthroughService]; the tool depends on this seam. */
interface WalkthroughServiceApi {
    fun startTour(title: String?, steps: List<WalkthroughStep>): WalkthroughFeedback
    fun appendSteps(steps: List<WalkthroughStep>): WalkthroughFeedback
    fun finishTour(): WalkthroughFeedback
    fun cancelTour(): WalkthroughFeedback
    fun updateStep(index: Int, bodyMarkdown: String, append: Boolean): WalkthroughFeedback
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
        fun armWalkthroughQuestion(stepRef: String)
        fun focusChatInput()
    }

    internal val machine = WalkthroughStateMachine()
    internal var uiFactory: (Project, WalkthroughService) -> WalkthroughUi =
        { p, s -> WalkthroughUiImpl(p, s) }
    internal var controllerGateway: ControllerGateway = object : ControllerGateway {
        override fun armWalkthroughQuestion(stepRef: String) {
            AgentControllerRegistry.getInstance(project).controller
                ?.armWalkthroughQuestionContext(stepRef)
        }
        override fun focusChatInput() {
            AgentControllerRegistry.getInstance(project).controller
                ?.focusChatInputForWalkthrough()
        }
    }
    private var ui: WalkthroughUi? = null

    // ── Agent side (via WalkthroughTool, on EDT) ─────────────────────────────

    override fun startTour(title: String?, steps: List<WalkthroughStep>): WalkthroughFeedback {
        // Recovery-friendly: if a tour is somehow still active (e.g. the user never finished a
        // prior one, or a stuck UI), REPLACE it instead of refusing — so the agent can always
        // reset by calling start again. The old popup + highlight are torn down first.
        val replaced = machine.isActive
        if (replaced) endTour(byUser = false)
        machine.start(title, steps)
        ui = uiFactory(project, this)
        showCurrent()
        val note = if (replaced) " (replaced the previously active walkthrough)" else ""
        return WalkthroughFeedback(true, machine.toolStatusLine() + note)
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

    /**
     * Forcibly end the tour (the action=cancel escape hatch): tears down the popup + highlight and
     * moves the tour to ENDED so a fresh `start` works. Distinct from `finish` (which only marks
     * the queue done while the user keeps paging). Idempotent — safe on an already-ended tour.
     */
    override fun cancelTour(): WalkthroughFeedback {
        if (!machine.isActive) return WalkthroughFeedback(true, "No active walkthrough to cancel.")
        endTour(byUser = false)
        return WalkthroughFeedback(true, "Walkthrough cancelled and torn down — you can start a new one.")
    }

    /** Agent revises/enriches a step (the update_step tool action). Re-renders only if it's the shown step. */
    override fun updateStep(index: Int, bodyMarkdown: String, append: Boolean): WalkthroughFeedback {
        notActive()?.let { return it }
        val outcome = machine.updateStep(index, bodyMarkdown, append)
        if (!outcome.ok) return WalkthroughFeedback(false, outcome.message)
        if (outcome.isCurrent) showCurrent()
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

    /** Route the user's question to the main chat: arm step context + focus chat. @return false if no active tour. */
    fun askInChat(): Boolean {
        if (!machine.isActive) return false
        val step = machine.currentStep() ?: return false
        val ref = "${step.file}:${step.startLine}-${step.endLine}"
        controllerGateway.armWalkthroughQuestion(ref)
        controllerGateway.focusChatInput()
        ui?.showDiscussingInChat()
        return true
    }

    // ── Lifecycle hooks (AgentController, on EDT) ────────────────────────────

    /** Run teardown: Completed/Failed/Cancelled/SessionHandoff all funnel here. Idempotent. */
    fun markGenerationEnded() {
        if (!machine.isActive) return
        machine.markGenerationEnded()
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
}
