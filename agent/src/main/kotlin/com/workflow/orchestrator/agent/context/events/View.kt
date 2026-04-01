package com.workflow.orchestrator.agent.context.events

/**
 * A filtered projection of the raw event history that the LLM will see.
 *
 * [View.fromEvents] applies the OpenHands condensation algorithm:
 * 1. Collect forgotten IDs from all [CondensationAction]s and [CondensationRequestAction]s
 * 2. Filter out forgotten events
 * 3. Insert the last condensation summary at the correct offset
 * 4. Detect unhandled condensation requests
 *
 * Every condenser operates on Views. This is the heart of the event-sourced
 * context management architecture.
 */
data class View(
    val events: List<Event>,
    val unhandledCondensationRequest: Boolean = false,
    val forgottenEventIds: Set<Int> = emptySet()
) {
    /** Number of events in this view. */
    val size: Int get() = events.size

    /** Access an event by index. */
    operator fun get(index: Int): Event = events[index]

    companion object {
        /**
         * Build a View from raw event history, applying the OpenHands condensation algorithm.
         *
         * The algorithm:
         * 1. **Collect forgotten IDs:** For each [CondensationAction], add its [CondensationAction.forgotten]
         *    IDs plus its own [Event.id]. For each [CondensationRequestAction], add its own [Event.id].
         * 2. **Filter:** Keep only events whose [Event.id] is NOT in the forgotten set.
         * 3. **Insert summary:** Scan events in REVERSE to find the LAST [CondensationAction] with both
         *    [CondensationAction.summary] and [CondensationAction.summaryOffset] set. If found, create a
         *    [CondensationObservation] and insert it at position [CondensationAction.summaryOffset]
         *    (clamped to keptEvents.size).
         * 4. **Detect unhandled request:** Scan events in REVERSE. If a [CondensationRequestAction] is found
         *    before any [CondensationAction], set [unhandledCondensationRequest] to true.
         */
        fun fromEvents(history: List<Event>): View {
            if (history.isEmpty()) {
                return View(events = emptyList())
            }

            // Step 1: Collect forgotten IDs
            val forgottenIds = mutableSetOf<Int>()
            for (event in history) {
                when (event) {
                    is CondensationAction -> {
                        forgottenIds.addAll(event.forgotten)
                        forgottenIds.add(event.id)
                    }
                    is CondensationRequestAction -> {
                        forgottenIds.add(event.id)
                    }
                    else -> { /* no-op */ }
                }
            }

            // Step 1b: Protect NEVER_FORGET_TYPES — remove their IDs from forgotten set
            val neverForgetIds = history
                .filter { event -> NEVER_FORGET_TYPES.any { it.isInstance(event) } }
                .map { it.id }
                .toSet()
            forgottenIds.removeAll(neverForgetIds)

            // Step 2: Filter out forgotten events
            val keptEvents = history.filter { it.id !in forgottenIds }.toMutableList()

            // Step 3: Insert summary from the LAST CondensationAction with summary + summaryOffset
            var summaryAction: CondensationAction? = null
            for (event in history.asReversed()) {
                if (event is CondensationAction && event.summary != null && event.summaryOffset != null) {
                    summaryAction = event
                    break
                }
            }
            if (summaryAction != null) {
                val observation = CondensationObservation(content = summaryAction.summary!!)
                val insertPos = summaryAction.summaryOffset!!.coerceAtMost(keptEvents.size)
                keptEvents.add(insertPos, observation)
            }

            // Step 4: Detect unhandled condensation request
            var unhandledRequest = false
            for (event in history.asReversed()) {
                when (event) {
                    is CondensationRequestAction -> {
                        unhandledRequest = true
                        break
                    }
                    is CondensationAction -> {
                        // A condensation action was found before any request — request is handled
                        break
                    }
                    else -> { /* keep scanning */ }
                }
            }

            return View(
                events = keptEvents,
                unhandledCondensationRequest = unhandledRequest,
                forgottenEventIds = forgottenIds
            )
        }
    }
}
