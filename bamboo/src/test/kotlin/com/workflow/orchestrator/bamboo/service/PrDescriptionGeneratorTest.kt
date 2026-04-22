package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.core.workflow.TicketComment
import com.workflow.orchestrator.core.workflow.TicketContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PrDescriptionGenerator].
 *
 * The generate() and generateTitle() methods touch IntelliJ platform APIs
 * (ChangeListManager, Git, PrService) so they cannot be tested in a pure unit
 * context without the IDE sandbox.  The internal helpers that carry the
 * business logic — [PrDescriptionGenerator.buildFallbackDescription] — are
 * marked `internal` and tested here directly.  The full cascade is covered
 * by integration / sandbox tests in a later phase.
 */
class PrDescriptionGeneratorTest {

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun ticket(
        key: String,
        summary: String,
        description: String? = null,
        comments: List<TicketComment> = emptyList()
    ) = TicketContext(
        key = key,
        summary = summary,
        description = description,
        status = "In Progress",
        priority = "Medium",
        issueType = "Story",
        assignee = "dev",
        reporter = "pm",
        comments = comments
    )

    private fun comment(author: String, created: String, body: String) =
        TicketComment(author = author, created = created, body = body)

    // ── buildFallbackDescription — empty tickets ─────────────────────────

    @Test
    fun `fallback with empty tickets shows only commits and branch`() {
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = emptyList(),
            commits = listOf("abc1234 Add login endpoint", "def5678 Fix null check"),
            branch = "feature/login"
        )

        assertFalse(result.contains("Related tickets"), "Should not contain related tickets section")
        // No ticket key header (e.g. "## PROJ-xx:")
        assertFalse(result.contains(Regex("## [A-Z]+-\\d+")), "Should not contain any ticket-key header")
        assertTrue(result.contains("Add login endpoint"))
        assertTrue(result.contains("Fix null check"))
        assertTrue(result.contains("**Branch:** feature/login"))
    }

    @Test
    fun `fallback with empty tickets and empty commits shows only branch`() {
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = emptyList(),
            commits = emptyList(),
            branch = "feature/empty"
        )

        assertFalse(result.contains("Related tickets"))
        assertFalse(result.contains("## Commits"))
        assertTrue(result.contains("**Branch:** feature/empty"))
    }

    // ── buildFallbackDescription — single primary ticket ─────────────────

    @Test
    fun `fallback with single primary ticket emits primary header`() {
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = listOf(ticket("PROJ-42", "Implement login")),
            commits = listOf("abc1234 Implement login screen"),
            branch = "feature/PROJ-42"
        )

        assertTrue(result.contains("## PROJ-42: Implement login"))
        assertTrue(result.contains("## Commits"))
        assertTrue(result.contains("- abc1234 Implement login screen"))
        assertTrue(result.contains("**Branch:** feature/PROJ-42"))
        assertFalse(result.contains("## Related tickets"))
    }

    @Test
    fun `fallback includes primary description truncated to 500 chars`() {
        val longDesc = "A".repeat(600)
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = listOf(ticket("PROJ-1", "Summary", description = longDesc)),
            commits = emptyList(),
            branch = "feature/x"
        )

        // Should contain exactly 500 A-chars, not 600
        val truncated = "A".repeat(500)
        assertTrue(result.contains(truncated))
        assertFalse(result.contains("A".repeat(501)))
    }

    @Test
    fun `fallback omits description section when description is blank`() {
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = listOf(ticket("PROJ-10", "Summary", description = "   ")),
            commits = emptyList(),
            branch = "feature/x"
        )

        // Blank description — only the ticket header and branch should appear
        assertTrue(result.contains("## PROJ-10: Summary"))
        assertTrue(result.contains("**Branch:** feature/x"))
        // The blank description itself should not be rendered as extra whitespace lines
        assertFalse(result.contains("Description:"))
    }

    @Test
    fun `fallback omits description when null`() {
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = listOf(ticket("PROJ-11", "Summary", description = null)),
            commits = emptyList(),
            branch = "feature/x"
        )

        assertTrue(result.contains("## PROJ-11: Summary"))
        assertFalse(result.contains("Description:"))
    }

    // ── buildFallbackDescription — primary + additional ──────────────────

    @Test
    fun `fallback with multiple tickets shows primary header and related section`() {
        val primary = ticket("PROJ-1", "Primary story")
        val extra1 = ticket("PROJ-2", "Related task A")
        val extra2 = ticket("PROJ-3", "Related task B")

        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = listOf(primary, extra1, extra2),
            commits = listOf("commit1"),
            branch = "feature/multi"
        )

        assertTrue(result.contains("## PROJ-1: Primary story"))
        assertTrue(result.contains("## Related tickets"))
        assertTrue(result.contains("- PROJ-2: Related task A"))
        assertTrue(result.contains("- PROJ-3: Related task B"))
        assertTrue(result.contains("## Commits"))
        assertTrue(result.contains("**Branch:** feature/multi"))
    }

    @Test
    fun `fallback omits related tickets section when only one ticket`() {
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = listOf(ticket("PROJ-1", "Only ticket")),
            commits = emptyList(),
            branch = "feature/solo"
        )

        assertFalse(result.contains("Related tickets"))
    }

    @Test
    fun `fallback omits commits section when commits list is empty`() {
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = listOf(ticket("PROJ-5", "No commits")),
            commits = emptyList(),
            branch = "feature/nocommits"
        )

        assertFalse(result.contains("## Commits"))
        assertTrue(result.contains("**Branch:** feature/nocommits"))
    }

    // ── ordering ─────────────────────────────────────────────────────────

    @Test
    fun `fallback section order is primary - related - commits - branch`() {
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = listOf(
                ticket("A-1", "Primary"),
                ticket("A-2", "Related")
            ),
            commits = listOf("commit-x"),
            branch = "feature/order"
        )

        val primaryIdx = result.indexOf("## A-1")
        val relatedIdx = result.indexOf("## Related tickets")
        val commitsIdx = result.indexOf("## Commits")
        val branchIdx = result.indexOf("**Branch:**")

        assertTrue(primaryIdx < relatedIdx, "primary before related")
        assertTrue(relatedIdx < commitsIdx, "related before commits")
        assertTrue(commitsIdx < branchIdx, "commits before branch")
    }
}
