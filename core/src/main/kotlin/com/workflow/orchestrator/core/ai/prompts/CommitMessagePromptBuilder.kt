package com.workflow.orchestrator.core.ai.prompts

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.workflow.TicketDetails

/**
 * Enterprise-grade commit message generation with configurable format.
 *
 * Design decisions:
 * - System message frames the role (consistent formatting, no hallucination)
 * - User message provides diff + context (code intelligence, recent history)
 * - "Why not what" emphasis in body bullets (enterprise traceability)
 * - Issue-type-driven commit type (Bug → fix, Story/Task/New Feature → feat) [CONVENTIONAL only]
 * - Multi-candidate ticket selection — when the active ticket and the branch ticket
 *   differ, both are sent and the LLM picks the one that best matches the diff
 * - Recent commits as a soft style reference (NOT format authority)
 * - No diff truncation — Sourcegraph supports 150K input tokens
 * - PLAIN format: escape hatch for teams that don't use Conventional Commits
 */
object CommitMessagePromptBuilder {

    /** Source label for a candidate ticket — tells the LLM where each candidate came from. */
    enum class TicketSource { ACTIVE, BRANCH, BOTH }

    /** A candidate ticket plus where it was discovered. */
    data class TicketCandidate(val source: TicketSource, val details: TicketDetails)

    /** System message — role framing for consistent, parseable output (Conventional Commits). */
    private const val SYSTEM_MESSAGE =
        """You are an expert at writing git commit messages following the Conventional Commits specification. """ +
            """You analyze diffs and produce clear, accurate commit messages that help teams understand what changed and why.

Output ONLY the raw commit message. No commentary, no markdown code blocks, no explanation."""

    /** System message for PLAIN format — no Conventional Commits framing. */
    private const val PLAIN_SYSTEM_MESSAGE =
        """You are an expert at writing clear, concise git commit messages. """ +
            """You analyze diffs and produce accurate messages that help teams understand what changed and why.

Output ONLY the raw commit message. No commentary, no markdown code blocks, no explanation."""

    fun buildMessages(
        diff: String,
        ticketId: String = "",
        filesSummary: String = "",
        recentCommits: List<String> = emptyList(),
        codeContext: String = "",
        candidateTickets: List<TicketCandidate> = emptyList(),
        format: CommitMessageFormat = CommitMessageFormat.CONVENTIONAL
    ): List<ChatMessage> {
        val systemMessage = if (format == CommitMessageFormat.PLAIN) PLAIN_SYSTEM_MESSAGE else SYSTEM_MESSAGE
        val userMessage = when (format) {
            CommitMessageFormat.CONVENTIONAL ->
                buildConventionalUserMessage(diff, ticketId, filesSummary, recentCommits, codeContext, candidateTickets)
            CommitMessageFormat.PLAIN ->
                buildPlainUserMessage(diff, ticketId, filesSummary, recentCommits, codeContext, candidateTickets)
        }
        return listOf(
            ChatMessage(role = "system", content = systemMessage),
            ChatMessage(role = "user", content = userMessage)
        )
    }

    private fun buildConventionalUserMessage(
        diff: String,
        ticketId: String,
        filesSummary: String,
        recentCommits: List<String>,
        codeContext: String,
        candidateTickets: List<TicketCandidate>
    ): String = buildString {
        appendLine("Generate a commit message for these changes.")
        appendLine()

        val hasMultipleCandidates = candidateTickets.size > 1
        val hasAnyCandidate = candidateTickets.isNotEmpty()

        // ── Format ──
        appendLine("FORMAT:")
        when {
            hasMultipleCandidates -> {
                appendLine("<CHOSEN-TICKET-ID> type(scope): imperative summary (max 72 chars total)")
                appendLine("(Pick exactly ONE ticket from CANDIDATE TICKETS — see SELECT TICKET below.)")
            }
            hasAnyCandidate -> {
                appendLine("${candidateTickets.first().details.key} type(scope): imperative summary (max 72 chars total)")
            }
            ticketId.isNotBlank() -> {
                appendLine("$ticketId type(scope): imperative summary (max 72 chars total)")
            }
            else -> {
                appendLine("type(scope): imperative summary (max 72 chars)")
            }
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

        // ── Issue-type → commit-type mapping ──
        // This is the deterministic signal that prevents the LLM from defaulting to `feat`
        // for everything. Issue type comes from Jira and overrides any guesswork.
        appendLine("ISSUE TYPE → COMMIT TYPE (use the chosen ticket's Type field):")
        appendLine("- Bug, Defect, Incident → fix")
        appendLine("- Story, New Feature, Improvement, Epic → feat")
        appendLine(
            "- Task, Sub-task → infer from the diff (fix if it's repairing broken behavior, " +
                "feat if it's new behavior, refactor/perf/test/docs/chore otherwise)"
        )
        appendLine("- Spike, Investigation → chore or docs")
        appendLine("- If no ticket type is available, infer purely from the diff.")
        appendLine()

        // ── Rules ──
        appendLine("RULES:")
        appendLine(
            "- scope = domain area (auth, billing, pr-list); prefer the chosen ticket's component or label if it matches; " +
                "NEVER use file paths"
        )
        appendLine("- Imperative mood: 'add' not 'added', 'fix' not 'fixed'")
        appendLine("- Body bullets explain WHY the change was made, not just what lines changed")
        appendLine("- AVOID: passive voice, 'This commit/change' phrasing, repeating type in summary")
        if (!hasAnyCandidate) {
            appendLine(
                "- No ticket is active; do NOT prepend any ticket ID to the summary, " +
                    "even if RECENT COMMITS show one."
            )
        } else {
            appendLine(
                "- Use ONLY the ticket ID you select from CANDIDATE TICKETS. " +
                    "Do NOT copy ticket IDs from RECENT COMMITS — those are style/tone references only."
            )
        }
        appendLine()

        // ── Candidate tickets ──
        if (hasMultipleCandidates) {
            appendLine("CANDIDATE TICKETS (pick the one whose summary/description/type best matches the DIFF):")
            candidateTickets.forEach { candidate ->
                appendCandidate(candidate)
            }
            appendLine("SELECT TICKET:")
            appendLine("- Choose the candidate whose summary/description/components best describes the DIFF.")
            appendLine("- If both seem to fit equally, prefer the ACTIVE ticket.")
            appendLine(
                "- If neither fits the DIFF (the diff is unrelated to either ticket), " +
                    "still use ACTIVE — but reflect the actual change in the body, not the ticket title."
            )
            appendLine("- Use the chosen ticket's Type field to set the commit type per the mapping above.")
            appendLine()
        } else if (hasAnyCandidate) {
            appendLine("TICKET CONTEXT:")
            appendCandidate(candidateTickets.first())
        }

        appendContextAndDiff(
            recentCommits,
            codeContext,
            filesSummary,
            diff,
            "the FORMAT and TYPES sections above are authoritative"
        )
    }

    private fun buildPlainUserMessage(
        diff: String,
        ticketId: String,
        filesSummary: String,
        recentCommits: List<String>,
        codeContext: String,
        candidateTickets: List<TicketCandidate>
    ): String = buildString {
        appendLine("Generate a commit message for these changes.")
        appendLine()
        appendLine("FORMAT:")
        appendLine("<concise imperative summary, max 72 chars>")
        appendLine()
        appendLine("- Optional body: one bullet per logical change, explaining WHAT changed and WHY")
        appendLine("- Group related edits into one bullet; trivial changes need just a summary line")
        appendLine()
        appendLine("RULES:")
        appendLine("- Imperative mood: 'add' not 'added', 'fix' not 'fixed'")
        appendLine("- Do NOT use a Conventional-Commits type prefix such as 'feat:' or 'fix:'")
        appendLine("- Body explains WHY the change was made, not just what lines changed")
        appendLine("- AVOID: passive voice, 'This commit/change' phrasing")
        if (ticketId.isNotBlank() || candidateTickets.isNotEmpty()) {
            appendLine(
                "- You MAY reference the ticket if it clarifies the change, but do not force any prefix format."
            )
        }
        appendLine()
        appendContextAndDiff(recentCommits, codeContext, filesSummary, diff, "the FORMAT/RULES above are authoritative")
    }

    /**
     * Appends the shared context/diff tail (RECENT COMMITS / CODE CONTEXT / CHANGED FILES / DIFF).
     *
     * The [authoritativeRef] param keeps CONVENTIONAL's exact wording byte-identical
     * ("the FORMAT and TYPES sections above are authoritative") while PLAIN uses its own
     * ("the FORMAT/RULES above are authoritative").
     */
    private fun StringBuilder.appendContextAndDiff(
        recentCommits: List<String>,
        codeContext: String,
        filesSummary: String,
        diff: String,
        authoritativeRef: String
    ) {
        if (recentCommits.isNotEmpty()) {
            appendLine("RECENT COMMITS (for tone and vocabulary only — $authoritativeRef):")
            recentCommits.forEach { appendLine("  $it") }
            appendLine()
        }
        if (codeContext.isNotBlank()) {
            appendLine("CODE CONTEXT:")
            appendLine(codeContext)
            appendLine()
        }
        if (filesSummary.isNotBlank()) {
            appendLine("CHANGED FILES: $filesSummary")
            appendLine()
        }
        appendLine("DIFF:")
        append(diff)
    }

    private fun StringBuilder.appendCandidate(candidate: TicketCandidate) {
        val d = candidate.details
        val sourceLabel = when (candidate.source) {
            TicketSource.ACTIVE -> "ACTIVE"
            TicketSource.BRANCH -> "BRANCH NAME"
            TicketSource.BOTH -> "ACTIVE + BRANCH NAME"
        }
        appendLine("- ${d.key}  [from: $sourceLabel]")
        appendLine("    Summary: ${d.summary}")
        if (!d.type.isNullOrBlank()) {
            appendLine("    Type: ${d.type}")
        }
        if (d.components.isNotEmpty()) {
            appendLine("    Components: ${d.components.joinToString(", ")}")
        }
        if (d.labels.isNotEmpty()) {
            appendLine("    Labels: ${d.labels.joinToString(", ")}")
        }
        val desc = d.description?.trim()
        if (!desc.isNullOrBlank()) {
            val truncated = if (desc.length > 800) desc.take(800) + "…" else desc
            appendLine("    Description:")
            truncated.lineSequence().forEach { appendLine("      $it") }
        }
        appendLine()
    }
}
