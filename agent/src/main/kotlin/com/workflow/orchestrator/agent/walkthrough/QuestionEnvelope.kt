package com.workflow.orchestrator.agent.walkthrough

/**
 * Wraps a user's tour question with step context for the LLM. The envelope is the
 * MODEL-facing text; the bare question is shown in chat via executeTask(displayText=…)
 * so this wrapper never renders raw in the transcript.
 */
object QuestionEnvelope {
    fun format(stepNumber: Int, step: WalkthroughStep, question: String): String =
        "[Walkthrough question about step $stepNumber — ${step.file}:${step.startLine}-${step.endLine}] " +
            "$question (Reply using the walkthrough tool with action=\"answer\".)"
}
