package com.workflow.orchestrator.jira.ui

import com.workflow.orchestrator.core.model.jira.StatusCategory
import com.workflow.orchestrator.core.model.jira.StatusRef
import com.workflow.orchestrator.core.model.jira.TransitionMeta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Pure-Kotlin tests for [TicketTransitionDialog.resolveInitialSelectionIndex].
 *
 * Locks in the "no silent fallback to index 0 when the caller's target id is
 * missing from the available transitions" guarantee.
 *
 * Why this matters: a previous bug surfaced as "I clicked Start Work but the
 * ticket transitioned to In Review". Root cause was that the configured Start
 * Work target ("In Progress") wasn't in the ticket's currently-available
 * transitions (because the ticket's status had already moved — typically via a
 * Jira-side automation rule on linked-branch detection), and the dialog
 * silently fell back to `selectedIndex = 0`, which happened to be the
 * In Review transition. The fallback was hidden from the user, so clicking OK
 * approved a transition they didn't intend.
 *
 * The fix: when the caller-supplied `initialTransitionId` doesn't match any
 * available transition, return null. The dialog then shows a warning and
 * keeps the OK button disabled until the user picks explicitly.
 */
class TicketTransitionDialogPreSelectionTest {

    private fun meta(id: String, toName: String): TransitionMeta = TransitionMeta(
        id = id,
        name = "Move to $toName",
        toStatus = StatusRef(id = id, name = toName, category = StatusCategory.IN_PROGRESS),
        hasScreen = false,
        fields = emptyList()
    )

    @Test
    fun `returns null for empty transitions, regardless of target`() {
        assertNull(TicketTransitionDialog.resolveInitialSelectionIndex(emptyList(), null))
        assertNull(TicketTransitionDialog.resolveInitialSelectionIndex(emptyList(), "11"))
    }

    @Test
    fun `defaults to index 0 when caller did not specify a target id`() {
        val transitions = listOf(meta("11", "In Progress"), meta("21", "In Review"))
        assertEquals(0, TicketTransitionDialog.resolveInitialSelectionIndex(transitions, null))
    }

    @Test
    fun `returns the matched index when target id is present`() {
        val transitions = listOf(
            meta("11", "In Progress"),
            meta("21", "In Review"),
            meta("31", "Done"),
        )
        assertEquals(0, TicketTransitionDialog.resolveInitialSelectionIndex(transitions, "11"))
        assertEquals(1, TicketTransitionDialog.resolveInitialSelectionIndex(transitions, "21"))
        assertEquals(2, TicketTransitionDialog.resolveInitialSelectionIndex(transitions, "31"))
    }

    @Test
    fun `returns null when target id is NOT in the available transitions`() {
        // The Start Work bug: caller asked for the In Progress transition but
        // only In Review is available (e.g. ticket already moved out of Open).
        // Must NOT fall back to index 0 (In Review) — that produced silent
        // approvals of unintended transitions.
        val transitions = listOf(meta("21", "In Review"))
        assertNull(
            TicketTransitionDialog.resolveInitialSelectionIndex(transitions, "11"),
            "Caller asked for id=11 but only id=21 is available — must NOT silently fall back to index 0"
        )
    }
}
