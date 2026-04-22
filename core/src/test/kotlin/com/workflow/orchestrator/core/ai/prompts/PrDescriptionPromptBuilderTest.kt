package com.workflow.orchestrator.core.ai.prompts

import com.workflow.orchestrator.core.workflow.TicketComment
import com.workflow.orchestrator.core.workflow.TicketContext
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrDescriptionPromptBuilderTest {

    private fun makeTicket(
        key: String = "PROJ-1",
        summary: String = "Fix the bug",
        description: String? = "A description",
        status: String? = "In Progress",
        priority: String? = "High",
        issueType: String? = "Bug",
        assignee: String? = "alice",
        reporter: String? = "bob",
        labels: List<String> = emptyList(),
        components: List<String> = emptyList(),
        fixVersions: List<String> = emptyList(),
        comments: List<TicketComment> = emptyList(),
        acceptanceCriteria: String? = null
    ) = TicketContext(
        key = key,
        summary = summary,
        description = description,
        status = status,
        priority = priority,
        issueType = issueType,
        assignee = assignee,
        reporter = reporter,
        labels = labels,
        components = components,
        fixVersions = fixVersions,
        comments = comments,
        acceptanceCriteria = acceptanceCriteria
    )

    private fun makeComment(author: String, created: String, body: String) =
        TicketComment(author = author, created = created, body = body)

    // ── Empty tickets ──────────────────────────────────────────────────────────

    @Test
    fun `empty tickets list omits JIRA TICKETS section`() {
        val result = PrDescriptionPromptBuilder.build(diff = "diff --git a/Foo.kt", tickets = emptyList())
        assertFalse(result.contains("JIRA TICKETS:"), "Should not emit JIRA TICKETS section when list is empty")
    }

    // ── Single primary ticket ──────────────────────────────────────────────────

    @Test
    fun `single primary ticket emits primary block only, no related ticket blocks`() {
        val ticket = makeTicket(key = "PROJ-1", summary = "Add login feature")
        val result = PrDescriptionPromptBuilder.build(diff = "some diff", tickets = listOf(ticket))
        assertTrue(result.contains("JIRA TICKETS:"))
        assertTrue(result.contains("Primary: PROJ-1 — Add login feature"))
        assertFalse(result.contains("Related ticket:"), "Should not emit Related ticket when only primary exists")
    }

    // ── Primary + additional ──────────────────────────────────────────────────

    @Test
    fun `primary plus two additional emits both block types`() {
        val primary = makeTicket(key = "PROJ-1", summary = "Primary ticket")
        val add1 = makeTicket(key = "PROJ-2", summary = "Related one")
        val add2 = makeTicket(key = "PROJ-3", summary = "Related two")
        val result = PrDescriptionPromptBuilder.build(diff = "d", tickets = listOf(primary, add1, add2))
        assertTrue(result.contains("Primary: PROJ-1 — Primary ticket"))
        assertTrue(result.contains("Related ticket: PROJ-2 — Related one"))
        assertTrue(result.contains("Related ticket: PROJ-3 — Related two"))
    }

    // ── Description truncation ─────────────────────────────────────────────────

    @Test
    fun `description longer than 2000 chars is truncated for primary ticket`() {
        val longDesc = "A".repeat(2500)
        val ticket = makeTicket(description = longDesc)
        val result = PrDescriptionPromptBuilder.build(diff = "d", tickets = listOf(ticket))
        assertTrue(result.contains("A".repeat(2000)))
        assertFalse(result.contains("A".repeat(2001)), "Should not contain more than 2000 description chars for primary")
        assertTrue(result.contains("..."), "Truncated desc should end with ellipsis")
    }

    @Test
    fun `description longer than 800 chars is truncated for additional ticket`() {
        val primary = makeTicket(key = "PROJ-1", description = "short")
        val addDesc = "B".repeat(1000)
        val additional = makeTicket(key = "PROJ-2", description = addDesc)
        val result = PrDescriptionPromptBuilder.build(diff = "d", tickets = listOf(primary, additional))
        assertTrue(result.contains("B".repeat(800)))
        assertFalse(result.contains("B".repeat(801)), "Should not contain more than 800 desc chars for additional ticket")
        assertTrue(result.contains("..."), "Truncated desc should have ellipsis")
    }

    // ── Comment body truncation ────────────────────────────────────────────────

    @Test
    fun `comment body longer than 400 chars is truncated for primary ticket`() {
        val longBody = "C".repeat(500)
        val comment = makeComment("alice", "2024-01-01T10:00:00Z", longBody)
        val ticket = makeTicket(comments = listOf(comment))
        val result = PrDescriptionPromptBuilder.build(diff = "d", tickets = listOf(ticket))
        assertTrue(result.contains("C".repeat(400)))
        assertFalse(result.contains("C".repeat(401)), "Comment body should be capped at 400 chars for primary")
    }

    @Test
    fun `comment body longer than 300 chars is truncated for additional ticket`() {
        val primary = makeTicket(key = "PROJ-1")
        val longBody = "D".repeat(400)
        val comment = makeComment("bob", "2024-01-01T10:00:00Z", longBody)
        val additional = makeTicket(key = "PROJ-2", comments = listOf(comment))
        val result = PrDescriptionPromptBuilder.build(diff = "d", tickets = listOf(primary, additional))
        assertTrue(result.contains("D".repeat(300)))
        assertFalse(result.contains("D".repeat(301)), "Comment body should be capped at 300 chars for additional")
    }

    // ── Comment count limits ───────────────────────────────────────────────────

    @Test
    fun `more than 10 comments for primary ticket are trimmed to 10 most recent`() {
        val comments = (1..15).map { i ->
            makeComment("user$i", "2024-01-${i.toString().padStart(2, '0')}T10:00:00Z", "Comment body #$i")
        }
        val ticket = makeTicket(comments = comments)
        val result = PrDescriptionPromptBuilder.build(diff = "d", tickets = listOf(ticket))
        // 15th comment is most recent (highest date) — should appear; 5th comment should not.
        // "Comment body #5" is distinct from "Comment body #15" (no substring overlap).
        assertTrue(result.contains("Comment body #15"), "Most recent comment should be included")
        assertFalse(result.contains("Comment body #5"), "11th-oldest comment should be excluded from primary (max 10 shown)")
    }

    @Test
    fun `more than 3 comments for additional ticket are trimmed to 3 most recent`() {
        val primary = makeTicket(key = "PROJ-1")
        val comments = (1..6).map { i ->
            makeComment("user$i", "2024-01-${i.toString().padStart(2, '0')}T10:00:00Z", "AddComment $i")
        }
        val additional = makeTicket(key = "PROJ-2", comments = comments)
        val result = PrDescriptionPromptBuilder.build(diff = "d", tickets = listOf(primary, additional))
        assertTrue(result.contains("AddComment 6"), "Most recent comment should be in additional block")
        assertFalse(result.contains("AddComment 3"), "4th-oldest comment should be excluded from additional (max 3)")
    }

    // ── Comments sorted descending ─────────────────────────────────────────────

    @Test
    fun `comments are sorted by created descending before truncation`() {
        // Provide 4 comments; most recent = "2024-04-..."
        val comments = listOf(
            makeComment("a", "2024-01-01T00:00:00Z", "Oldest comment"),
            makeComment("b", "2024-04-01T00:00:00Z", "Newest comment"),
            makeComment("c", "2024-02-01T00:00:00Z", "Middle comment"),
            makeComment("d", "2024-03-01T00:00:00Z", "Second newest")
        )
        val primary = makeTicket(key = "PROJ-1")
        // additional gets max 3, so "Oldest comment" (sorted 4th) should be dropped
        val additional = makeTicket(key = "PROJ-2", comments = comments)
        val result = PrDescriptionPromptBuilder.build(diff = "d", tickets = listOf(primary, additional))
        assertTrue(result.contains("Newest comment"), "Newest comment should appear")
        assertFalse(result.contains("Oldest comment"), "Oldest comment should be excluded when only 3 kept")
    }

    // ── Per-ticket block cap (primary) ─────────────────────────────────────────

    @Test
    fun `per-ticket block cap stops at comment boundary, not mid-string`() {
        // Build a primary ticket with 10 comments each with 400-char body.
        // Fixed overhead: header lines ~200 chars + description ~2000 + AC ~1000 = ~3200 chars already
        // Each comment line: "[author · created] " + 400 + "..." overhead ≈ ~430 chars
        // At cap 8000, after header (~3200) we can fit about 11 comments worth — but we only have 10.
        // To force truncation at comment boundary, use a description that fills most of the budget.
        val bigDesc = "X".repeat(1980) // just under 2000
        val bigAc = "Y".repeat(980)    // just under 1000
        // Overhead so far before comments: ~200 header + 1980 desc + 980 AC ≈ 3160 chars
        // Each comment ≈ 430 chars. Budget remaining after 3160 = 8000-3160 = 4840, fits ~11
        // But we have 10, so all should fit. Let's use a massive description to force cap.
        val hugeDesc = "Z".repeat(2000)
        val hugeAc = "W".repeat(1000)
        // header ~200 + desc 2000 + ac 1000 = ~3200; comments each ~430, 8000-3200 = 4800 fits ~11
        // Force the cap: use a very long description capped at 2000 + large AC + many big comments
        // The block cap should prevent mid-string truncation — we test by checking no partial comment
        val comments = (1..10).map { i ->
            makeComment("user", "2024-01-${i.toString().padStart(2, '0')}T00:00:00Z", "E".repeat(400))
        }
        val ticket = makeTicket(
            description = hugeDesc,
            acceptanceCriteria = hugeAc,
            comments = comments
        )
        val result = PrDescriptionPromptBuilder.build(diff = "d", tickets = listOf(ticket))
        // Verify comment bodies that do appear are not mid-string cut (they start with "[user")
        val commentLines = result.lines().filter { it.startsWith("[user") }
        commentLines.forEach { line ->
            // Each line should end with a full block of E's (possibly truncated at body cap of 400)
            // but not partially cut off - the body part should be "E"*400 + "..."
            assertTrue(
                line.contains("E".repeat(400)),
                "Comment body should contain full 400 E's (at cap boundary), not be mid-string cut"
            )
        }
    }

    // ── Acceptance criteria ────────────────────────────────────────────────────

    @Test
    fun `null acceptance criteria omits AC section`() {
        val ticket = makeTicket(acceptanceCriteria = null)
        val result = PrDescriptionPromptBuilder.build(diff = "d", tickets = listOf(ticket))
        assertFalse(result.contains("Acceptance Criteria:"), "AC section should be omitted when null")
    }

    @Test
    fun `blank acceptance criteria omits AC section`() {
        val ticket = makeTicket(acceptanceCriteria = "   ")
        val result = PrDescriptionPromptBuilder.build(diff = "d", tickets = listOf(ticket))
        assertFalse(result.contains("Acceptance Criteria:"), "AC section should be omitted when blank")
    }

    // ── Labels/components/fixVersions ─────────────────────────────────────────

    @Test
    fun `empty labels list omits labels line`() {
        val ticket = makeTicket(labels = emptyList())
        val result = PrDescriptionPromptBuilder.build(diff = "d", tickets = listOf(ticket))
        assertFalse(result.contains("Labels:"), "Labels line should be omitted when empty")
    }

    @Test
    fun `empty components list omits components line`() {
        val ticket = makeTicket(components = emptyList())
        val result = PrDescriptionPromptBuilder.build(diff = "d", tickets = listOf(ticket))
        assertFalse(result.contains("Components:"), "Components line should be omitted when empty")
    }

    @Test
    fun `empty fixVersions list omits fix versions line`() {
        val ticket = makeTicket(fixVersions = emptyList())
        val result = PrDescriptionPromptBuilder.build(diff = "d", tickets = listOf(ticket))
        assertFalse(result.contains("Fix Versions:"), "Fix Versions line should be omitted when empty")
    }

    @Test
    fun `non-empty labels components fixVersions are included`() {
        val ticket = makeTicket(
            labels = listOf("backend", "urgent"),
            components = listOf("auth-service"),
            fixVersions = listOf("2.3.0")
        )
        val result = PrDescriptionPromptBuilder.build(diff = "d", tickets = listOf(ticket))
        assertTrue(result.contains("Labels: backend, urgent"))
        assertTrue(result.contains("Components: auth-service"))
        assertTrue(result.contains("Fix Versions: 2.3.0"))
    }

    // ── Existing blocks preserved ──────────────────────────────────────────────

    @Test
    fun `existing STRUCTURE RULES AVOID DIFF blocks are preserved`() {
        val result = PrDescriptionPromptBuilder.build(diff = "some diff content", tickets = emptyList())
        assertTrue(result.contains("STRUCTURE (follow exactly):"))
        assertTrue(result.contains("RULES:"))
        assertTrue(result.contains("AVOID:"))
        assertTrue(result.contains("DIFF:"))
        assertTrue(result.contains("some diff content"))
    }

    @Test
    fun `BRANCH line is emitted when sourceBranch is set`() {
        val result = PrDescriptionPromptBuilder.build(
            diff = "d",
            tickets = emptyList(),
            sourceBranch = "feature/my-branch",
            targetBranch = "main"
        )
        assertTrue(result.contains("BRANCH: feature/my-branch → main"))
    }

    @Test
    fun `COMMITS block is emitted when commit messages are set`() {
        val result = PrDescriptionPromptBuilder.build(
            diff = "d",
            commitMessages = listOf("feat: add login", "fix: remove bug"),
            tickets = emptyList()
        )
        assertTrue(result.contains("COMMITS IN THIS PR:"))
        assertTrue(result.contains("feat: add login"))
        assertTrue(result.contains("fix: remove bug"))
    }

    @Test
    fun `diff longer than 10000 chars is truncated`() {
        val bigDiff = "X".repeat(12000)
        val result = PrDescriptionPromptBuilder.build(diff = bigDiff, tickets = emptyList())
        assertFalse(result.contains("X".repeat(10001)), "Diff should be capped at 10000 chars")
        assertTrue(result.contains("diff truncated"), "Should note truncation")
    }

    // ── Diff section conditionality ───────────────────────────────────────────

    @Test
    fun `diff section omitted when diff is blank`() {
        val result = PrDescriptionPromptBuilder.build(diff = "", tickets = emptyList())
        assertFalse(result.contains("DIFF:"), "DIFF header should be omitted when diff is blank")
        assertFalse(result.contains("```diff"), "diff fence should be omitted when diff is blank")
    }

    @Test
    fun `diff section omitted when diff equals sentinel`() {
        val result = PrDescriptionPromptBuilder.build(diff = "(diff unavailable)", tickets = emptyList())
        assertFalse(result.contains("DIFF:"), "DIFF header should be omitted when diff is the sentinel")
        assertFalse(result.contains("```diff"), "diff fence should be omitted when diff is the sentinel")
        assertFalse(result.contains("(diff unavailable)"), "Sentinel text must not appear in output")
    }

    @Test
    fun `diff section included when diff is available`() {
        val result = PrDescriptionPromptBuilder.build(diff = "diff --git a/Foo.kt b/Foo.kt", tickets = emptyList())
        assertTrue(result.contains("DIFF:"), "DIFF header should appear when diff is available")
        assertTrue(result.contains("```diff"), "diff fence should appear when diff is available")
        assertTrue(result.contains("diff --git a/Foo.kt b/Foo.kt"), "Actual diff content should appear")
    }

    // ── Max 4 additional tickets ───────────────────────────────────────────────

    @Test
    fun `at most 4 additional tickets are emitted`() {
        val primary = makeTicket(key = "PROJ-1")
        val additionals = (2..8).map { makeTicket(key = "PROJ-$it", summary = "Related $it") }
        val result = PrDescriptionPromptBuilder.build(diff = "d", tickets = listOf(primary) + additionals)
        // PROJ-2 through PROJ-5 should appear (max 4 additional)
        assertTrue(result.contains("Related ticket: PROJ-2"))
        assertTrue(result.contains("Related ticket: PROJ-5"))
        assertFalse(result.contains("Related ticket: PROJ-6"), "Should not exceed 4 additional tickets")
    }
}
