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
        val isDiffAvailable = diff.isNotBlank() && diff != "(diff unavailable)"

        appendLine("Generate a pull request description in markdown. Output ONLY the markdown — no preamble, no wrapping code blocks.")
        appendLine()

        // ── Structure ──
        // Seven sections, ordered by reviewer cognitive load: tl;dr → why → what → how →
        // verification → risk → feedback focus. Derived from consensus across Google eng
        // practices, Microsoft playbook, SoundCloud, Graphite, and arXiv 2602.14611.
        appendLine("STRUCTURE (follow exactly, in this order; omit a section only when it would be empty or not applicable):")
        appendLine()
        appendLine("## Summary")
        appendLine("1–2 paragraphs. First sentence is a self-contained tl;dr: what this PR delivers and why, understandable without reading the code. Add a second sentence or short paragraph for the user-visible or developer-visible outcome.")
        appendLine()
        appendLine("## Context")
        appendLine("The background a reviewer needs but would NOT have from the diff alone:")
        appendLine("- Problem being solved (user pain, incident, regression, new requirement)")
        appendLine("- Why this approach was chosen over alternatives (if non-obvious)")
        appendLine("- Relevant prior work, design docs, or tickets")
        appendLine("- Constraints or deadlines that shaped the scope")
        appendLine("Skip this section entirely for mechanical/trivial PRs (renames, typo fixes, dependency bumps with no behavior change).")
        appendLine()
        appendLine("## Changes")
        appendLine("Bullet list of logical, behavior-level deltas in imperative mood. Each bullet describes a capability, contract, or behavior change — NOT a file edit. Group related bullets. Use `backticks` for class/method/config names.")
        appendLine()
        appendLine("## Implementation Notes")
        appendLine("Key design decisions, trade-offs, and alternatives considered. Call out anything non-obvious a reviewer should understand BEFORE reading the diff, or any area that deserves extra scrutiny. This is where you explain the HOW at a design level (not line-by-line). Omit for mechanical PRs.")
        appendLine()
        appendLine("## Testing")
        appendLine("Specific scenarios the reviewer should verify — not generic \"test the feature\". Format as a checklist:")
        appendLine("- [ ] Concrete scenario 1 (inputs, expected outcome)")
        appendLine("- [ ] Concrete scenario 2")
        appendLine("Include what was exercised locally, new test files added, and known gaps.")
        appendLine()
        appendLine("## Risks & Rollback")
        appendLine("Include a line for each that applies:")
        appendLine("- **Breaking changes:** API/schema/config surface that consumers depend on")
        appendLine("- **Migrations:** one-way data or schema changes")
        appendLine("- **Feature flags:** gating and default state")
        appendLine("- **Performance:** expected latency/throughput impact")
        appendLine("- **Rollback plan:** how to undo if this misbehaves in production")
        appendLine("Omit the whole section if none of these apply.")
        appendLine()
        appendLine("## Feedback Requested")
        appendLine("One specific sentence stating what kind of review is most valuable: e.g. \"Correctness of the retry/backoff logic under concurrent failures\", \"API naming and error shape\", \"Security review of the token scope\", \"LGTM unless you spot issues\". (Research: specifying feedback type increases merge rate by 64–72%.)")
        if (tickets.isNotEmpty()) {
            val primary = tickets[0]
            appendLine()
            appendLine("## Jira")
            appendLine("${primary.key}: ${primary.summary}")
        }
        appendLine()

        // ── Rules ──
        appendLine("RULES:")
        appendLine("- First sentence of Summary must stand alone — a reader should understand the change from that sentence without reading further.")
        appendLine("- Use imperative mood (\"Add\", \"Remove\", \"Fix\"), not \"This PR adds…\".")
        appendLine("- `## Changes` = behavior deltas, not file edits or line counts.")
        appendLine("- `## Implementation Notes` = WHY decisions were made, not WHAT was written.")
        appendLine("- Use `backticks` for class/method/config/env-var names.")
        appendLine("- Testing bullets must be specific scenarios, not \"test the feature works\".")
        appendLine("- Prefer complete sentences over clipped fragments in Summary/Context.")
        appendLine("- Omit empty sections; do not include placeholder \"N/A\" headings.")
        appendLine("- For breaking changes, always state the migration path.")
        appendLine()
        appendLine("AVOID:")
        appendLine("- Generic descriptions: \"Fix bug\", \"Update dependencies\", \"Phase 1\", \"Misc improvements\" — these tell the reviewer nothing.")
        appendLine("- File paths in Changes (\"Modified UserService.java, UserController.java\").")
        appendLine("- \"This PR\" or \"This change\" phrasing — speak in imperative voice.")
        appendLine("- Restating Summary content inside Changes.")
        appendLine("- Wrapping the entire response in a code block.")
        appendLine("- Inventing implementation details not supported by the diff or commits.")

        // ── Tier-2 grounding constraint ──
        // When the diff is unavailable, the LLM loses its primary source of truth for
        // Implementation Notes and Changes. Force it to stay conservative instead of
        // hallucinating design rationale from thin ticket/commit context.
        if (!isDiffAvailable) {
            appendLine()
            appendLine("NOTE: Diff is unavailable. Base `## Changes` and `## Implementation Notes` on commit messages and Jira context only. Write conservative claims grounded in that evidence — do NOT invent design rationale, architectural decisions, or test scenarios the commits don't support. If a section would require guessing, write \"_(to be filled by author)_\" and omit other detail for that section.")
        }

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
