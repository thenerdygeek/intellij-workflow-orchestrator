package com.workflow.orchestrator.core.ai.prompts

import com.workflow.orchestrator.core.workflow.TicketContext
import com.workflow.orchestrator.core.workflow.TicketComment

object PrDescriptionPromptBuilder {

    private const val DIFF_CAP = 10_000
    private const val PRIMARY_DESC_CAP = 2_000
    private const val PRIMARY_AC_CAP = 1_000
    private const val PRIMARY_COMMENT_COUNT = 10
    private const val PRIMARY_COMMENT_BODY_CAP = 400
    private const val PRIMARY_BLOCK_CAP = 8_000
    private const val ADDITIONAL_DESC_CAP = 800
    private const val ADDITIONAL_COMMENT_COUNT = 3
    private const val ADDITIONAL_COMMENT_BODY_CAP = 300
    private const val ADDITIONAL_BLOCK_CAP = 3_000
    private const val MAX_ADDITIONAL_TICKETS = 4

    fun build(
        diff: String,
        commitMessages: List<String> = emptyList(),
        tickets: List<TicketContext> = emptyList(),
        sourceBranch: String = "",
        targetBranch: String = ""
    ): String = buildString {
        appendLine("Generate a pull request description in markdown. Output ONLY the markdown — no preamble, no wrapping code blocks.")
        appendLine()

        // ── Structure ──
        appendLine("STRUCTURE (follow exactly):")
        appendLine()
        appendLine("## Summary")
        appendLine("2-3 sentences: what this PR does and why. Written for someone reading a PR review email.")
        appendLine()
        appendLine("## Changes")
        appendLine("- Bullet per logical change, imperative mood, describes behavioral change not file edit")
        appendLine()
        appendLine("## Testing")
        appendLine("- [ ] Checkbox items for what reviewers should verify")
        if (tickets.isNotEmpty()) {
            val primary = tickets[0]
            appendLine()
            appendLine("## Jira")
            appendLine("${primary.key}: ${primary.summary}")
        }
        appendLine()

        // ── Rules ──
        appendLine("RULES:")
        appendLine("- Summary understandable without reading the code")
        appendLine("- Changes bullets: WHAT and WHY, not HOW or file paths")
        appendLine("- Use `backticks` for class/method/config names")
        appendLine("- Testing: specific scenarios, not generic 'test the feature'")
        appendLine("- Be concise — reviewers scan, don't read novels")
        appendLine("- If breaking changes exist, add ## Breaking Changes section")
        appendLine("- Omit empty sections")
        appendLine()
        appendLine("AVOID: file paths in Changes, passive voice, 'This PR' phrasing,")
        appendLine("redundancy between Summary and Changes, wrapping in code blocks")

        // ── Jira tickets context ──
        if (tickets.isNotEmpty()) {
            appendLine()
            appendLine("JIRA TICKETS:")
            appendLine()

            val primary = tickets[0]
            append(buildPrimaryBlock(primary))

            val additional = tickets.drop(1).take(MAX_ADDITIONAL_TICKETS)
            additional.forEach { ticket ->
                appendLine()
                append(buildAdditionalBlock(ticket))
            }
        }

        // ── Branch info ──
        if (sourceBranch.isNotBlank()) {
            appendLine()
            appendLine("BRANCH: $sourceBranch → $targetBranch")
        }

        // ── Commits ──
        if (commitMessages.isNotEmpty()) {
            appendLine()
            appendLine("COMMITS IN THIS PR:")
            commitMessages.take(20).forEach { appendLine("  - $it") }
        }

        // ── Diff ──
        val isDiffAvailable = diff.isNotBlank() && diff != "(diff unavailable)"
        if (isDiffAvailable) {
            appendLine()
            appendLine("DIFF:")
            appendLine("```diff")
            appendLine(if (diff.length > DIFF_CAP) diff.take(DIFF_CAP) + "\n... (diff truncated)" else diff)
            appendLine("```")
        }
    }

    private fun buildPrimaryBlock(ticket: TicketContext): String = buildString {
        var charCount = 0

        /** Use for strings that already include their own '\n'. Length is taken as-is. */
        fun appendTracked(s: String) {
            append(s)
            charCount += s.length
        }

        /** Use for lines WITHOUT a trailing '\n' in the string. Accounts for the newline appendLine adds. */
        fun appendLineTracked(s: String = "") {
            appendLine(s)
            charCount += s.length + 1
        }

        appendLineTracked("Primary: ${ticket.key} — ${ticket.summary}")
        appendLineTracked("Type: ${ticket.issueType ?: "?"} · Status: ${ticket.status ?: "?"} · Priority: ${ticket.priority ?: "?"}")
        appendLineTracked("Assignee: ${ticket.assignee ?: "unassigned"} · Reporter: ${ticket.reporter ?: "unknown"}")

        if (ticket.labels.isNotEmpty()) {
            appendLineTracked("Labels: ${ticket.labels.joinToString(", ")}")
        }
        if (ticket.components.isNotEmpty()) {
            appendLineTracked("Components: ${ticket.components.joinToString(", ")}")
        }
        if (ticket.fixVersions.isNotEmpty()) {
            appendLineTracked("Fix Versions: ${ticket.fixVersions.joinToString(", ")}")
        }

        // Description and acceptance criteria are always included even if they exceed the
        // block cap — only comments are evicted to satisfy the cap. The per-field caps
        // (PRIMARY_DESC_CAP, PRIMARY_AC_CAP) bound them independently.
        val desc = ticket.description
        if (!desc.isNullOrBlank()) {
            appendLineTracked("Description:")
            appendLineTracked(desc.truncateTo(PRIMARY_DESC_CAP))
        }

        val ac = ticket.acceptanceCriteria
        if (!ac.isNullOrBlank()) {
            appendLineTracked("Acceptance Criteria:")
            appendLineTracked(ac.truncateTo(PRIMARY_AC_CAP))
        }

        val sortedComments = ticket.comments.sortedByDescending { it.created }
        val topComments = sortedComments.take(PRIMARY_COMMENT_COUNT)

        if (topComments.isNotEmpty()) {
            val headerLine = "Comments (${topComments.size} most recent):\n"
            if (charCount + headerLine.length <= PRIMARY_BLOCK_CAP) {
                appendTracked(headerLine)
                for (comment in topComments) {
                    val body = comment.body.truncateTo(PRIMARY_COMMENT_BODY_CAP)
                    val line = "[${comment.author} · ${comment.created}] $body\n"
                    if (charCount + line.length > PRIMARY_BLOCK_CAP) break
                    appendTracked(line)
                }
            }
        }
    }

    private fun buildAdditionalBlock(ticket: TicketContext): String = buildString {
        var charCount = 0

        /** Use for strings that already include their own '\n'. Length is taken as-is. */
        fun appendTracked(s: String) {
            append(s)
            charCount += s.length
        }

        /** Use for lines WITHOUT a trailing '\n' in the string. Accounts for the newline appendLine adds. */
        fun appendLineTracked(s: String = "") {
            appendLine(s)
            charCount += s.length + 1
        }

        appendLineTracked("Related ticket: ${ticket.key} — ${ticket.summary}")
        appendLineTracked("Type: ${ticket.issueType ?: "?"} · Status: ${ticket.status ?: "?"}")

        // Description is always included even if it exceeds the block cap — only comments
        // are evicted to satisfy the cap. The per-field cap (ADDITIONAL_DESC_CAP) bounds it independently.
        val desc = ticket.description
        if (!desc.isNullOrBlank()) {
            appendLineTracked("Description:")
            appendLineTracked(desc.truncateTo(ADDITIONAL_DESC_CAP))
        }

        val sortedComments = ticket.comments.sortedByDescending { it.created }
        val topComments = sortedComments.take(ADDITIONAL_COMMENT_COUNT)

        if (topComments.isNotEmpty()) {
            val headerLine = "Top comments (${topComments.size}):\n"
            if (charCount + headerLine.length <= ADDITIONAL_BLOCK_CAP) {
                appendTracked(headerLine)
                for (comment in topComments) {
                    val body = comment.body.truncateTo(ADDITIONAL_COMMENT_BODY_CAP)
                    val line = "[${comment.author} · ${comment.created}] $body\n"
                    if (charCount + line.length > ADDITIONAL_BLOCK_CAP) break
                    appendTracked(line)
                }
            }
        }
    }
}
