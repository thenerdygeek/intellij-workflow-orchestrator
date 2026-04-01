package com.workflow.orchestrator.agent.context.events

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ViewTest {

    // -----------------------------------------------------------------------
    // Helper factories
    // -----------------------------------------------------------------------

    private fun msg(id: Int, content: String = "msg-$id") =
        MessageAction(content = content, id = id)

    private fun sysMsg(id: Int, content: String = "sys-$id") =
        SystemMessageAction(content = content, id = id)

    private fun condensation(
        id: Int,
        forgottenIds: List<Int>? = null,
        startId: Int? = null,
        endId: Int? = null,
        summary: String? = null,
        summaryOffset: Int? = null
    ) = CondensationAction(
        forgottenEventIds = forgottenIds,
        forgottenEventsStartId = startId,
        forgottenEventsEndId = endId,
        summary = summary,
        summaryOffset = summaryOffset,
        id = id
    )

    private fun condensationRequest(id: Int) =
        CondensationRequestAction(id = id)

    // -----------------------------------------------------------------------
    // fromEvents — no condensation
    // -----------------------------------------------------------------------

    @Test
    fun `fromEvents with no condensation returns all events unchanged`() {
        val events = listOf(msg(0), msg(1), msg(2))
        val view = View.fromEvents(events)

        assertEquals(3, view.size)
        assertEquals(events, view.events)
        assertFalse(view.unhandledCondensationRequest)
        assertTrue(view.forgottenEventIds.isEmpty())
    }

    // -----------------------------------------------------------------------
    // fromEvents — forgotten events (explicit IDs)
    // -----------------------------------------------------------------------

    @Test
    fun `fromEvents filters out forgotten events from CondensationAction with explicit IDs`() {
        val events = listOf(
            msg(0),
            msg(1),
            msg(2),
            msg(3),
            condensation(id = 4, forgottenIds = listOf(1, 2))
        )
        val view = View.fromEvents(events)

        // Events 1, 2 are forgotten; event 4 (CondensationAction itself) is also forgotten
        assertEquals(2, view.size)
        assertEquals(listOf(msg(0), msg(3)), view.events)
        assertEquals(setOf(1, 2, 4), view.forgottenEventIds)
    }

    // -----------------------------------------------------------------------
    // fromEvents — forgotten events (range mode)
    // -----------------------------------------------------------------------

    @Test
    fun `fromEvents filters out forgotten events from CondensationAction with range mode`() {
        val events = listOf(
            msg(0),
            msg(1),
            msg(2),
            msg(3),
            condensation(id = 4, startId = 1, endId = 2)
        )
        val view = View.fromEvents(events)

        // Range 1..2 forgotten, plus event 4 itself
        assertEquals(2, view.size)
        assertEquals(listOf(msg(0), msg(3)), view.events)
        assertEquals(setOf(1, 2, 4), view.forgottenEventIds)
    }

    // -----------------------------------------------------------------------
    // fromEvents — CondensationAction itself is filtered out
    // -----------------------------------------------------------------------

    @Test
    fun `fromEvents CondensationAction itself is also filtered out`() {
        val events = listOf(
            msg(0),
            condensation(id = 1, forgottenIds = emptyList())
        )
        val view = View.fromEvents(events)

        // CondensationAction's own ID is added to forgotten set
        assertEquals(1, view.size)
        assertEquals(listOf(msg(0)), view.events)
        assertTrue(1 in view.forgottenEventIds)
    }

    // -----------------------------------------------------------------------
    // fromEvents — CondensationRequestAction is filtered out
    // -----------------------------------------------------------------------

    @Test
    fun `fromEvents CondensationRequestAction is filtered out`() {
        val events = listOf(
            msg(0),
            condensationRequest(1),
            msg(2)
        )
        val view = View.fromEvents(events)

        // CondensationRequestAction's own ID is added to forgotten set
        assertEquals(2, view.size)
        assertEquals(listOf(msg(0), msg(2)), view.events)
        assertTrue(1 in view.forgottenEventIds)
    }

    // -----------------------------------------------------------------------
    // fromEvents — summary insertion at correct offset
    // -----------------------------------------------------------------------

    @Test
    fun `fromEvents inserts summary at correct summaryOffset position`() {
        val events = listOf(
            msg(0),      // id=0
            msg(1),      // id=1 — will be forgotten
            msg(2),      // id=2 — will be forgotten
            msg(3),      // id=3
            condensation(
                id = 4,
                forgottenIds = listOf(1, 2),
                summary = "Summary of events 1-2",
                summaryOffset = 1  // Insert at position 1 in kept events
            )
        )
        val view = View.fromEvents(events)

        // Kept events before insertion: [msg(0), msg(3)]
        // After inserting summary at position 1: [msg(0), CondensationObservation, msg(3)]
        assertEquals(3, view.size)
        assertEquals(msg(0), view[0])
        assertTrue(view[1] is CondensationObservation)
        assertEquals("Summary of events 1-2", (view[1] as CondensationObservation).content)
        assertEquals(msg(3), view[2])
    }

    @Test
    fun `fromEvents clamps summaryOffset to keptEvents size`() {
        val events = listOf(
            msg(0),
            condensation(
                id = 1,
                forgottenIds = emptyList(),
                summary = "Summary",
                summaryOffset = 999  // Way beyond list size
            )
        )
        val view = View.fromEvents(events)

        // Kept: [msg(0)]. Summary clamped to position 1 (end of list).
        assertEquals(2, view.size)
        assertEquals(msg(0), view[0])
        assertTrue(view[1] is CondensationObservation)
    }

    // -----------------------------------------------------------------------
    // fromEvents — uses LAST CondensationAction's summary (not first)
    // -----------------------------------------------------------------------

    @Test
    fun `fromEvents uses the LAST CondensationAction summary not first`() {
        val events = listOf(
            msg(0),
            msg(1),
            msg(2),
            condensation(
                id = 3,
                forgottenIds = listOf(1),
                summary = "First summary",
                summaryOffset = 0
            ),
            msg(4),
            condensation(
                id = 5,
                forgottenIds = listOf(2),
                summary = "Second summary",
                summaryOffset = 0
            )
        )
        val view = View.fromEvents(events)

        // The LAST condensation's summary should be used
        val summaries = view.events.filterIsInstance<CondensationObservation>()
        assertEquals(1, summaries.size)
        assertEquals("Second summary", summaries[0].content)
    }

    // -----------------------------------------------------------------------
    // fromEvents — unhandled condensation request detection
    // -----------------------------------------------------------------------

    @Test
    fun `fromEvents detects unhandled condensation request when request is after last condensation`() {
        val events = listOf(
            msg(0),
            condensation(id = 1, forgottenIds = emptyList()),
            msg(2),
            condensationRequest(3)
        )
        val view = View.fromEvents(events)

        assertTrue(view.unhandledCondensationRequest)
    }

    @Test
    fun `fromEvents does NOT flag request as unhandled if condensation follows it`() {
        val events = listOf(
            msg(0),
            condensationRequest(1),
            condensation(id = 2, forgottenIds = emptyList())
        )
        val view = View.fromEvents(events)

        // Scanning in reverse: CondensationAction at id=2 is found before CondensationRequestAction
        assertFalse(view.unhandledCondensationRequest)
    }

    @Test
    fun `fromEvents does not flag unhandled when no request exists`() {
        val events = listOf(msg(0), msg(1))
        val view = View.fromEvents(events)

        assertFalse(view.unhandledCondensationRequest)
    }

    // -----------------------------------------------------------------------
    // fromEvents — empty history
    // -----------------------------------------------------------------------

    @Test
    fun `fromEvents handles empty history`() {
        val view = View.fromEvents(emptyList())

        assertEquals(0, view.size)
        assertTrue(view.events.isEmpty())
        assertFalse(view.unhandledCondensationRequest)
        assertTrue(view.forgottenEventIds.isEmpty())
    }

    // -----------------------------------------------------------------------
    // fromEvents — multiple condensation rounds (chained)
    // -----------------------------------------------------------------------

    @Test
    fun `fromEvents with multiple condensation rounds chains correctly`() {
        val events = listOf(
            msg(0),       // kept
            msg(1),       // forgotten by first condensation
            msg(2),       // forgotten by first condensation
            condensation(id = 3, forgottenIds = listOf(1, 2), summary = "Round 1 summary", summaryOffset = 1),
            msg(4),       // kept
            msg(5),       // forgotten by second condensation
            condensation(id = 6, forgottenIds = listOf(5), summary = "Round 2 summary", summaryOffset = 1)
        )
        val view = View.fromEvents(events)

        // Forgotten: {1, 2, 3, 5, 6}
        assertEquals(setOf(1, 2, 3, 5, 6), view.forgottenEventIds)

        // Kept before summary insertion: [msg(0), msg(4)]
        // LAST condensation (id=6) has summary "Round 2 summary" at offset 1
        // After insertion: [msg(0), CondensationObservation("Round 2 summary"), msg(4)]
        assertEquals(3, view.size)
        assertEquals(msg(0), view[0])
        assertTrue(view[1] is CondensationObservation)
        assertEquals("Round 2 summary", (view[1] as CondensationObservation).content)
        assertEquals(msg(4), view[2])
    }

    @Test
    fun `fromEvents with chained condensation where second forgets first summary range`() {
        // Simulates a scenario where the second condensation forgets events
        // including the range covered by the first condensation
        val events = listOf(
            msg(0),
            msg(1),
            msg(2),
            condensation(id = 3, forgottenIds = listOf(1, 2)),
            msg(4),
            msg(5),
            condensation(id = 6, forgottenIds = listOf(4, 5), summary = "Everything after msg 0", summaryOffset = 1)
        )
        val view = View.fromEvents(events)

        // Forgotten: {1, 2, 3, 4, 5, 6}
        assertEquals(setOf(1, 2, 3, 4, 5, 6), view.forgottenEventIds)

        // Kept before insertion: [msg(0)]
        // Insert summary at offset 1 (end): [msg(0), CondensationObservation]
        assertEquals(2, view.size)
        assertEquals(msg(0), view[0])
        assertTrue(view[1] is CondensationObservation)
    }

    // -----------------------------------------------------------------------
    // View.size and View[index]
    // -----------------------------------------------------------------------

    @Test
    fun `View size returns event count`() {
        val view = View(events = listOf(msg(0), msg(1), msg(2)))
        assertEquals(3, view.size)
    }

    @Test
    fun `View index operator returns correct event`() {
        val events = listOf(msg(0), msg(1), msg(2))
        val view = View(events = events)

        assertEquals(msg(0), view[0])
        assertEquals(msg(1), view[1])
        assertEquals(msg(2), view[2])
    }

    @Test
    fun `View index out of bounds throws exception`() {
        val view = View(events = listOf(msg(0)))
        assertThrows(IndexOutOfBoundsException::class.java) { view[5] }
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    fun `fromEvents with condensation that has no summary does not insert observation`() {
        val events = listOf(
            msg(0),
            msg(1),
            condensation(id = 2, forgottenIds = listOf(1))
        )
        val view = View.fromEvents(events)

        // No summary, so no CondensationObservation inserted
        assertEquals(1, view.size)
        assertEquals(msg(0), view[0])
        assertFalse(view.events.any { it is CondensationObservation })
    }

    @Test
    fun `fromEvents with only condensation events results in empty view`() {
        val events = listOf(
            condensation(id = 0, forgottenIds = emptyList()),
            condensationRequest(1)
        )
        val view = View.fromEvents(events)

        // Both are filtered out (their own IDs added to forgotten)
        assertTrue(view.events.isEmpty() || view.events.all { it is CondensationObservation })
    }

    @Test
    fun `fromEvents summary offset zero inserts at beginning`() {
        val events = listOf(
            msg(0),
            msg(1),
            condensation(id = 2, forgottenIds = emptyList(), summary = "Before everything", summaryOffset = 0)
        )
        val view = View.fromEvents(events)

        // Summary inserted at position 0
        assertEquals(3, view.size)
        assertTrue(view[0] is CondensationObservation)
        assertEquals("Before everything", (view[0] as CondensationObservation).content)
        assertEquals(msg(0), view[1])
        assertEquals(msg(1), view[2])
    }

    @Test
    fun `fromEvents ignores condensation with summary but null summaryOffset`() {
        // This shouldn't happen per validation, but fromEvents should handle it gracefully
        val events = listOf(
            msg(0),
            condensation(id = 1, forgottenIds = emptyList(), summary = "Orphan summary", summaryOffset = null)
        )
        val view = View.fromEvents(events)

        // summary != null but summaryOffset == null, so this condensation is skipped for summary insertion
        assertFalse(view.events.any { it is CondensationObservation })
    }

    @Test
    fun `fromEvents with mixed event types preserves non-message events`() {
        val events = listOf(
            msg(0),
            AgentThinkAction(thought = "thinking", id = 1),
            ToolResultObservation(toolCallId = "tc1", content = "result", isError = false, toolName = "read_file", id = 2),
            msg(3),
            condensation(id = 4, forgottenIds = listOf(1))
        )
        val view = View.fromEvents(events)

        // Event 1 (think) forgotten, event 4 (condensation) forgotten
        assertEquals(3, view.size)
        assertEquals(msg(0), view[0])
        assertTrue(view[1] is ToolResultObservation)
        assertEquals(msg(3), view[2])
    }
}
