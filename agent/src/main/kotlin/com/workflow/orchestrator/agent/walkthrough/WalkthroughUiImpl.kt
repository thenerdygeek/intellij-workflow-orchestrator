package com.workflow.orchestrator.agent.walkthrough

import com.intellij.openapi.project.Project

/** Real Swing implementation — filled in by the popup task; stub keeps the service compiling. */
@Suppress("UNUSED_PARAMETER", "EmptyFunctionBlock")
internal class WalkthroughUiImpl(
    project: Project, // plain params (not properties) so detekt's UnusedPrivateProperty stays quiet
    service: WalkthroughService,
) : WalkthroughUi {
    override fun showStep(step: WalkthroughStep, counter: String, nextIsDone: Boolean, backEnabled: Boolean) {}
    override fun showLoading(counter: String) {}
    override fun showPaused(counter: String) {}
    override fun showAnswering(question: String) {}
    override fun showAnswer(bodyMarkdown: String) {}
    override fun showAnswerFallbackNote() {}
    override fun updateCounter(counter: String) {}
    override fun dispose() {}
}
