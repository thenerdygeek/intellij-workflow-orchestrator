package com.workflow.orchestrator.agent.context.condenser

import com.workflow.orchestrator.agent.context.events.*

/**
 * Zero-loss optimization condenser that reduces context size without forgetting events.
 *
 * Applies three strategies by replacing events with lighter equivalents:
 * 1. **Deduplicate file reads** — when the same file is read twice without an intervening edit,
 *    the older read result is replaced with a condensation placeholder.
 * 2. **Purge failed tool inputs** — for tool calls that failed and are old enough, truncates
 *    the original action's arguments to save tokens.
 * 3. **Supersede confirmed writes** — when an edit is confirmed by a subsequent read of the
 *    same file, the edit's result observation is replaced with a compact confirmation.
 *
 * This condenser always runs (no trigger check) and always returns [CondenserView].
 * It creates new event objects at replacement indices — original events are never mutated.
 */
class SmartPrunerCondenser(
    private val turnsAfterError: Int = 4
) : Condenser {

    override fun condense(context: CondenserContext): CondenserResult {
        val events = context.view.events.toMutableList()

        deduplicateFileReads(events)
        purgeFailedToolInputs(events)
        supersedeConfirmedWrites(events)

        return CondenserView(
            View(
                events = events,
                unhandledCondensationRequest = context.view.unhandledCondensationRequest,
                forgottenEventIds = context.view.forgottenEventIds
            )
        )
    }

    /**
     * Strategy 1: When the same file is read multiple times without an intervening edit,
     * replace the older read result(s) with a condensation placeholder.
     */
    private fun deduplicateFileReads(events: MutableList<Event>) {
        // Map from file path to the index in events where the latest read result sits
        val lastReadResultIndex = mutableMapOf<String, Int>()

        for (i in events.indices) {
            when (val event = events[i]) {
                is FileEditAction -> {
                    // Edit resets dedup tracking for that path
                    lastReadResultIndex.remove(event.path)
                }
                is FileReadAction -> {
                    val path = event.path
                    // Look for the corresponding ToolResultObservation by toolCallId
                    val resultIndex = findToolResultIndex(events, event.toolCallId, startFrom = i + 1)
                    if (resultIndex != null) {
                        val previousIndex = lastReadResultIndex[path]
                        if (previousIndex != null) {
                            // Replace the OLDER result with a condensation observation
                            val oldResult = events[previousIndex]
                            events[previousIndex] = CondensationObservation(
                                content = "[Deduplicated — '$path' was re-read later]",
                                id = oldResult.id,
                                timestamp = oldResult.timestamp
                            )
                        }
                        // Track this read's result index as the latest
                        lastReadResultIndex[path] = resultIndex
                    }
                }
                else -> { /* no-op */ }
            }
        }
    }

    /**
     * Strategy 2: For tool calls that failed (isError=true) and are old enough
     * (more than [turnsAfterError] events after the error result), truncate the
     * corresponding tool action's arguments if they exceed 500 chars.
     */
    private fun purgeFailedToolInputs(events: MutableList<Event>) {
        for (i in events.indices) {
            val event = events[i]
            if (event !is ToolResultObservation || !event.isError) continue

            // Check if this error is old enough (more than turnsAfterError events after it)
            val eventsAfter = events.size - 1 - i
            if (eventsAfter < turnsAfterError) continue

            // Find the corresponding ToolAction by toolCallId
            val actionIndex = findToolActionIndex(events, event.toolCallId, endBefore = i)
            if (actionIndex == null) continue

            val action = events[actionIndex]
            if (action !is ToolAction) continue

            // Get the arguments string from the action
            val args = getToolActionArguments(action) ?: continue
            if (args.length <= 500) continue

            // Replace with a GenericToolAction carrying truncated arguments
            val truncatedArgs = args.take(200) + "... [args truncated — tool call failed]"
            events[actionIndex] = GenericToolAction(
                toolCallId = action.toolCallId,
                responseGroupId = action.responseGroupId,
                toolName = getToolName(action),
                arguments = truncatedArgs,
                id = action.id,
                timestamp = action.timestamp,
                source = action.source
            )
        }
    }

    /**
     * Strategy 3: When a FileEditAction has a successful ToolResultObservation and
     * the same file is later read (FileReadAction), replace the edit's result with
     * a compact confirmation observation.
     */
    private fun supersedeConfirmedWrites(events: MutableList<Event>) {
        // Collect all paths that are read at some point
        val readPaths = mutableSetOf<String>()
        for (event in events) {
            if (event is FileReadAction) {
                readPaths.add(event.path)
            }
        }

        for (i in events.indices) {
            val event = events[i]
            if (event !is FileEditAction) continue

            val path = event.path

            // Find the edit's result observation
            val resultIndex = findToolResultIndex(events, event.toolCallId, startFrom = i + 1)
            if (resultIndex == null) continue

            val result = events[resultIndex]
            if (result !is ToolResultObservation || result.isError) continue

            // Check if there's a later FileReadAction for the same path
            val hasLaterRead = events.subList(resultIndex + 1, events.size).any {
                it is FileReadAction && it.path == path
            }
            if (!hasLaterRead) continue

            // Replace the edit's result with a condensation observation
            events[resultIndex] = CondensationObservation(
                content = "[Write confirmed by subsequent read — edit to '$path' was applied successfully.]",
                id = result.id,
                timestamp = result.timestamp
            )
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Find the index of the [ToolResultObservation] matching a [toolCallId],
     * searching forward from [startFrom].
     */
    private fun findToolResultIndex(events: List<Event>, toolCallId: String, startFrom: Int): Int? {
        for (i in startFrom until events.size) {
            val event = events[i]
            if (event is ToolResultObservation && event.toolCallId == toolCallId) {
                return i
            }
        }
        return null
    }

    /**
     * Find the index of the [ToolAction] matching a [toolCallId],
     * searching backward from [endBefore].
     */
    private fun findToolActionIndex(events: List<Event>, toolCallId: String, endBefore: Int): Int? {
        for (i in (endBefore - 1) downTo 0) {
            val event = events[i]
            if (event is ToolAction && event.toolCallId == toolCallId) {
                return i
            }
        }
        return null
    }

    /**
     * Extract the arguments string from a [ToolAction].
     */
    private fun getToolActionArguments(action: ToolAction): String? = when (action) {
        is GenericToolAction -> action.arguments
        is MetaToolAction -> action.arguments
        is FileReadAction -> action.path
        is FileEditAction -> buildString {
            append("path=${action.path}")
            action.oldStr?.let { append(", old_str=$it") }
            action.newStr?.let { append(", new_str=$it") }
        }
        is CommandRunAction -> action.command
        is SearchCodeAction -> action.query
        is DiagnosticsAction -> action.path ?: ""
    }

    /**
     * Extract a tool name from a [ToolAction] for use in the replacement [GenericToolAction].
     */
    private fun getToolName(action: ToolAction): String = when (action) {
        is GenericToolAction -> action.toolName
        is MetaToolAction -> "${action.toolName}.${action.actionName}"
        is FileReadAction -> "read_file"
        is FileEditAction -> "edit_file"
        is CommandRunAction -> "run_command"
        is SearchCodeAction -> "search_code"
        is DiagnosticsAction -> "diagnostics"
    }
}
