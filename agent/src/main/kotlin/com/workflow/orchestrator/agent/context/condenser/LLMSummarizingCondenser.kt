package com.workflow.orchestrator.agent.context.condenser

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.context.events.*
import kotlinx.coroutines.runBlocking

/**
 * Interface for LLM-based summarization calls.
 * Decoupled from the real LLM client for testability.
 */
interface SummarizationClient {
    suspend fun summarize(messages: List<ChatMessage>): String?
}

/**
 * LLM-powered context condenser that summarizes old conversation events
 * into a compact summary, preserving the most important information.
 *
 * This is the most expensive condenser — it makes an LLM call. It runs last
 * in the pipeline (after SmartPruner and ObservationMasking have already
 * reduced the context).
 *
 * Algorithm (matching OpenHands LLMSummarizingCondenser):
 * 1. Keep the first [keepFirst] events (head) and recent events (tail)
 * 2. Summarize the forgotten middle events via LLM
 * 3. Insert the summary at [keepFirst] offset
 * 4. Protect NEVER_FORGET_TYPES from being forgotten
 *
 * @param llmClient Interface for making LLM summarization calls
 * @param keepFirst Number of initial events to always preserve
 * @param maxSize Maximum view size before condensation triggers
 * @param tokenThreshold Token utilization fraction that triggers condensation
 * @param maxEventLength Maximum character length for individual event descriptions
 */
class LLMSummarizingCondenser(
    private val llmClient: SummarizationClient,
    private val keepFirst: Int = 4,
    private val maxSize: Int = 150,
    private val tokenThreshold: Double = 0.75,
    private val maxEventLength: Int = 10_000
) : RollingCondenser() {

    override fun shouldCondense(context: CondenserContext): Boolean {
        return context.tokenUtilization > tokenThreshold
            || context.view.size > maxSize
            || context.view.unhandledCondensationRequest
    }

    override fun getCondensation(context: CondenserContext): Condensation {
        val view = context.view
        val events = view.events

        // Step 1: Head — first keepFirst events
        val head = events.take(keepFirst)

        // Step 2: Target size after condensation
        val targetSize = maxSize / 2
        val eventsFromTail = (targetSize - head.size - 1).coerceAtLeast(0) // -1 for summary slot
        val tail = events.takeLast(eventsFromTail)

        // Step 3: Detect previous summary at position keepFirst
        val previousSummary = if (events.size > keepFirst) {
            val candidate = events[keepFirst]
            if (candidate is CondensationObservation) candidate.content else null
        } else {
            null
        }

        // Step 4: Determine head and tail ID sets
        val headIds = head.map { it.id }.toSet()
        val tailIds = tail.map { it.id }.toSet()
        val keepIds = headIds + tailIds

        // Step 5: Collect NEVER_FORGET events
        val neverForgetIds = events
            .filter { event -> NEVER_FORGET_TYPES.any { it.isInstance(event) } }
            .map { it.id }
            .toSet()

        // Step 6: Collect forgotten events (middle, excluding CondensationObservation and NEVER_FORGET)
        val forgottenEvents = events.filter { event ->
            event.id !in keepIds
                && event.id !in neverForgetIds
                && event !is CondensationObservation
        }

        // Step 7: Build forgotten IDs (all events not in head, tail, or NEVER_FORGET)
        val forgottenIds = events
            .filter { event ->
                event.id !in keepIds
                    && event.id !in neverForgetIds
            }
            .map { it.id }
            .sorted()

        if (forgottenIds.isEmpty()) {
            return noOpCondensation()
        }

        // Step 8: Format events for LLM
        val eventBlocks = forgottenEvents.joinToString("\n") { event ->
            val description = formatEvent(event).let {
                if (it.length > maxEventLength) it.take(maxEventLength) + "..." else it
            }
            "<EVENT id=${event.id}>\n$description\n</EVENT>"
        }

        // Step 9: Build LLM prompt
        val previousSummaryText = previousSummary ?: "No events summarized"
        val promptContent = buildString {
            append("<system_instructions>\n")
            append(SUMMARIZATION_PROMPT)
            append("\n</system_instructions>\n\n")
            append("<PREVIOUS SUMMARY>\n")
            append(previousSummaryText)
            append("\n</PREVIOUS SUMMARY>\n\n")
            append(eventBlocks)
            append("\n\nNow summarize the events using the rules above.")
        }

        val messages = listOf(ChatMessage(role = "user", content = promptContent))

        // Step 10: Call LLM (blocking — condense() is not suspend)
        val summary = runBlocking {
            try {
                llmClient.summarize(messages)
            } catch (_: Exception) {
                null
            }
        }

        // Step 11: Handle failure — fall back to simple concatenation
        val finalSummary = if (summary.isNullOrBlank()) {
            buildFallbackSummary(forgottenEvents)
        } else {
            summary
        }

        // Step 12: Return Condensation with range or explicit IDs
        return if (isContiguous(forgottenIds)) {
            Condensation(
                CondensationAction(
                    forgottenEventIds = null,
                    forgottenEventsStartId = forgottenIds.first(),
                    forgottenEventsEndId = forgottenIds.last(),
                    summary = finalSummary,
                    summaryOffset = keepFirst
                )
            )
        } else {
            Condensation(
                CondensationAction(
                    forgottenEventIds = forgottenIds,
                    forgottenEventsStartId = null,
                    forgottenEventsEndId = null,
                    summary = finalSummary,
                    summaryOffset = keepFirst
                )
            )
        }
    }

    /**
     * Format an event into a human-readable description for the LLM.
     */
    internal fun formatEvent(event: Event): String = when (event) {
        is MessageAction -> "[Message from ${event.source}] ${event.content}"
        is SystemMessageAction -> "[System] ${event.content}"
        is AgentThinkAction -> "[Think] ${event.thought}"
        is AgentFinishAction -> "[Finish] ${event.finalThought}"
        is DelegateAction -> "[Delegate to ${event.agentType}] ${event.prompt}"
        is FileReadAction -> "[Tool: read_file] path=${event.path}"
        is FileEditAction -> "[Tool: edit_file] path=${event.path}"
        is CommandRunAction -> "[Tool: run_command] command=${event.command}"
        is SearchCodeAction -> "[Tool: search_code] query=${event.query}"
        is DiagnosticsAction -> "[Tool: diagnostics] path=${event.path ?: "project"}"
        is GenericToolAction -> "[Tool: ${event.toolName}] ${event.arguments}"
        is MetaToolAction -> "[Tool: ${event.toolName}.${event.actionName}] ${event.arguments}"
        is ToolResultObservation -> "[Tool result] ${event.content}"
        is CondensationObservation -> "[Summary] ${event.content}"
        is ErrorObservation -> "[Error] ${event.content}"
        is SuccessObservation -> "[Success] ${event.content}"
        is FactRecordedAction -> "[Fact: ${event.factType}] ${event.content}"
        is PlanUpdatedAction -> "[Plan updated] ${event.planJson}"
        is SkillActivatedAction -> "[Skill activated: ${event.skillName}]"
        is SkillDeactivatedAction -> "[Skill deactivated: ${event.skillName}]"
        is GuardrailRecordedAction -> "[Guardrail] ${event.rule}"
        is MentionAction -> "[Mention] paths=${event.paths.joinToString()}"
        is CondensationAction -> "[Condensation action]"
        is CondensationRequestAction -> "[Condensation request]"
    }

    private fun buildFallbackSummary(events: List<Event>): String {
        val MAX_FALLBACK_LENGTH = 4096
        val descriptions = events.joinToString("\n") { "- ${formatEvent(it)}" }
        return if (descriptions.length > MAX_FALLBACK_LENGTH) {
            descriptions.take(MAX_FALLBACK_LENGTH) + "..."
        } else {
            descriptions
        }
    }

    private fun noOpCondensation(): Condensation {
        return Condensation(
            CondensationAction(
                forgottenEventIds = emptyList(),
                forgottenEventsStartId = null,
                forgottenEventsEndId = null,
                summary = null,
                summaryOffset = null
            )
        )
    }

    private fun isContiguous(sortedIds: List<Int>): Boolean {
        if (sortedIds.isEmpty()) return true
        return sortedIds.last() - sortedIds.first() + 1 == sortedIds.size
    }

    companion object {
        internal const val SUMMARIZATION_PROMPT = """You are maintaining a context-aware state summary for an interactive agent.
You will be given a list of events corresponding to actions taken by the agent, and the most recent previous summary if one exists.
If the events being summarized contain ANY task-tracking, you MUST include a TASK_TRACKING section to maintain continuity.
When referencing tasks make sure to preserve exact task IDs and statuses.

Track:

USER_CONTEXT: (Preserve essential user requirements, goals, and clarifications in concise form)

TASK_TRACKING: {Active tasks, their IDs and statuses - PRESERVE TASK IDs}

COMPLETED: (Tasks completed so far, with brief results)
PENDING: (Tasks that still need to be done)
CURRENT_STATE: (Current variables, data structures, or relevant state)

For code-specific tasks, also include:
CODE_STATE: {File paths, function signatures, data structures}
TESTS: {Failing cases, error messages, outputs}
CHANGES: {Code edits, variable updates}
DEPS: {Dependencies, imports, external calls}
VERSION_CONTROL_STATUS: {Repository state, current branch, PR status, commit history}

PRIORITIZE:
1. Adapt tracking format to match the actual task type
2. Capture key user requirements and goals
3. Distinguish between completed and pending tasks
4. Keep all sections concise and relevant

SKIP: Tracking irrelevant details for the current task type"""
    }
}
