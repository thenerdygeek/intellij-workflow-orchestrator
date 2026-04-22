package com.workflow.orchestrator.pullrequest.service

import com.workflow.orchestrator.core.workflow.TicketComment
import com.workflow.orchestrator.core.workflow.TicketContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PrDescriptionGenerator].
 *
 * The generate() and generateTitle() methods touch IntelliJ platform APIs
 * (ChangeListManager, Git, PrService) so they cannot be tested in a pure unit
 * context without the IDE sandbox. The internal helper [buildFallbackDescription]
 * carries the Tier-3 logic and is tested here directly.
 *
 * Tier-3 shape must mirror the Tier-1/2 LLM prompt structure (see
 * [com.workflow.orchestrator.core.ai.prompts.PrDescriptionPromptBuilder]) so the
 * author editing a fallback description sees the same 7-section skeleton the
 * AI would have produced.
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

    // ── section skeleton ─────────────────────────────────────────────────

    @Test
    fun `fallback always emits the seven-section skeleton in order`() {
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = listOf(ticket("PROJ-1", "Something")),
            commits = listOf("abc1234 Some commit"),
            branch = "feature/x"
        )

        val idxSummary = result.indexOf("## Summary")
        val idxContext = result.indexOf("## Context")
        val idxChanges = result.indexOf("## Changes")
        val idxTesting = result.indexOf("## Testing")
        val idxRisks = result.indexOf("## Risks & Rollback")
        val idxFeedback = result.indexOf("## Feedback Requested")
        val idxJira = result.indexOf("## Jira")
        val idxBranch = result.indexOf("**Branch:**")

        assertTrue(idxSummary >= 0, "## Summary missing")
        assertTrue(idxContext > idxSummary, "## Context must follow ## Summary")
        assertTrue(idxChanges > idxContext, "## Changes must follow ## Context")
        assertTrue(idxTesting > idxChanges, "## Testing must follow ## Changes")
        assertTrue(idxRisks > idxTesting, "## Risks & Rollback must follow ## Testing")
        assertTrue(idxFeedback > idxRisks, "## Feedback Requested must follow ## Risks & Rollback")
        assertTrue(idxJira > idxFeedback, "## Jira must follow ## Feedback Requested")
        assertTrue(idxBranch > idxJira, "**Branch:** must be last")
    }

    // ── Summary population ──────────────────────────────────────────────

    @Test
    fun `fallback Summary pulls primary ticket key and summary when present`() {
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = listOf(ticket("PROJ-42", "Implement login")),
            commits = emptyList(),
            branch = "feature/login"
        )
        assertTrue(result.contains("PROJ-42: Implement login"), "Primary ticket summary should populate ## Summary")
    }

    @Test
    fun `fallback Summary includes primary description truncated to 500 chars`() {
        val longDesc = "A".repeat(600)
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = listOf(ticket("PROJ-1", "Summary", description = longDesc)),
            commits = emptyList(),
            branch = "feature/x"
        )
        assertTrue(result.contains("A".repeat(500)), "First 500 chars of description should appear")
        assertFalse(result.contains("A".repeat(501)), "Description should be capped at 500 chars")
    }

    @Test
    fun `fallback Summary shows placeholder when no primary ticket available`() {
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = emptyList(),
            commits = listOf("abc1234 some work"),
            branch = "feature/x"
        )
        assertTrue(
            result.contains("AI generation unavailable"),
            "Summary should show an obvious placeholder when no ticket context exists"
        )
    }

    // ── Changes population ──────────────────────────────────────────────

    @Test
    fun `fallback Changes section uses commits as a starting point when available`() {
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = listOf(ticket("PROJ-1", "s")),
            commits = listOf("abc1234 Add login endpoint", "def5678 Fix null check"),
            branch = "feature/x"
        )
        assertTrue(result.contains("Add login endpoint"), "Commit subjects should appear in Changes")
        assertTrue(result.contains("Fix null check"))
        assertTrue(
            result.contains("Derived from commit messages"),
            "Author should be told commits are a starting point, not the final form"
        )
    }

    @Test
    fun `fallback Changes shows placeholder when no commits provided`() {
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = listOf(ticket("PROJ-1", "s")),
            commits = emptyList(),
            branch = "feature/x"
        )
        // Extract just the Changes section
        val changesIdx = result.indexOf("## Changes")
        val testingIdx = result.indexOf("## Testing")
        val changesSection = result.substring(changesIdx, testingIdx)
        assertTrue(
            changesSection.contains("AI generation unavailable"),
            "Changes section should show placeholder when no commits exist"
        )
    }

    // ── Placeholder sections ─────────────────────────────────────────────

    @Test
    fun `fallback Testing section always carries an obvious placeholder`() {
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = listOf(ticket("PROJ-1", "s")),
            commits = listOf("c"),
            branch = "b"
        )
        val testingIdx = result.indexOf("## Testing")
        val risksIdx = result.indexOf("## Risks & Rollback")
        val testingSection = result.substring(testingIdx, risksIdx)
        assertTrue(testingSection.contains("AI generation unavailable"))
    }

    @Test
    fun `fallback Feedback Requested carries a guiding placeholder`() {
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = listOf(ticket("PROJ-1", "s")),
            commits = listOf("c"),
            branch = "b"
        )
        val feedbackIdx = result.indexOf("## Feedback Requested")
        val jiraIdx = result.indexOf("## Jira")
        val section = result.substring(
            feedbackIdx,
            if (jiraIdx > 0) jiraIdx else result.length
        )
        assertTrue(
            section.contains("most valuable"),
            "Feedback Requested placeholder should guide the author toward specificity"
        )
    }

    // ── Jira listing ─────────────────────────────────────────────────────

    @Test
    fun `fallback Jira section lists primary plus all related tickets`() {
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = listOf(
                ticket("PROJ-1", "Primary"),
                ticket("PROJ-2", "Related A"),
                ticket("PROJ-3", "Related B")
            ),
            commits = emptyList(),
            branch = "feature/multi"
        )
        assertTrue(result.contains("- PROJ-1: Primary"))
        assertTrue(result.contains("- PROJ-2: Related A"))
        assertTrue(result.contains("- PROJ-3: Related B"))
    }

    @Test
    fun `fallback omits Jira section entirely when no tickets provided`() {
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = emptyList(),
            commits = listOf("c"),
            branch = "b"
        )
        assertFalse(result.contains("## Jira"), "## Jira should not appear when no tickets exist")
    }

    // ── Branch line ──────────────────────────────────────────────────────

    @Test
    fun `fallback ends with Branch line`() {
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = emptyList(),
            commits = emptyList(),
            branch = "feature/empty"
        )
        assertTrue(result.contains("**Branch:** feature/empty"))
        assertTrue(result.trimEnd().endsWith("**Branch:** feature/empty"), "Branch line should be the final content")
    }

    // ── Spacing sanity check ─────────────────────────────────────────────

    @Test
    fun `fallback does not emit triple-blank-line paragraphs`() {
        val result = PrDescriptionGenerator.buildFallbackDescription(
            tickets = listOf(ticket("PROJ-1", "Some summary", description = "A real description")),
            commits = listOf("abc1234 Some commit"),
            branch = "feature/spacing"
        )
        assertFalse(
            result.contains("\n\n\n"),
            "Output must not contain three consecutive newlines (double blank lines)"
        )
    }
}
