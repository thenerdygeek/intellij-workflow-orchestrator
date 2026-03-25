package com.workflow.orchestrator.core.ai.prompts

object PrDescriptionPromptBuilder {
    fun build(
        diff: String,
        commitMessages: List<String> = emptyList(),
        ticketId: String = "",
        ticketSummary: String = "",
        ticketDescription: String = "",
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
        if (ticketId.isNotBlank()) {
            appendLine()
            appendLine("## Jira")
            appendLine("$ticketId: $ticketSummary")
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

        // ── Jira context ──
        if (ticketId.isNotBlank()) {
            appendLine()
            appendLine("JIRA TICKET: $ticketId — $ticketSummary")
            if (ticketDescription.isNotBlank()) {
                appendLine("Ticket description: ${ticketDescription.take(500)}")
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
        appendLine()
        appendLine("DIFF:")
        appendLine("```diff")
        appendLine(diff)
        appendLine("```")
    }
}
