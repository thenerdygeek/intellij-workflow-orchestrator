package com.workflow.orchestrator.agent.delegation.ui

/**
 * Pure, stateless helper that maps a [PickerEntry] to a human-readable explainer
 * string shown below the project list in [DelegationPicker].
 *
 * Extracted as a standalone object so it can be unit-tested without a running
 * IntelliJ [com.intellij.openapi.application.Application] or an EDT thread.
 *
 * Design: one short sentence per entry status, written in plain English that
 * answers "what happens when I click Delegate for this row?"
 */
object DelegationPickerExplainer {

    /**
     * Returns a short (≤ ~140 chars) per-selection explainer.
     *
     * - [PickerEntry.Status.RUNNING]   — delegation socket bound, work starts immediately.
     * - [PickerEntry.Status.AVAILABLE] — doorbell bound but inbound off; a consent prompt rings.
     * - [PickerEntry.Status.CLOSED]    — IDE not running; Launch & Delegate opens it first, then consent.
     * - [PickerEntry.Status.MISSING]   — project path does not exist; inert row.
     * - `isHeader == true` or `null`  — section separator; no explainer.
     */
    fun explainerFor(entry: PickerEntry?): String {
        if (entry == null || entry.isHeader) return ""
        val name = entry.displayName
        return when (entry.status) {
            PickerEntry.Status.RUNNING ->
                "➤ Sends to “$name” now — it accepts delegations directly."

            PickerEntry.Status.AVAILABLE ->
                "➤ “$name” has inbound delegation off — sending rings a consent prompt there; " +
                    "work starts only after someone approves."

            PickerEntry.Status.CLOSED ->
                "➤ “$name” is closed — Launch & Delegate opens it, " +
                    "then it asks for consent before running."

            PickerEntry.Status.MISSING -> ""
        }
    }
}
