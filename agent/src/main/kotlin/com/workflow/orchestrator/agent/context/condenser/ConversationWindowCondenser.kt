package com.workflow.orchestrator.agent.context.condenser

import com.workflow.orchestrator.agent.context.events.*

/**
 * Proactive sliding-window condenser that trims old conversation events.
 *
 * Triggers when token utilization exceeds [threshold] OR when an explicit
 * condensation request is pending (context window overflow from the API).
 *
 * Algorithm (matching OpenHands ConversationWindowCondenser, enhanced with proactive trigger):
 * 1. Identify essential events (system message + first user message)
 * 2. Keep roughly half of the non-essential events from the tail
 * 3. Skip dangling observations at the slice boundary
 * 4. Protect NEVER_FORGET_TYPES events from being forgotten
 * 5. Use range mode for contiguous forgotten IDs, explicit list otherwise
 * 6. No LLM call, no summary
 */
class ConversationWindowCondenser(
    private val threshold: Double = 0.75
) : RollingCondenser() {

    override fun shouldCondense(context: CondenserContext): Boolean {
        return context.tokenUtilization >= threshold
            || context.view.unhandledCondensationRequest
    }

    override fun getCondensation(context: CondenserContext): Condensation {
        // Delegate to getCondensationOrPassthrough which handles the no-op case
        // by returning CondenserView. This override is kept for the abstract contract
        // but should not be called directly — use condense() instead.
        throw UnsupportedOperationException("Use condense() instead")
    }

    override fun condense(context: CondenserContext): CondenserResult {
        if (!shouldCondense(context)) {
            return CondenserView(context.view)
        }

        val view = context.view
        val events = view.events

        // Step 1: Find essential initial events
        val systemMessage = events.firstOrNull { it is SystemMessageAction }
        val firstUserMsg = events.firstOrNull {
            (it is MessageAction || it is UserSteeringAction) && it.source == EventSource.USER
        }

        // If no first user message, nothing to forget — pass through
        if (firstUserMsg == null) {
            return CondenserView(context.view)
        }

        val essentialEvents = buildSet {
            if (systemMessage != null) add(systemMessage.id)
            add(firstUserMsg.id)
        }
        val numEssentialEvents = essentialEvents.size

        // Step 2: Calculate keep count
        val numNonEssentialEvents = view.size - numEssentialEvents
        if (numNonEssentialEvents <= 0) {
            return CondenserView(context.view)
        }
        val numRecentToKeep = maxOf(1, numNonEssentialEvents / 2)
        val sliceStartIndex = view.size - numRecentToKeep

        // Step 3: Handle dangling observations
        // Find the first non-Observation event in the recent slice
        var firstValidEventIndex = sliceStartIndex
        while (firstValidEventIndex < events.size && events[firstValidEventIndex] is Observation) {
            firstValidEventIndex++
        }
        // If we skipped past everything, fall back to sliceStartIndex
        if (firstValidEventIndex >= events.size) {
            firstValidEventIndex = sliceStartIndex
        }

        // Step 4: Build keep set (essential + recent valid + NEVER_FORGET_TYPES)
        val eventsToKeep = buildSet {
            addAll(essentialEvents)
            for (i in firstValidEventIndex until events.size) {
                add(events[i].id)
            }
            // Protect NEVER_FORGET_TYPES
            for (event in events) {
                if (NEVER_FORGET_TYPES.any { it.isInstance(event) }) {
                    add(event.id)
                }
            }
        }

        val allEventIds = events.map { it.id }.toSet()
        val forgottenEventIds = (allEventIds - eventsToKeep).sorted()

        if (forgottenEventIds.isEmpty()) {
            return CondenserView(context.view)
        }

        // Step 5: Optimize — use range mode if IDs are contiguous
        return if (isContiguous(forgottenEventIds)) {
            Condensation(
                CondensationAction(
                    forgottenEventIds = null,
                    forgottenEventsStartId = forgottenEventIds.first(),
                    forgottenEventsEndId = forgottenEventIds.last(),
                    summary = null,
                    summaryOffset = null
                )
            )
        } else {
            Condensation(
                CondensationAction(
                    forgottenEventIds = forgottenEventIds,
                    forgottenEventsStartId = null,
                    forgottenEventsEndId = null,
                    summary = null,
                    summaryOffset = null
                )
            )
        }
    }

    private fun isContiguous(sortedIds: List<Int>): Boolean {
        if (sortedIds.isEmpty()) return true
        return sortedIds.last() - sortedIds.first() + 1 == sortedIds.size
    }
}
