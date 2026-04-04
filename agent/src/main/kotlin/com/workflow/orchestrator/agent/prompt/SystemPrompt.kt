package com.workflow.orchestrator.agent.prompt

/**
 * Builds the system prompt for the AI coding agent.
 * Intentionally simple -- replaces a 605-LOC PromptAssembler with ~60 lines.
 */
object SystemPrompt {

    fun build(
        projectName: String,
        projectPath: String,
        repoMap: String? = null,
        planModeEnabled: Boolean = false,
        additionalContext: String? = null
    ): String = buildString {
        // Role
        appendLine("You are an AI coding agent working inside an IDE on project '$projectName'.")
        appendLine()

        // Project path
        appendLine("Project path: $projectPath")
        appendLine()

        // Repository structure
        if (repoMap != null) {
            appendLine("## Repository structure")
            appendLine(repoMap)
            appendLine()
        }

        // Additional context (CLAUDE.md, etc.)
        if (additionalContext != null) {
            appendLine("## Additional context")
            appendLine(additionalContext)
            appendLine()
        }

        // Rules
        appendLine("## Rules")
        appendLine("- Always read a file before editing it")
        appendLine("- After editing, verify the change compiles/works")
        appendLine("- Keep working until the task is FULLY resolved")
        appendLine("- If you encounter an error, investigate and fix it")
        appendLine("- Explain what you're doing briefly as you work")
        appendLine("- When finished, call attempt_completion with a summary")
        appendLine("- NEVER respond without calling a tool")

        // Plan mode constraint
        if (planModeEnabled) {
            appendLine()
            appendLine("## Plan mode (read-only)")
            appendLine("You are in read-only plan mode. You can read files, search, and analyze,")
            appendLine("but you CANNOT edit files, create files, or run commands that modify state.")
            appendLine("Produce a plan only -- do not attempt to implement it.")
        }
    }
}
