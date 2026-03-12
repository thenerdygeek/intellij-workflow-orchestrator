package com.workflow.orchestrator.handover.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.handover.model.FindingSeverity
import com.workflow.orchestrator.handover.model.ReviewFinding

@Service(Service.Level.PROJECT)
class PreReviewService {

    private var project: Project? = null

    constructor(project: Project) {
        this.project = project
    }

    constructor()

    /**
     * Parses Cody's text response into structured findings.
     * Cody returns free-text; we look for patterns like:
     * - **HIGH** `file.kt:42` — Missing @Transactional [missing-transactional]
     */
    fun parseFindings(codyResponse: String): List<ReviewFinding> {
        val findings = mutableListOf<ReviewFinding>()
        val pattern = Regex(
            """\*\*(HIGH|MEDIUM|LOW)\*\*\s+`([^:]+):(\d+)`\s*[-\u2013\u2014]\s*(.+?)\s*\[([^\]]+)]"""
        )

        for (match in pattern.findAll(codyResponse)) {
            val severity = when (match.groupValues[1]) {
                "HIGH" -> FindingSeverity.HIGH
                "MEDIUM" -> FindingSeverity.MEDIUM
                else -> FindingSeverity.LOW
            }
            findings.add(ReviewFinding(
                severity = severity,
                filePath = match.groupValues[2],
                lineNumber = match.groupValues[3].toIntOrNull() ?: 0,
                message = match.groupValues[4].trim(),
                pattern = match.groupValues[5]
            ))
        }

        return findings.sortedBy { it.severity.ordinal }
    }

    fun buildReviewPrompt(diff: String): String {
        return """
            |Analyze this Spring Boot code diff for anti-patterns and issues.
            |For each issue found, format as:
            |**SEVERITY** `file:line` — description [pattern-name]
            |
            |Where SEVERITY is HIGH, MEDIUM, or LOW.
            |Pattern names: missing-transactional, unclosed-resource, missing-error-handling, n-plus-one-query, missing-validation
            |
            |Diff:
            |```
            |$diff
            |```
        """.trimMargin()
    }

    enum class DiffValidation { OK, EMPTY, TOO_LARGE }

    fun validateDiff(diff: String): DiffValidation {
        if (diff.isBlank()) return DiffValidation.EMPTY
        if (diff.lines().size > 10_000) return DiffValidation.TOO_LARGE
        return DiffValidation.OK
    }

    companion object {
        fun getInstance(project: Project): PreReviewService {
            return project.getService(PreReviewService::class.java)
        }
    }
}
