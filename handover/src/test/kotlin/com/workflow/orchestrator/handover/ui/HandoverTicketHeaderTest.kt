package com.workflow.orchestrator.handover.ui

import com.workflow.orchestrator.handover.model.HandoverState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import javax.swing.JLabel
import java.awt.Container

class HandoverTicketHeaderTest {

    private fun walk(c: Container): List<JLabel> = buildList {
        for (i in 0 until c.componentCount) {
            val child = c.getComponent(i)
            if (child is JLabel) add(child)
            if (child is Container) addAll(walk(child))
        }
    }

    @Test
    fun `populates ticket key, title, and status when active`() {
        val header = HandoverTicketHeader()
        val state = HandoverState(
            ticketId = "AFTER8TE-912",
            ticketSummary = "Cache dev-status responses for 60s",
            currentStatusName = "In Progress",
        )
        header.updateState(state)

        val labels = walk(header).map { it.text }
        assertTrue(labels.any { it.contains("AFTER8TE-912") }, "ticket key not in labels: $labels")
        assertTrue(labels.any { it.contains("Cache dev-status responses for 60s") }, labels.toString())
        assertTrue(labels.any { it.contains("In Progress") }, labels.toString())
    }

    @Test
    fun `falls back to placeholder when ticketId blank`() {
        val header = HandoverTicketHeader()
        val state = HandoverState(
            ticketId = "",
            ticketSummary = "",
            currentStatusName = null,
        )
        header.updateState(state)
        val labels = walk(header).map { it.text }
        assertTrue(labels.any { it.contains("NO ACTIVE TICKET", ignoreCase = true) }, labels.toString())
    }

    @Test
    fun `falls back for status when currentStatusName is null`() {
        val header = HandoverTicketHeader()
        val state = HandoverState(
            ticketId = "PROJ-42",
            ticketSummary = "Some task",
            currentStatusName = null,
        )
        header.updateState(state)
        val labels = walk(header).map { it.text }
        assertTrue(labels.any { it.contains("Unknown", ignoreCase = true) }, labels.toString())
    }

    @Test
    fun `falls back title when ticketSummary is blank`() {
        val header = HandoverTicketHeader()
        val state = HandoverState(
            ticketId = "PROJ-99",
            ticketSummary = "",
            currentStatusName = "Done",
        )
        header.updateState(state)
        val labels = walk(header).map { it.text }
        assertTrue(labels.any { it == "—" }, "Expected '—' placeholder for empty summary, got: $labels")
    }
}
