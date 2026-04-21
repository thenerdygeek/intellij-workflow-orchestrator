package com.workflow.orchestrator.core.ai.prompts

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.workflow.TicketDetails

/**
 * Enterprise-grade commit message generation using Conventional Commits.
 *
 * Design decisions:
 * - System message frames the role (consistent formatting, no hallucination)
 * - User message provides diff + context (code intelligence, recent history)
 * - "Why not what" emphasis in body bullets (enterprise traceability)
 * - Ticket ID prefix for Jira integration
 * - Recent commits for style matching (matches project conventions automatically)
 * - No diff truncation — Sourcegraph supports 150K input tokens
 */
object CommitMessagePromptBuilder {

    /** System message — role framing for consistent, parseable output. */
    private const val SYSTEM_MESSAGE = """You are an expert at writing git commit messages following the Conventional Commits specification. You analyze diffs and produce clear, accurate commit messages that help teams understand what changed and why.

Output ONLY the raw commit message. No commentary, no markdown code blocks, no explanation."""

    fun buildMessages(
        diff: String,
        ticketId: String = "",
        filesSummary: String = "",
        recentCommits: List<String> = emptyList(),
        codeContext: String = "",
        ticketDetails: TicketDetails? = null
    ): List<ChatMessage> {
        val userMessage = buildUserMessage(diff, ticketId, filesSummary, recentCommits, codeContext, ticketDetails)
        return listOf(
            ChatMessage(role = "system", content = SYSTEM_MESSAGE),
            ChatMessage(role = "user", content = userMessage)
        )
    }

    private fun buildUserMessage(
        diff: String,
        ticketId: String,
        filesSummary: String,
        recentCommits: List<String>,
        codeContext: String,
        ticketDetails: TicketDetails?
    ): String = buildString {
        appendLine("Generate a commit message for these changes.")
        appendLine()

        // ── Format ──
        appendLine("FORMAT:")
        if (ticketId.isNotBlank()) {
            appendLine("$ticketId type(scope): imperative summary (max 72 chars total)")
        } else {
            appendLine("type(scope): imperative summary (max 72 chars)")
        }
        appendLine()
        appendLine("- Body bullet per logical change, explaining WHAT changed and WHY")
        appendLine("- Group related edits into one bullet")
        appendLine("- If trivial (typo, import, version bump), one short bullet is fine")
        appendLine()

        // ── Type reference ──
        appendLine("TYPES: feat (→ MINOR) | fix (→ PATCH) | refactor | perf | test | docs | style | build | ci | chore")
        appendLine("BREAKING CHANGE in footer → MAJOR version bump")
        appendLine()

        // ── Rules ──
        appendLine("RULES:")
        appendLine("- scope = domain area (auth, billing, pr-list), NOT file paths")
        appendLine("- Imperative mood: 'add' not 'added', 'fix' not 'fixed'")
        appendLine("- Body bullets explain WHY the change was made, not just what lines changed")
        appendLine("- AVOID: passive voice, 'This commit/change' phrasing, repeating type in summary")
        if (ticketId.isBlank()) {
            appendLine("- No ticket is active; do NOT prepend any ticket ID to the summary, even if RECENT COMMITS show one.")
        } else {
            appendLine("- Use ONLY the ticket ID from TICKET CONTEXT. Do NOT copy ticket IDs from RECENT COMMITS — those are style/tone references, not content.")
        }
        appendLine()

        // ── Ticket context (authoritative ticket — overrides any ID in RECENT COMMITS) ──
        if (ticketDetails != null) {
            appendLine("TICKET CONTEXT:")
            appendLine("  ${ticketDetails.key} — ${ticketDetails.summary}")
            val desc = ticketDetails.description?.trim()
            if (!desc.isNullOrBlank()) {
                val truncated = if (desc.length > 500) desc.take(500) + "…" else desc
                appendLine("  $truncated")
            }
            appendLine()
        }

        // ── Recent commits (style reference) ──
        if (recentCommits.isNotEmpty()) {
            appendLine("RECENT COMMITS (match this project's style):")
            recentCommits.forEach { appendLine("  $it") }
            appendLine()
        }

        // ── Code intelligence ──
        if (codeContext.isNotBlank()) {
            appendLine("CODE CONTEXT:")
            appendLine(codeContext)
            appendLine()
        }

        // ── Changed files ──
        if (filesSummary.isNotBlank()) {
            appendLine("CHANGED FILES: $filesSummary")
            appendLine()
        }

        // ── Diff (no truncation — Sourcegraph supports 150K input) ──
        appendLine("DIFF:")
        append(diff)
    }
}
