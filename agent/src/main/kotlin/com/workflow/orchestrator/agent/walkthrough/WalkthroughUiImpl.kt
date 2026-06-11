package com.workflow.orchestrator.agent.walkthrough

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.walkthrough.ui.WalkthroughCalloutPopup
import com.workflow.orchestrator.agent.walkthrough.ui.WalkthroughMarkdown

/** Real Swing implementation: navigator (editor side) + callout popup (explanation side). EDT-only. */
internal class WalkthroughUiImpl(
    private val project: Project,
    private val service: WalkthroughService,
) : WalkthroughUi {

    private val navigator = WalkthroughNavigator(project)
    private val popup = WalkthroughCalloutPopup(
        onNext = { service.onNext() },
        onBack = { service.onBack() },
        onClose = { service.endTour(byUser = true) },
        onAsk = { service.askInChat() },
    )

    override fun showStep(step: WalkthroughStep, counter: String, nextIsDone: Boolean, backEnabled: Boolean) {
        val editor = navigator.showStep(step)
        if (editor == null) {
            popup.renderMissingFile(counter, step.file, backEnabled)
            popup.showCenteredFallback(project) // step 1's file may be gone — box must still appear
            return
        }
        popup.renderStep(
            counter,
            step.title,
            WalkthroughMarkdown.toHtml(step.bodyMarkdown, project),
            nextIsDone,
            backEnabled,
        )
        popup.showAt(editor, anchorLine = step.endLine)
    }

    override fun showLoading(counter: String) = popup.renderLoading(counter)
    override fun showPaused(counter: String) = popup.renderPaused(counter)
    override fun showDiscussingInChat() = popup.showDiscussingInChat()
    override fun updateCounter(counter: String) = popup.updateCounter(counter)

    override fun dispose() {
        popup.dispose()
        navigator.clearHighlight()
    }
}
