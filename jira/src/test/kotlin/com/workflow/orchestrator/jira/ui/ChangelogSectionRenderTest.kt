package com.workflow.orchestrator.jira.ui

import com.workflow.orchestrator.core.model.jira.TicketHistoryEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Headless tests for the pure formatting helper used by [ChangelogSection].
 * No Swing required — exercises [ChangelogSection.formatLine] directly.
 */
class ChangelogSectionRenderTest {

    private val createdAt = "2024-01-01T10:00:00.000+0000"

    @Test
    fun `status change uses set status to phrasing`() {
        val entry = TicketHistoryEntry(
            actorDisplayName = "Bob",
            createdAt = createdAt,
            field = "status",
            oldValue = "Open",
            newValue = "In Review"
        )
        val line = ChangelogSection.formatLine(entry)
        assertTrue(line.startsWith("Bob set status to In Review"), "Got: $line")
    }

    @Test
    fun `assignee change uses assigned to phrasing`() {
        val entry = TicketHistoryEntry(
            actorDisplayName = "Alice",
            createdAt = createdAt,
            field = "assignee",
            oldValue = "Bob",
            newValue = "Carol"
        )
        val line = ChangelogSection.formatLine(entry)
        assertTrue(line.startsWith("Alice assigned to Carol"), "Got: $line")
    }

    @Test
    fun `unassignment phrasing kicks in when newValue is null`() {
        val entry = TicketHistoryEntry(
            actorDisplayName = "Alice",
            createdAt = createdAt,
            field = "assignee",
            oldValue = "Bob",
            newValue = null
        )
        val line = ChangelogSection.formatLine(entry)
        assertTrue(line.startsWith("Alice unassigned the issue"), "Got: $line")
    }

    @Test
    fun `generic field uses canonical changed phrasing with arrow`() {
        val entry = TicketHistoryEntry(
            actorDisplayName = "Dave",
            createdAt = createdAt,
            field = "priority",
            oldValue = "Medium",
            newValue = "High"
        )
        val line = ChangelogSection.formatLine(entry)
        assertTrue(line.contains("Dave changed priority"), "Got: $line")
        assertTrue(line.contains("Medium→High"), "Got: $line")
    }

    @Test
    fun `generic field with only newValue omits arrow`() {
        val entry = TicketHistoryEntry(
            actorDisplayName = "Eve",
            createdAt = createdAt,
            field = "labels",
            oldValue = null,
            newValue = "backend"
        )
        val line = ChangelogSection.formatLine(entry)
        assertTrue(line.contains("Eve changed labels backend"), "Got: $line")
    }

    @Test
    fun `blank actor falls back to Someone`() {
        val entry = TicketHistoryEntry(
            actorDisplayName = "",
            createdAt = createdAt,
            field = "status",
            oldValue = null,
            newValue = "Done"
        )
        val line = ChangelogSection.formatLine(entry)
        assertTrue(line.startsWith("Someone set status to Done"), "Got: $line")
    }

    @Test
    fun `MAX_VISIBLE constant is 8 per spec`() {
        assertEquals(8, ChangelogSection.MAX_VISIBLE)
    }
}
