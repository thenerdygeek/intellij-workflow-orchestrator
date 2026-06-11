package com.workflow.orchestrator.agent.walkthrough

/**
 * UI seam between [WalkthroughService] and the Swing layer, so the service is
 * testable with a recording fake. Real impl: WalkthroughUiImpl (navigator + popup).
 * All methods are invoked on the EDT.
 */
interface WalkthroughUi {
    /** Open/scroll/highlight the step's range and render its body in the callout. */
    fun showStep(step: WalkthroughStep, counter: String, nextIsDone: Boolean, backEnabled: Boolean)

    /** User outran the queue: spinner + "Writing next step…". */
    fun showLoading(counter: String)

    /** Loop parked awaiting chat input: "Agent is waiting for your input in chat ↗". */
    fun showPaused(counter: String)

    /** Question submitted: disable Ask, show "Answering…" under the question. */
    fun showAnswering(question: String)

    /** action=answer arrived: render the markdown under the question, re-enable Ask. */
    fun showAnswer(bodyMarkdown: String)

    /** Run ended with a question pending: "The agent may have answered in the chat panel ↗". */
    fun showAnswerFallbackNote()

    /** Queue grew or completed while the user reads: refresh the counter only. */
    fun updateCounter(counter: String)

    /** Tour over: close popup, remove highlight. Must be idempotent. */
    fun dispose()
}
