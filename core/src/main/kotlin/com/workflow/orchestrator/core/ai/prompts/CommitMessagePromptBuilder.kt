package com.workflow.orchestrator.core.ai.prompts

object CommitMessagePromptBuilder {
    fun build(
        diff: String,
        ticketId: String = "",
        filesSummary: String = "",
        recentCommits: List<String> = emptyList(),
        codeContext: String = ""
    ): String = buildString {
        appendLine("Generate a git commit message for the following changes. Output ONLY the raw commit message — no commentary, no markdown code blocks.")
        appendLine()

        // ── Format specification ──
        appendLine("FORMAT (follow exactly):")
        appendLine("type(scope): imperative summary (max 72 chars, no trailing period)")
        appendLine("")
        appendLine("- Bullet point per logical change, imperative verb, explains what+why")
        appendLine("- Group related edits into one bullet")
        appendLine()

        // ── Rules ──
        appendLine("RULES:")
        appendLine("- type: feat|fix|refactor|perf|test|docs|style|build|ci|chore")
        appendLine("- scope: domain area (e.g., auth, billing, pr-list), NOT file paths")
        appendLine("- Summary: imperative mood ('add' not 'added'), captures the essence")
        appendLine("- Body: ALWAYS bullet points with '- ' prefix, even for single changes")
        appendLine("- Bullets describe behavioral/semantic changes, not line-level edits")
        appendLine("- If trivial (typo, import, version bump), body can be one short bullet")
        if (ticketId.isNotBlank()) {
            appendLine("- Prefix summary with ticket: $ticketId type(scope): description")
        }
        appendLine()
        appendLine("AVOID: repeating the type in summary, file paths in bullets, passive voice,")
        appendLine("'This commit/change' phrasing, wrapping in code blocks")

        // ── Recent commits for context + style ──
        if (recentCommits.isNotEmpty()) {
            appendLine()
            appendLine("RECENT COMMITS (understand how this change relates to recent work; match this project's style):")
            recentCommits.forEach { appendLine("  $it") }
        }

        // ── Code intelligence context ──
        if (codeContext.isNotBlank()) {
            appendLine()
            appendLine("CODE CONTEXT (classes, annotations, and structure of changed files):")
            appendLine(codeContext)
        }

        // ── Changed files ──
        if (filesSummary.isNotBlank()) {
            appendLine()
            appendLine("CHANGED FILES: $filesSummary")
        }

        // ── The diff ──
        appendLine()
        appendLine("DIFF:")
        appendLine("```diff")
        appendLine(diff)
        appendLine("```")
    }
}
