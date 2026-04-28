package com.workflow.orchestrator.core.services.impl

import com.workflow.orchestrator.core.model.build.BuildProblem
import com.workflow.orchestrator.core.model.build.BuildSource
import com.workflow.orchestrator.core.model.build.ProblemType
import com.workflow.orchestrator.core.model.build.Severity

/**
 * Pure-function parser for Gradle import error messages.
 *
 * Gradle exposes typed errors only via free-form strings on `Exception.message` and
 * the stderr stream — we extract structure with regex pattern-matching against the
 * shapes we've observed in the wild. Anything that does not match a known shape is
 * surfaced as `OTHER` with the raw description so the LLM still has the original
 * text.
 *
 * Patterns are conservative on purpose: false negatives (typing some errors as
 * OTHER) are fine; false positives (mistyping a non-dependency error as DEPENDENCY)
 * are not, because the LLM may then suggest "bump the version" for unrelated issues.
 */
object GradleErrorParser {

    private val DEPENDENCY_NOT_RESOLVED = Regex(
        """Could not resolve[^\n]*?\s([\w.\-]+(?::[\w.\-]+){2,4})\b"""
    )
    private val DEPENDENCY_NOT_FOUND = Regex(
        """Could not find\s+([\w.\-]+(?::[\w.\-]+){2,4})\b"""
    )
    private val PLUGIN_NOT_FOUND = Regex(
        """[Pp]lugin (?:with id )?['"`]([\w.\-]+)['"`] (?:not found|was not found)"""
    )
    private val BUILD_FILE_LINE = Regex(
        """Build file ['"`]([^'"`]+)['"`] line:\s*(\d+)"""
    )
    private val REPOSITORY_AUTH = Regex(
        """\b(?:401|403|Unauthorized|authentication failed|access denied)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Parse a Gradle import failure into one or more typed [BuildProblem]s.
     *
     * The same exception/stderr can carry multiple errors; this function returns the
     * union. If nothing matches a known pattern the whole text is returned as a single
     * [ProblemType.OTHER] entry.
     */
    fun parse(projectPath: String, text: String): List<BuildProblem> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<BuildProblem>()

        for (match in DEPENDENCY_NOT_RESOLVED.findAll(text)) {
            out += dep(projectPath, match.value, match.groupValues[1])
        }
        for (match in DEPENDENCY_NOT_FOUND.findAll(text)) {
            out += dep(projectPath, match.value, match.groupValues[1])
        }
        for (match in PLUGIN_NOT_FOUND.findAll(text)) {
            out += BuildProblem(
                source = BuildSource.GRADLE_IMPORT,
                projectPath = projectPath,
                description = match.value,
                type = ProblemType.STRUCTURE,
                severity = Severity.ERROR,
            )
        }
        for (match in BUILD_FILE_LINE.findAll(text)) {
            out += BuildProblem(
                source = BuildSource.GRADLE_IMPORT,
                projectPath = match.groupValues[1],
                description = match.value,
                type = ProblemType.STRUCTURE,
                severity = Severity.ERROR,
                line = match.groupValues[2].toIntOrNull(),
            )
        }
        if (REPOSITORY_AUTH.containsMatchIn(text) && out.none { it.type == ProblemType.REPOSITORY }) {
            out += BuildProblem(
                source = BuildSource.GRADLE_IMPORT,
                projectPath = projectPath,
                description = text.lineSequence().firstOrNull { REPOSITORY_AUTH.containsMatchIn(it) } ?: "Repository auth failed",
                type = ProblemType.REPOSITORY,
                severity = Severity.ERROR,
            )
        }

        if (out.isEmpty()) {
            out += BuildProblem(
                source = BuildSource.GRADLE_IMPORT,
                projectPath = projectPath,
                description = text.trim().lineSequence().take(5).joinToString("\n"),
                type = ProblemType.OTHER,
                severity = Severity.ERROR,
            )
        }
        return out
    }

    private fun dep(projectPath: String, fullMatch: String, coords: String) = BuildProblem(
        source = BuildSource.GRADLE_IMPORT,
        projectPath = projectPath,
        description = fullMatch,
        type = ProblemType.DEPENDENCY,
        severity = Severity.ERROR,
        artifactCoords = coords,
    )
}
