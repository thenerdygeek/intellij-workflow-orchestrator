package com.workflow.orchestrator.pullrequest.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure-function tests for the provenance-label helper on [CreatePrDialog].
 *
 * The rest of the dialog is Swing — exercised manually via `runIde` — but the
 * label-formatting logic is isolated so it can be covered here without a
 * Swing harness. Mirrors the Phase 4 test style for [TicketChipInput]: drive the
 * single pure function from outside, assert on its return value.
 */
class CreatePrDialogProvenanceTest {

    private val gen = CreatePrDialog.GenContext(
        target = "develop",
        tickets = listOf("ABC-1", "ABC-2")
    )

    @Test
    fun `empty when no generation has happened yet`() {
        val text = CreatePrDialog.buildProvenanceText(
            lastGen = null,
            currentTarget = "develop",
            currentTickets = listOf("ABC-1")
        )
        assertEquals("", text)
    }

    @Test
    fun `base label when current matches last generation`() {
        val text = CreatePrDialog.buildProvenanceText(
            lastGen = gen,
            currentTarget = "develop",
            currentTickets = listOf("ABC-1", "ABC-2")
        )
        assertEquals("Generated against target: develop · tickets: ABC-1,ABC-2", text)
    }

    @Test
    fun `target-changed suffix appears when target diverges`() {
        val text = CreatePrDialog.buildProvenanceText(
            lastGen = gen,
            currentTarget = "main",
            currentTickets = listOf("ABC-1", "ABC-2")
        )
        assertEquals(
            "Generated against target: develop · tickets: ABC-1,ABC-2 · (target changed — click Generate to refresh)",
            text
        )
    }

    @Test
    fun `tickets-changed suffix appears when list diverges`() {
        val text = CreatePrDialog.buildProvenanceText(
            lastGen = gen,
            currentTarget = "develop",
            currentTickets = listOf("ABC-1")
        )
        assertEquals(
            "Generated against target: develop · tickets: ABC-1,ABC-2 · (tickets changed — click Generate to refresh)",
            text
        )
    }

    @Test
    fun `both suffixes appear when target and tickets diverge`() {
        val text = CreatePrDialog.buildProvenanceText(
            lastGen = gen,
            currentTarget = "main",
            currentTickets = emptyList()
        )
        assertEquals(
            "Generated against target: develop · tickets: ABC-1,ABC-2 · (target changed — click Generate to refresh) · (tickets changed — click Generate to refresh)",
            text
        )
    }

    @Test
    fun `empty tickets render as parenthesised none`() {
        val emptyGen = CreatePrDialog.GenContext("develop", emptyList())
        val text = CreatePrDialog.buildProvenanceText(
            lastGen = emptyGen,
            currentTarget = "develop",
            currentTickets = emptyList()
        )
        assertEquals("Generated against target: develop · tickets: (none)", text)
    }

    @Test
    fun `ticket order is significant — reorder counts as change`() {
        val text = CreatePrDialog.buildProvenanceText(
            lastGen = gen,
            currentTarget = "develop",
            currentTickets = listOf("ABC-2", "ABC-1")
        )
        assertEquals(
            "Generated against target: develop · tickets: ABC-1,ABC-2 · (tickets changed — click Generate to refresh)",
            text
        )
    }

    @Test
    fun `clear after generation then change target — provenance stays empty`() {
        // Simulates showDescriptionEmpty() setting lastGen = null after user clears or timeout/error.
        // Even if the target changes afterwards, buildProvenanceText with null lastGen must return "".
        val text = CreatePrDialog.buildProvenanceText(
            lastGen = null,
            currentTarget = "main",
            currentTickets = listOf("ABC-1", "ABC-2")
        )
        assertEquals("", text)
    }
}
