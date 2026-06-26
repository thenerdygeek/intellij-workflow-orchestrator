package com.workflow.orchestrator.core.ai.prompts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommitMessagePromptBuilderTest {

    private val sampleDiff = "diff --git a/Foo.kt b/Foo.kt\n+fun bar() {}"

    @Test
    fun `conventional is the default format and keeps its markers and exact recent-commits wording`() {
        // Pass recentCommits so the shared context/diff tail is exercised — this pins
        // that the CONVENTIONAL prompt's RECENT COMMITS wording is byte-identical to
        // the pre-refactor text (the silent-regression guard the reviewers flagged).
        val msgs = CommitMessagePromptBuilder.buildMessages(
            diff = sampleDiff,
            ticketId = "PROJ-1",
            recentCommits = listOf("abc123 feat(x): something")
        )
        assertEquals(2, msgs.size)
        val system = msgs[0].content ?: ""
        val user = msgs[1].content ?: ""
        assertTrue(system.contains("Conventional Commits"), "default system message frames Conventional Commits")
        assertTrue(user.contains("TYPES:"), "conventional user message lists TYPES")
        assertTrue(user.contains("ISSUE TYPE → COMMIT TYPE"), "conventional user message has issue-type map")
        assertTrue(user.contains("PROJ-1 type(scope): imperative summary"), "conventional prepends the ticket id")
        // Verbatim — the CONVENTIONAL recent-commits line must NOT drift to the plain phrasing.
        val recentCommitsWording = "RECENT COMMITS (for tone and vocabulary only — " +
            "the FORMAT and TYPES sections above are authoritative):"
        assertTrue(
            user.contains(recentCommitsWording),
            "conventional keeps its exact RECENT COMMITS wording (no silent prompt drift)"
        )
        assertTrue(user.contains("DIFF:"), "diff section present")
    }

    @Test
    fun `plain format drops the type-scope prefix and issue-type map`() {
        val msgs = CommitMessagePromptBuilder.buildMessages(
            diff = sampleDiff,
            ticketId = "PROJ-1",
            format = CommitMessageFormat.PLAIN
        )
        val system = msgs[0].content ?: ""
        val user = msgs[1].content ?: ""
        assertFalse(
            system.contains("Conventional Commits"),
            "plain system message does not mention Conventional Commits"
        )
        assertFalse(user.contains("TYPES:"), "plain user message omits the TYPES reference")
        assertFalse(user.contains("ISSUE TYPE → COMMIT TYPE"), "plain omits the issue-type map")
        assertFalse(user.contains("type(scope)"), "plain forbids the conventional type(scope) prefix anywhere")
        assertTrue(user.contains("DIFF:"), "diff section still present in plain mode")
    }

    @Test
    fun `fromSetting maps plain and defaults conventional`() {
        assertEquals(CommitMessageFormat.PLAIN, CommitMessageFormat.fromSetting("plain"))
        assertEquals(CommitMessageFormat.CONVENTIONAL, CommitMessageFormat.fromSetting("conventional"))
        assertEquals(CommitMessageFormat.CONVENTIONAL, CommitMessageFormat.fromSetting(null))
        assertEquals(CommitMessageFormat.CONVENTIONAL, CommitMessageFormat.fromSetting("garbage"))
    }
}
