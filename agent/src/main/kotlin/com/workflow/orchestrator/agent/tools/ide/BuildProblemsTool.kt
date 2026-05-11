package com.workflow.orchestrator.agent.tools.ide

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
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

    override fun documentation(): ToolDocumentation = toolDoc("get_build_problems") {
        summary {
            technical(
                "Reads structured build/import problems from the local IDE's BuildProblemsService — " +
                "aggregates Maven snapshot import errors, live-captured Gradle import failures, and " +
                "compile-event errors stored per BuildSource (MAVEN_IMPORT, GRADLE_IMPORT, COMPILE); " +
                "optional `source` param scopes to one build system; optional `severity` param filters " +
                "to ERROR or WARNING; each problem carries type (DEPENDENCY, REPOSITORY, PARENT, " +
                "STRUCTURE, SYNTAX, SETTINGS, COMPILE, OTHER), file path, optional line number, and — " +
                "for dependency errors — extracted artifact coordinates (groupId:artifactId:version); " +
                "returns isError=false when problems are found (the problem list IS the payload)."
            )
            plain(
                "Like opening the 'Build' tool window in IntelliJ and reading the red error entries " +
                "after a Maven reload or Gradle import fails — this tool surfaces the same structured " +
                "list of what went wrong (missing dependency, wrong parent POM, syntax error in " +
                "build.gradle) without having to re-run the build. Covers the local IDE build system " +
                "only — for remote CI failures, use bamboo_builds."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without get_build_problems the LLM would shell out to `./gradlew build` or `mvn dependency:resolve` " +
            "via run_command to learn why a project won't import — 30-120s per invocation, hundreds of lines of " +
            "build-system noise, and no structured artifact coordinate extraction for dependency errors. " +
            "get_build_problems returns the same signal in milliseconds with 1-based line numbers, typed problem " +
            "categories, and parsed groupId:artifactId:version coordinates the LLM can act on immediately. " +
            "For Gradle and compile errors it relies on live-captured events, so it is only as fresh as the " +
            "most recent build run — but for Maven import failures (which are snapshot-stored) it is always current."
        )
        llmMistake(
            "Reads `isError=false` as 'no build problems found'. WRONG — `isError=false` means the tool ran " +
            "successfully; the problem list IS the payload. Both 'No build/import problems.' and " +
            "'N build/import problem(s): ...' return `isError=false`. Only invalid `source`/`severity` enum " +
            "values and an underlying BuildProblemsService failure (coreResult.isError=true) return `isError=true`. " +
            "The LLM must inspect the content to decide whether a fix is needed. This is the same F6 contract " +
            "shared by the entire diagnostics family: diagnostics, run_inspections, list_quickfixes, problem_view."
        )
        llmMistake(
            "Calls get_build_problems to check for remote CI failures. It covers LOCAL IDE build/import errors " +
            "only — Maven reload failures, Gradle import errors, and compile errors captured at event time. " +
            "Remote CI pipeline failures (Bamboo builds, GitHub Actions, etc.) require bamboo_builds or similar."
        )
        llmMistake(
            "Confuses get_build_problems with `diagnostics` or `run_inspections`. Build problems are build-system " +
            "errors from the IDE's build event model (Maven/Gradle import failures, compile errors surfaced by the " +
            "build tool window). diagnostics runs PSI analysis for semantic errors in a source file. " +
            "run_inspections runs IntelliJ inspection profile checks. A Maven snapshot resolution error is a " +
            "build problem; an unresolved Kotlin reference is a diagnostics issue."
        )
        llmMistake(
            "Expects Gradle import or compile errors to be present immediately after IDE startup without having " +
            "run a build. Gradle import errors and compile errors are captured at event time — if the IDE was " +
            "started after the failed build, there may be no record. The description warns about this explicitly: " +
            "re-run the build (e.g. via `run_command ./gradlew build` or the IDE's Reload button) to capture " +
            "fresh events before calling this tool."
        )
        llmMistake(
            "Passes `source=compile` expecting syntax-level PSI errors from a source file. Compile errors " +
            "here are build-tool compile errors (javac/kotlinc output captured from the IDE Build event bus), " +
            "not the real-time PSI analysis that `diagnostics` provides. For per-file semantic analysis, " +
            "use `diagnostics`."
        )
        params {
            optional("source", "string") {
                llmSeesIt("Filter by source: 'maven', 'gradle', 'compile', or 'all' (default 'all')")
                humanReadable(
                    "Which build system's errors to include. 'maven' returns Maven import snapshot errors; " +
                    "'gradle' returns live-captured Gradle import failures; 'compile' returns compile-event " +
                    "errors from either build system; 'all' (default) returns everything across all three sources."
                )
                whenPresent("Only BuildProblem entries whose source field matches the requested value are included.")
                whenAbsent("Defaults to 'all' — Maven, Gradle, and compile errors are all returned.")
                enumValue("maven", "gradle", "compile", "all")
                constraint("must be one of: 'maven', 'gradle', 'compile', 'all' — any other value returns isError=true before any service call")
                example("maven")
                example("gradle")
                example("all")
            }
            optional("severity", "string") {
                llmSeesIt("Filter by severity: 'error', 'warning', or 'all' (default 'all')")
                humanReadable(
                    "Which severity levels to include. 'error' returns only ERROR-level entries; " +
                    "'warning' returns only WARNING-level; 'all' (default) returns both. " +
                    "Build problems are typically errors, but Gradle import warnings do occur (e.g. deprecated API usage)."
                )
                whenPresent("Only BuildProblem entries matching the requested severity are included.")
                whenAbsent("Defaults to 'all' — both ERROR and WARNING entries are returned.")
                enumValue("error", "warning", "all")
                constraint("must be one of: 'error', 'warning', 'all' — any other value returns isError=true before any service call")
                example("error")
                example("all")
            }
        }
        verdict {
            keep(
                "Unique access to the IDE's structured Build tool window problem model — provides typed problem " +
                "categories, artifact coordinate extraction for dependency errors, and per-problem file+line " +
                "references that shell build output lacks. The source/severity filters make it composable: " +
                "call with source=maven to diagnose POM issues, source=compile to check compile-event failures, " +
                "source=all for a full triage. Distinct from diagnostics (PSI, per-file, semantic) and " +
                "run_inspections (inspection profile, quality). No other tool surfaces Maven dependency resolution " +
                "errors with parsed groupId:artifactId:version coordinates ready for pom.xml edit_file fixes.",
                VerdictSeverity.NORMAL,
            )
        }
        related(
            "diagnostics",
            Relationship.ALTERNATIVE,
            "Use diagnostics instead for semantic errors in a specific source file (unresolved references, " +
            "type mismatches, PsiErrorElements) — it walks PSI directly and works for any file regardless of " +
            "whether a build was recently run. get_build_problems covers build-system-level failures; " +
            "diagnostics covers per-file code-level failures."
        )
        related(
            "run_inspections",
            Relationship.SEE_ALSO,
            "run_inspections sweeps the IntelliJ inspection profile on a source file (unused code, null safety, " +
            "Spring misconfig) — orthogonal to build problems. Run get_build_problems first to confirm the " +
            "project imports cleanly before running inspections, as a broken import can produce false positives."
        )
        related(
            "problem_view",
            Relationship.SEE_ALSO,
            "problem_view reads already-computed IDE highlighting from open editor tabs — overlapping payload " +
            "for compile errors, but does not cover Maven/Gradle import failures that live in the Build tool " +
            "window rather than the Problems panel."
        )
        related(
            "bamboo_builds",
            Relationship.ALTERNATIVE,
            "Use bamboo_builds for REMOTE CI build failures (Bamboo pipelines, job logs, test results). " +
            "get_build_problems covers LOCAL IDE build/import errors only."
        )
        downside(
            "Gradle import and compile errors are captured at build-event time — if the IDE was restarted " +
            "after a failed build without re-running it, there may be no record. Call with source=gradle or " +
            "source=compile only after confirming a build was recently run in the current IDE session."
        )
        downside(
            "Maven import errors are snapshot-stored (always available) but reflect the last Maven reload, " +
            "not a re-import triggered by the agent. If the user has changed the POM after the last reload, " +
            "the stored errors may be stale. The agent should trigger a Maven reload via run_command (e.g. " +
            "`mvn dependency:resolve`) or the IDE's Reload button, then call this tool again."
        )
        downside(
            "Does not provide real-time PSI-level errors — for compile errors surfaced mid-edit before " +
            "a build is run, use `diagnostics`. get_build_problems captures the build tool's event stream, " +
            "which only fires when an explicit build or import is triggered."
        )
        downside(
            "Artifact coordinate extraction (groupId:artifactId:version on the `artifactCoords` field) is " +
            "present only for DEPENDENCY-type problems. REPOSITORY, PARENT, SYNTAX, SETTINGS, STRUCTURE, " +
            "COMPILE, and OTHER types carry only the description and file path — the LLM must parse the " +
            "description string to recover coordinates for non-DEPENDENCY types."
        )
        downside(
            "The `line` field on a BuildProblem is optional — compile errors typically include a line number " +
            "but Maven import errors often do not. A null line means the problem is file-level or build-level, " +
            "not anchored to a specific source line."
        )
        observation(
            "isError=false on problems-found is the shared contract across the diagnostics tool family: " +
            "diagnostics, run_inspections, list_quickfixes, problem_view, and get_build_problems all return " +
            "isError=false when the tool ran successfully — even when the payload contains errors. " +
            "isError=true is reserved strictly for tool-execution failures (service error, invalid enum value). " +
            "See agent/CLAUDE.md ('ToolResult.isError semantics') and the F6 invariant documented in each tool's execute() KDoc."
        )
        observation(
            "V1 covers Maven snapshot import errors fully; Gradle import + compile event capture are " +
            "designated V1.1 in the tool KDoc. The source filter accepts all three values regardless of " +
            "V1 vs V1.1 status — results for gradle and compile sources may be empty on V1 deployments."
        )
        mergeOpportunity(
            "get_build_problems, diagnostics, run_inspections, list_quickfixes, and problem_view are all " +
            "diagnostics-family tools that share the isError=false contract and structured DiagnosticEntry " +
            "output. A future 'diagnostics' meta-tool with an `action` enum (psi, inspections, quickfixes, " +
            "problems, build) would halve schema token cost and unify the selection logic. Deferred until " +
            "the family stabilises."
        )
    }

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

        val filtered = coreResult.data!!
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
