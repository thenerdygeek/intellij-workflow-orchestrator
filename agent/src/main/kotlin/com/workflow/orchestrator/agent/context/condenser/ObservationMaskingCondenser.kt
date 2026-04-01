package com.workflow.orchestrator.agent.context.condenser

import com.workflow.orchestrator.agent.context.events.*

/**
 * Cheaply reduces context by replacing old observations outside an attention window
 * with rich placeholders. Runs after SmartPruner in the pipeline.
 *
 * Matching OpenHands' ObservationMaskingCondenser design, enhanced with rich placeholders
 * that include tool name, content preview, and recovery hints to help the agent recover
 * masked content via tool calls.
 *
 * Key behaviors:
 * - [CondensationObservation] instances are NEVER masked (they contain critical summaries)
 * - Non-observation events (actions) are never masked
 * - Only observations outside the attention window are replaced
 * - Always returns [CondenserView], never [Condensation]
 */
class ObservationMaskingCondenser(
    private val attentionWindow: Int = 30
) : Condenser {

    override fun condense(context: CondenserContext): CondenserResult {
        val view = context.view
        val events = view.events
        val threshold = events.size - attentionWindow

        val maskedEvents = events.mapIndexed { i, event ->
            if (i < threshold && event is Observation && event !is CondensationObservation) {
                maskObservation(event)
            } else {
                event
            }
        }

        return CondenserView(
            View(
                events = maskedEvents,
                unhandledCondensationRequest = view.unhandledCondensationRequest,
                forgottenEventIds = view.forgottenEventIds
            )
        )
    }

    private fun maskObservation(observation: Observation): CondensationObservation {
        return when (observation) {
            is ToolResultObservation -> maskToolResult(observation)
            else -> maskGenericObservation(observation)
        }
    }

    private fun maskToolResult(observation: ToolResultObservation): CondensationObservation {
        val preview = observation.content.take(100)
        val recoveryHint = getRecoveryHint(observation.toolName)

        return CondensationObservation(
            content = buildString {
                append("[Tool result masked to save context]\n")
                append("Tool: ${observation.toolName}\n")
                append("Preview: $preview...\n")
                append("Recovery: $recoveryHint")
            },
            id = observation.id,
            timestamp = observation.timestamp
        )
    }

    private fun maskGenericObservation(observation: Observation): CondensationObservation {
        val preview = observation.content.take(100)

        return CondensationObservation(
            content = "[Observation masked to save context]\nPreview: $preview...",
            id = observation.id,
            timestamp = observation.timestamp
        )
    }

    companion object {
        private val RECOVERY_HINTS = mapOf(
            "read_file" to "re-run read_file to get current content",
            "search_code" to "re-run search_code with the same query",
            "glob_files" to "re-run glob_files with the same pattern",
            "run_command" to "re-run the command if output is needed",
            "diagnostics" to "re-run diagnostics for current results"
        )

        internal fun getRecoveryHint(toolName: String): String =
            RECOVERY_HINTS[toolName] ?: "re-run the tool if the result is needed"
    }
}
