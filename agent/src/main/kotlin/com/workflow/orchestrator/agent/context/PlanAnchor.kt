package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.runtime.AgentPlan

/**
 * Builds and manages structured `<active_plan>` summaries that are injected
 * as system messages. Because system messages are never compressed by
 * [ContextManager], the plan summary survives context compression and keeps
 * the LLM aware of current plan status at all times.
 */
object PlanAnchor {
    private const val TAG_OPEN = "<active_plan>"
    private const val TAG_CLOSE = "</active_plan>"

    /**
     * Builds a concise, structured summary of the plan suitable for
     * inclusion as a system message. Uses status icons for quick scanning:
     * ✓ done, ◉ running, ✗ failed, ○ pending.
     */
    fun buildSummary(plan: AgentPlan): String {
        val doneCount = plan.steps.count { it.status == "done" }
        val totalCount = plan.steps.size
        val currentStep = plan.steps.firstOrNull { it.status == "running" }
        val statusLine = if (doneCount == totalCount) {
            "completed"
        } else {
            "executing ($doneCount/$totalCount steps complete)"
        }

        val progressLines = plan.steps.joinToString(" | ") { step ->
            val icon = when (step.status) {
                "done" -> "✓"
                "running" -> "◉"
                "failed" -> "✗"
                else -> "○"
            }
            "${step.id}.$icon ${step.title}"
        }

        val filesModified = plan.steps
            .filter { it.status == "done" }
            .flatMap { it.files }
            .distinct()
        val filesLine = if (filesModified.isNotEmpty()) {
            "Files modified: ${filesModified.joinToString(", ")}"
        } else {
            "Files modified: none yet"
        }

        val currentLine = when {
            currentStep != null -> "Current: Step ${currentStep.id} — ${currentStep.title}"
            doneCount == totalCount -> "Current: All steps complete"
            else -> "Current: Awaiting next step"
        }

        return buildString {
            appendLine(TAG_OPEN)
            appendLine("Goal: ${plan.goal}")
            appendLine("Status: $statusLine")
            appendLine("Progress: $progressLines")
            appendLine(currentLine)
            appendLine(filesLine)
            appendLine("Full plan: available on disk (plan.json in session directory)")
            append(TAG_CLOSE)
        }
    }

    /**
     * Finds the index of the plan anchor message within a list of message contents.
     * Returns -1 if no plan message exists.
     */
    fun findPlanMessageIndex(messageContents: List<String>): Int =
        messageContents.indexOfFirst { it.contains(TAG_OPEN) }

    /**
     * Checks whether a message content string is a plan anchor message.
     */
    fun isPlanMessage(content: String): Boolean = content.contains(TAG_OPEN)

    /**
     * Creates a [ChatMessage] with role "system" containing the plan summary.
     */
    fun createPlanMessage(plan: AgentPlan): ChatMessage =
        ChatMessage(role = "system", content = buildSummary(plan))
}
