package com.workflow.orchestrator.agent.context.condenser

import com.workflow.orchestrator.agent.context.events.*

/**
 * Token-utilization-gated observation condenser with three content tiers.
 *
 * Unlike a fixed event-count window, this condenser only activates when context
 * pressure exceeds [threshold] (fraction of token budget). When active, it applies
 * tiered compression based on each observation's token distance from the tail:
 *
 * - **FULL** (within [innerWindowTokens]): Content preserved unchanged
 * - **COMPRESSED** (within [outerWindowTokens]): First 20 + last 5 lines kept
 * - **METADATA** (beyond outer window): Tool name + 100-char preview + recovery hint
 *
 * Key behaviors:
 * - [CondensationObservation] instances are NEVER masked (they contain critical summaries)
 * - Non-observation events (actions) are never masked
 * - Always returns [CondenserView], never [Condensation]
 * - Below [threshold], the view passes through completely unchanged
 */
class ObservationMaskingCondenser(
    private val threshold: Double = 0.60,
    private val innerWindowTokens: Int = 40_000,
    private val outerWindowTokens: Int = 60_000
) : Condenser {

    init {
        require(innerWindowTokens < outerWindowTokens) {
            "innerWindowTokens ($innerWindowTokens) must be less than outerWindowTokens ($outerWindowTokens)"
        }
    }

    override fun condense(context: CondenserContext): CondenserResult {
        if (context.tokenUtilization < threshold) {
            return CondenserView(context.view)
        }

        val events = context.view.events
        val tokenDistances = computeTokenDistances(events)

        val maskedEvents = events.mapIndexed { i, event ->
            if (event is Observation && event !is CondensationObservation) {
                val distance = tokenDistances[i]
                when (tierFor(distance)) {
                    Tier.FULL -> event
                    Tier.COMPRESSED -> compressObservation(event)
                    Tier.METADATA -> maskObservation(event)
                }
            } else {
                event
            }
        }

        return CondenserView(
            View(
                events = maskedEvents,
                unhandledCondensationRequest = context.view.unhandledCondensationRequest,
                forgottenEventIds = context.view.forgottenEventIds
            )
        )
    }

    /**
     * Compute the approximate token distance from the tail for each event.
     * Walks backwards from the end, accumulating estimated token counts.
     */
    private fun computeTokenDistances(events: List<Event>): IntArray {
        val distances = IntArray(events.size)
        var accumulated = 0
        for (i in events.indices.reversed()) {
            distances[i] = accumulated
            accumulated += estimateEventTokens(events[i])
        }
        return distances
    }

    /**
     * Estimate the token count for an event using the chars/4 heuristic.
     */
    private fun estimateEventTokens(event: Event): Int {
        val content = when (event) {
            is Observation -> event.content
            is ToolAction -> getActionContent(event)
            is MessageAction -> event.content
            is SystemMessageAction -> event.content
            is UserSteeringAction -> event.content
            is AgentThinkAction -> event.thought
            is AgentFinishAction -> event.finalThought
            is DelegateAction -> event.prompt
            is FactRecordedAction -> event.content
            is PlanUpdatedAction -> event.planJson
            is SkillActivatedAction -> event.skillName + event.content
            is SkillDeactivatedAction -> event.skillName
            is GuardrailRecordedAction -> event.rule
            is MentionAction -> event.paths.joinToString()
            is CondensationAction -> ""
            is CondensationRequestAction -> ""
        }
        return maxOf(1, content.length / 4)
    }

    private fun getActionContent(action: ToolAction): String = when (action) {
        is GenericToolAction -> action.arguments
        is MetaToolAction -> action.arguments
        is FileReadAction -> action.path
        is FileEditAction -> buildString {
            append(action.path)
            action.oldStr?.let { append(it) }
            action.newStr?.let { append(it) }
        }
        is CommandRunAction -> action.command
        is SearchCodeAction -> action.query
        is DiagnosticsAction -> action.path ?: ""
    }

    private fun tierFor(tokenDistance: Int): Tier = when {
        tokenDistance <= innerWindowTokens -> Tier.FULL
        tokenDistance <= outerWindowTokens -> Tier.COMPRESSED
        else -> Tier.METADATA
    }

    /**
     * COMPRESSED tier: keep first 20 + last 5 lines, replace middle with marker.
     */
    private fun compressObservation(observation: Observation): Observation {
        val content = observation.content
        val lines = content.lines()

        if (lines.size <= 30) return observation // Small enough, keep as-is

        val compressedCount = lines.size - 25
        val kept = buildString {
            lines.take(20).forEach { appendLine(it) }
            appendLine("[... $compressedCount ${if (compressedCount == 1) "line" else "lines"} compressed ...]")
            lines.takeLast(5).forEach { appendLine(it) }
        }.trimEnd()

        return when (observation) {
            is ToolResultObservation -> observation.copy(content = kept)
            is ErrorObservation -> observation.copy(content = kept)
            is SuccessObservation -> observation.copy(content = kept)
            else -> CondensationObservation(
                content = kept,
                id = observation.id,
                timestamp = observation.timestamp
            )
        }
    }

    /**
     * METADATA tier: tool name + 100-char preview + recovery hint.
     */
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

    private enum class Tier { FULL, COMPRESSED, METADATA }

    companion object {
        private val RECOVERY_HINTS = mapOf(
            "read_file" to "content was compressed — use search_code to find specific content, or read_file with offset+limit for a specific section. Do NOT re-read the entire file.",
            "search_code" to "re-run search_code with the same query if needed",
            "glob_files" to "re-run glob_files with the same pattern if needed",
            "run_command" to "re-run the command if output is needed",
            "diagnostics" to "re-run diagnostics for current results"
        )

        internal fun getRecoveryHint(toolName: String): String =
            RECOVERY_HINTS[toolName] ?: "result was compressed to save context"
    }
}
