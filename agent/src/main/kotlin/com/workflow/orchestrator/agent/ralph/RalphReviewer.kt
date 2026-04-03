package com.workflow.orchestrator.agent.ralph

enum class ReviewVerdict { ACCEPT, IMPROVE }

data class ReviewResult(
    val verdict: ReviewVerdict,
    val feedback: String?,
)

/**
 * Builds reviewer prompts and parses reviewer responses for the Ralph Loop.
 *
 * The reviewer is executed as a WorkerSession (see AgentController),
 * but prompt building and response parsing are pure functions testable without IntelliJ.
 */
object RalphReviewer {
    const val MAX_FEEDBACK_LENGTH = 2000

    const val SYSTEM_PROMPT = """You are a code reviewer evaluating work done by an AI coding agent.
Your job is to determine if the agent's work meets the requirements of the original task.
Be pragmatic — request correctness, not perfection. Focus on bugs, missing requirements, and broken functionality.
Do NOT request stylistic changes, comment additions, or minor refactoring unless they affect correctness."""

    fun buildReviewerPrompt(
        originalTask: String,
        iteration: Int,
        maxIterations: Int,
        completionSummary: String,
        changedFiles: List<String>,
        planStatus: String?,
        priorFeedback: String?,
    ): String = buildString {
        appendLine("Evaluate the following work against the original task.")
        appendLine()
        appendLine("<original_task>")
        appendLine(originalTask)
        appendLine("</original_task>")
        appendLine()
        appendLine("<iteration>$iteration of $maxIterations</iteration>")
        appendLine()
        appendLine("<completion_summary>")
        appendLine(completionSummary)
        appendLine("</completion_summary>")
        if (changedFiles.isNotEmpty()) {
            appendLine()
            appendLine("<files_changed>")
            changedFiles.forEach { appendLine("- $it") }
            appendLine("</files_changed>")
        }
        if (!planStatus.isNullOrBlank()) {
            appendLine()
            appendLine("<plan_status>")
            appendLine(planStatus)
            appendLine("</plan_status>")
        }
        if (!priorFeedback.isNullOrBlank()) {
            appendLine()
            appendLine("<prior_reviewer_feedback>")
            appendLine(priorFeedback)
            appendLine("</prior_reviewer_feedback>")
        }
        appendLine()
        appendLine("Instructions:")
        appendLine("1. Read the changed files to evaluate the actual code quality")
        appendLine("2. Run diagnostics to check for errors")
        appendLine("3. Assess whether the work fully satisfies the original task")
        appendLine("4. Check for bugs, missing edge cases, or incomplete implementations")
        if (!priorFeedback.isNullOrBlank()) {
            appendLine("5. Verify that the previous reviewer feedback was addressed")
        }
        appendLine()
        appendLine("Respond with EXACTLY one of:")
        appendLine("  ACCEPT — work meets requirements, no further iteration needed.")
        appendLine("  IMPROVE: <specific, actionable feedback about what to change>")
    }

    fun parseResponse(content: String): ReviewResult {
        val trimmed = content.trim()
        // Check first word
        if (trimmed.startsWith("ACCEPT")) {
            return ReviewResult(ReviewVerdict.ACCEPT, null)
        }
        if (trimmed.startsWith("IMPROVE")) {
            val feedback = trimmed.removePrefix("IMPROVE:").removePrefix("IMPROVE").trim()
                .take(MAX_FEEDBACK_LENGTH)
                .ifEmpty { trimmed.take(MAX_FEEDBACK_LENGTH) }
            return ReviewResult(ReviewVerdict.IMPROVE, feedback)
        }
        // Check for embedded keywords — IMPROVE takes priority
        val hasImprove = trimmed.contains("IMPROVE")
        val hasAccept = trimmed.contains("ACCEPT")
        if (hasImprove) {
            val idx = trimmed.indexOf("IMPROVE")
            val after = trimmed.substring(idx).removePrefix("IMPROVE:").removePrefix("IMPROVE").trim()
            return ReviewResult(
                ReviewVerdict.IMPROVE,
                after.take(MAX_FEEDBACK_LENGTH).ifEmpty { trimmed.take(MAX_FEEDBACK_LENGTH) }
            )
        }
        if (hasAccept && !hasImprove) {
            return ReviewResult(ReviewVerdict.ACCEPT, null)
        }
        // Ambiguous — default to IMPROVE
        return ReviewResult(ReviewVerdict.IMPROVE, trimmed.take(MAX_FEEDBACK_LENGTH))
    }

    /** Tool names the reviewer is allowed to use (read-only evaluation tools). */
    val REVIEWER_TOOLS = setOf(
        "read_file", "search_code", "diagnostics", "problem_view",
        "find_definition", "find_references", "run_inspections",
        "file_structure", "glob_files", "get_annotations", "get_method_body"
    )
}
