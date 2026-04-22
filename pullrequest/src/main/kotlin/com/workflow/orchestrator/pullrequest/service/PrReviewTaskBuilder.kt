package com.workflow.orchestrator.pullrequest.service

class PrReviewTaskBuilder {

    data class JiraTicket(
        val key: String,
        val summary: String,
        val description: String?,
        val acceptanceCriteria: String?,
    )

    fun build(
        projectKey: String,
        repoSlug: String,
        prId: Int,
        prTitle: String,
        prAuthor: String,
        sourceBranch: String,
        targetBranch: String,
        reviewers: List<String>,
        changedFiles: List<String>,
        diff: String,
        jiraTicket: JiraTicket?,
        sessionId: String,
    ): String {
        val diffCapped = if (diff.length > MAX_DIFF_CHARS) {
            diff.take(MAX_DIFF_CHARS) + "\n[... diff truncated at $MAX_DIFF_CHARS chars ...]"
        } else {
            diff
        }

        val sb = StringBuilder()
        sb.appendLine(
            """
You are conducting a PR review. Follow your persona's review pipeline (Phases 1-6) with these adaptations:

- Source of truth for the diff is the <pr_diff> block below — do not re-fetch it.
- Emit each finding as one call to ai_review.add_finding(pr_id=..., session_id=..., severity=NORMAL|BLOCKER, message=..., file?, line_start?, line_end?, anchor_side? (ADDED|REMOVED|CONTEXT), suggestion?).
- Before emitting, call bitbucket_review.list_comments(...) and ai_review.list_findings(...) to skip duplicates (same file+line with overlapping concern).
- Do NOT post to Bitbucket. Pushing is the user's action from the AI Review sub-tab after your session completes.
- When done (or there are no findings), call attempt_completion with a 1-sentence summary.

<session_id>$sessionId</session_id>
<pr_id>$projectKey/$repoSlug/PR-$prId</pr_id>
<pr_metadata>
  title: $prTitle
  author: $prAuthor
  source_branch: $sourceBranch -> target_branch: $targetBranch
  reviewers: ${reviewers.joinToString(", ").ifBlank { "(none)" }}
</pr_metadata>
            """.trimIndent()
        )

        if (jiraTicket != null) {
            sb.appendLine()
            sb.appendLine("<linked_jira_ticket>")
            sb.appendLine("  ${jiraTicket.key}: ${jiraTicket.summary}")
            if (!jiraTicket.description.isNullOrBlank()) {
                sb.appendLine("  Description: ${jiraTicket.description}")
            }
            if (!jiraTicket.acceptanceCriteria.isNullOrBlank()) {
                sb.appendLine("  Acceptance Criteria: ${jiraTicket.acceptanceCriteria}")
            }
            sb.appendLine("</linked_jira_ticket>")
        }

        sb.appendLine()
        sb.appendLine("<changed_files>")
        if (changedFiles.isEmpty()) {
            sb.appendLine("  (none listed)")
        } else {
            changedFiles.forEach { sb.appendLine("  $it") }
        }
        sb.appendLine("</changed_files>")

        sb.appendLine()
        sb.appendLine("<pr_diff>")
        sb.appendLine(diffCapped)
        sb.appendLine("</pr_diff>")
        sb.appendLine()
        sb.appendLine("Begin.")

        return sb.toString()
    }

    companion object {
        const val MAX_DIFF_CHARS = 327_680
    }
}
