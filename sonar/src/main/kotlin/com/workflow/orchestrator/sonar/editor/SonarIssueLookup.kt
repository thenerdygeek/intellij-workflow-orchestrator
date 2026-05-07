package com.workflow.orchestrator.sonar.editor

import com.workflow.orchestrator.sonar.model.MappedIssue

/**
 * Finds the Sonar issue at a specific (filePath, line) coordinate. Pure
 * function — no IntelliJ infrastructure — so it can be unit-tested.
 */
object SonarIssueLookup {

    /**
     * Returns the issue at [filePath] whose line range contains [line].
     * When multiple issues span the same line, the innermost (smallest
     * range) wins so the user gets the most specific quick-fix.
     */
    fun findIssueSpanningLine(
        issues: List<MappedIssue>,
        filePath: String,
        line: Int
    ): MappedIssue? {
        return issues
            .asSequence()
            .filter { it.filePath == filePath && line in it.startLine..it.endLine }
            .minByOrNull { it.endLine - it.startLine }
    }
}
