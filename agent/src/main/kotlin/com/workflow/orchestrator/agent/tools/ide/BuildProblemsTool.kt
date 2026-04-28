package com.workflow.orchestrator.agent.tools.ide

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.core.model.build.BuildProblem
import com.workflow.orchestrator.core.model.build.BuildSource
import com.workflow.orchestrator.core.model.build.Severity
import com.workflow.orchestrator.core.services.BuildProblemsService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads structured build/import problems for the local IDE project (Maven reload errors,
 * Gradle import failures, compile errors). Distinct from remote CI build status (Bamboo).
 *
 * V1: Maven only — Gradle import + compile event capture lands in V1.1.
 */
class BuildProblemsTool : AgentTool {

    override val name = "get_build_problems"

    override val description = """
LOCAL IDE ONLY: Read errors from the most recent local IDE build/import. Covers Maven import (snapshot), Gradle import (live-captured), and compile (live-captured) errors.

Use for: 'why did my Maven reload fail', 'why did my Gradle import fail', 'why won't my project compile', 'what's wrong with my pom.xml or build.gradle', 'check the IDE Build tool window for errors', 'show local build errors'.
Do NOT use for: remote CI builds (use bamboo_builds for those), code-level inspection problems (use problem_view), or runtime errors (use diagnostics).

Returns structured problems with file path, problem type (DEPENDENCY, REPOSITORY, PARENT, STRUCTURE, SYNTAX, SETTINGS, COMPILE, OTHER), description, and — for dependency errors — extracted artifact coordinates (groupId:artifactId:version). Gradle and compile errors are captured at event time; if the IDE was started after the failed build there may be no record — re-run the build to capture fresh events.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "source" to ParameterProperty(
                type = "string",
                description = "Filter by source: 'maven', 'gradle', 'compile', or 'all' (default 'all')",
                enumValues = listOf("maven", "gradle", "compile", "all"),
            ),
            "severity" to ParameterProperty(
                type = "string",
                description = "Filter by severity: 'error', 'warning', or 'all' (default 'all')",
                enumValues = listOf("error", "warning", "all"),
            ),
        ),
        required = emptyList(),
    )

    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val source = params["source"]?.jsonPrimitive?.content?.lowercase() ?: "all"
        val severity = params["severity"]?.jsonPrimitive?.content?.lowercase() ?: "all"

        if (source !in VALID_SOURCES) {
            return errorResult("Invalid 'source' value: '$source'. Must be one of $VALID_SOURCES.")
        }
        if (severity !in VALID_SEVERITIES) {
            return errorResult("Invalid 'severity' value: '$severity'. Must be one of $VALID_SEVERITIES.")
        }

        val coreResult = BuildProblemsService.getInstance(project).getRecentBuildProblems()
        if (coreResult.isError) {
            return ToolResult(
                content = coreResult.summary + (coreResult.hint?.let { "\nHint: $it" } ?: ""),
                summary = coreResult.summary.lines().firstOrNull() ?: "",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )
        }

        val filtered = coreResult.data
            .filter { source == "all" || matchesSource(it.source, source) }
            .filter { severity == "all" || matchesSeverity(it.severity, severity) }

        val content = formatProblems(filtered, source, severity)
        return ToolResult(
            content = content,
            summary = formatSummary(filtered, source, severity),
            tokenEstimate = TokenEstimator.estimate(content),
            isError = false,
        )
    }

    private fun errorResult(message: String) = ToolResult(
        content = message,
        summary = message,
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true,
    )

    private fun matchesSource(actual: BuildSource, filter: String): Boolean = when (filter) {
        "maven" -> actual == BuildSource.MAVEN_IMPORT
        "gradle" -> actual == BuildSource.GRADLE_IMPORT
        "compile" -> actual == BuildSource.COMPILE
        else -> true
    }

    private fun matchesSeverity(actual: Severity, filter: String): Boolean = when (filter) {
        "error" -> actual == Severity.ERROR
        "warning" -> actual == Severity.WARNING
        else -> true
    }

    private fun formatSummary(problems: List<BuildProblem>, source: String, severity: String): String {
        if (problems.isEmpty()) {
            val scope = if (source == "all" && severity == "all") "" else " (filter: source=$source, severity=$severity)"
            return "No build/import problems$scope."
        }
        val byType = problems.groupingBy { it.type }.eachCount()
        val typeBreakdown = byType.entries.joinToString(", ") { "${it.value} ${it.key.name.lowercase()}" }
        return "${problems.size} build/import problem(s): $typeBreakdown"
    }

    private fun formatProblems(problems: List<BuildProblem>, source: String, severity: String): String {
        if (problems.isEmpty()) {
            return formatSummary(problems, source, severity)
        }
        return buildString {
            appendLine(formatSummary(problems, source, severity))
            appendLine()
            problems.forEachIndexed { index, p ->
                appendLine("[${index + 1}] ${p.severity} ${p.type} (${p.source})")
                if (p.projectPath.isNotBlank()) appendLine("    at: ${p.projectPath}${p.line?.let { ":$it" } ?: ""}")
                if (p.artifactCoords != null) appendLine("    artifact: ${p.artifactCoords}")
                appendLine("    ${p.description}")
                if (index < problems.lastIndex) appendLine()
            }
        }.trimEnd()
    }

    companion object {
        private val VALID_SOURCES = setOf("maven", "gradle", "compile", "all")
        private val VALID_SEVERITIES = setOf("error", "warning", "all")
    }
}
