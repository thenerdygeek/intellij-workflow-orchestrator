package com.workflow.orchestrator.agent.tools.ide

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import kotlin.coroutines.cancellation.CancellationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolOutputConfig
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class RunInspectionsTool : AgentTool {
    override val name = "run_inspections"
    override val description = "Run IntelliJ code inspections on a file: unused code, null safety, performance, Spring misconfig. Returns problems with severity. Use to find issues beyond syntax/compilation errors."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "File path to inspect"),
            "severity" to ParameterProperty(
                type = "string",
                description = "Minimum severity filter: 'ERROR', 'WARNING', or 'INFO'. Optional, defaults to WARNING.",
                enumValues = listOf("ERROR", "WARNING", "INFO")
            )
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)
    override val outputConfig = ToolOutputConfig.COMMAND

    override fun documentation(): ToolDocumentation = toolDoc("run_inspections") {
        summary {
            technical("File-scoped IntelliJ inspection sweep via the active project inspection profile — iterates LocalInspectionToolWrapper entries gated by profile.isToolEnabled(), runs each tool's buildVisitor+PsiRecursiveElementWalkingVisitor walk under ReadAction.nonBlocking().inSmartMode(), collects ProblemDescriptors, applies an optional minimum-severity filter, builds structured DiagnosticEntry list (head-20 prose preview inline, full JSON spills to disk above 30K via ToolOutputSpiller), and returns isError=false even when problems are found.")
            plain("Like running the IntelliJ 'Inspect Code' command on a single file and getting back a list of every warning and error the IDE can see — unused variables, null-safety issues, Spring misconfigurations, performance hints, deprecations — all scoped to the file you're working on, without launching a full build.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without run_inspections, the LLM falls back to static analysis shell tools (e.g. `./gradlew check`, `mvn verify`, standalone ktlint / checkstyle / spotbugs) via run_command. These take 30-120s per invocation, produce build-system noise the LLM must filter, do not respect the user's active IntelliJ inspection profile settings, and yield no line-precise structured output. run_inspections covers the same problem classes (unused code, null safety, performance, Spring misconfig) in <5s with 1-based line numbers ready for edit_file, using the exact same profile the user sees in their IDE."
        )
        llmMistake("Reads `isError=false` as 'no problems found'. WRONG — `isError=false` means the tool executed successfully; the problem list IS the payload. A file with 50 inspection warnings and a clean file both return `isError=false`. Only path validation failures, DumbService-blocked indexing, file-not-found, PSI parse failure, PSI invalidation mid-walk, and uncaught exceptions return `isError=true`. The LLM must read the content — 'No inspection problems found in X' vs 'N problem(s) in X:' — to determine whether a fix is needed. This is the shared F6 invariant across the diagnostics tool family (diagnostics, run_inspections, list_quickfixes, problem_view).")
        llmMistake("Calls run_inspections on a project with a large inspection profile and expects instantaneous results. The tool iterates EVERY enabled LocalInspectionToolWrapper in the profile and runs a PSI visitor for each — on large files with many enabled inspections this can take several seconds. If latency is a concern, use `severity=ERROR` to limit the walk to error-level inspections only, or use `diagnostics` for a faster single-pass compilation check.")
        llmMistake("Passes `severity=INFO` expecting only informational hints — INFO also includes WARNING and ERROR results because the filter is a minimum threshold (ordinal-based). 'ERROR' returns only errors; 'WARNING' returns warnings and errors; 'INFO' returns everything. The parameter name is misleading when read as a level filter rather than a floor.")
        llmMistake("Expects run_inspections to catch all compilation errors. It does not — the tool runs LocalInspectionTools (style, lint, quality inspections) not the compiler front-end. Compilation errors (unresolved references, type mismatches) belong to `diagnostics`. run_inspections is for quality-of-code issues beyond what the compiler checks.")
        llmMistake("Calls run_inspections immediately after edit_file and interprets problems that existed before the edit as regressions. The tool always reflects the current on-disk state; it does not diff against a baseline. The LLM should compare the problem list to what it knows was there before the edit, not assume all output is new.")
        params {
            required("path", "string") {
                llmSeesIt("File path to inspect")
                humanReadable("Which file to inspect — relative to the project root or absolute. Same path semantics as read_file and diagnostics.")
                whenPresent("Path is canonicalised and validated by PathValidator, the VFS entry is resolved, PSI is built, and the full LocalInspectionToolWrapper iteration runs under inSmartMode.")
                constraint("must point inside the project root or another allow-listed path root — traversal (../../etc/passwd) is rejected before any IDE call")
                constraint("must point to a file, not a directory")
                constraint("file must already exist in the VFS — unsaved scratch paths are not visible to findFileByIoFile")
                example("src/main/kotlin/com/example/UserService.kt")
                example("agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RunInspectionsTool.kt")
            }
            optional("severity", "string") {
                llmSeesIt("Minimum severity filter: 'ERROR', 'WARNING', or 'INFO'. Optional, defaults to WARNING.")
                humanReadable("The floor for which severity levels are included in the output. Think of it as a radio dial: ERROR returns only the most critical issues; WARNING returns warnings and errors; INFO returns everything the IDE can see.")
                whenPresent("Only problems whose normalised severity ordinal is <= the requested level are included in the result list. The PSI walk still runs for all enabled inspections; filtering is applied to the collected ProblemDescriptors.")
                whenAbsent("Defaults to WARNING — errors and warnings are returned; INFO-level hints are suppressed.")
                enumValue("ERROR", "WARNING", "INFO")
                constraint("case-insensitive; any unrecognised value is treated as WARNING")
                example("ERROR")
                example("WARNING")
                example("INFO")
            }
        }
        verdict {
            keep(
                "Unique access to IntelliJ's full inspection profile output on a single file — covers unused code, null safety, Spring misconfigurations, performance antipatterns, deprecations, and dozens of other quality checks that diagnostics (compiler front-end only) does not see. Respects the user's active inspection profile via profile.isToolEnabled(), so it reflects exactly what the user sees in their IDE. The 100K COMMAND output cap and ToolOutputSpiller integration make it safe for large files. No static analysis shell tool replicates this fidelity without a full project build.",
                VerdictSeverity.STRONG,
            )
        }
        related("diagnostics", Relationship.ALTERNATIVE, "Use diagnostics for fast per-file compilation checks (type errors, unresolved references) — it is faster and language-provider-based. Use run_inspections when you need the full inspection profile sweep (unused code, style, performance, Spring misconfig) that goes beyond what the compiler checks.")
        related("list_quickfixes", Relationship.COMPOSE_WITH, "run_inspections surfaces problems with the inspection short name and available fix names already included in the output. For a specific line, list_quickfixes gives the full Alt+Enter menu — the natural next step when the LLM wants to apply a suggested fix.")
        related("problem_view", Relationship.SEE_ALSO, "problem_view reads the IDE's existing Problems tool-window state without re-running inspections — faster but reflects what the IDE has already computed. Use run_inspections to force a fresh sweep; use problem_view to read cached results.")
        downside("Depends on indexing being complete — returns 'IDE is still indexing. Try again shortly.' (isError=true) during DumbService periods. Retry after a delay or do other non-PSI work first.")
        downside("Runs every enabled LocalInspectionToolWrapper in the profile sequentially — on files with many enabled inspections (300+) and a complex PSI tree the walk can take several seconds. The COMMAND output cap (100K) mitigates token cost, but latency is real for large files.")
        downside("Does NOT cover compilation errors (unresolved references, type mismatches) — those are the compiler front-end's domain and belong to `diagnostics`. run_inspections and diagnostics are complementary, not equivalent.")
        downside("Column numbers are -1 in DiagnosticEntry — ProblemDescriptor does not expose column through the current walk (Phase 7 follow-up). Line numbers are 1-based and correct.")
        downside("Inspection profile gating via profile.isToolEnabled() means results vary across projects depending on the user's active profile. A problem visible in one project may be absent in another if the inspection is disabled in that profile.")
        downside("Some inspections silently throw exceptions on certain file types and are skipped — the tool catches and suppresses per-inspection exceptions. This means the result list may omit problems from inspections that crash on the specific file.")
        observation("isError=false on problems-found is shared across the diagnostics tool family (diagnostics T5, run_inspections T2, list_quickfixes T3, problem_view T4). This invariant is documented in agent/CLAUDE.md ('ToolResult.isError semantics') and each tool's execute() KDoc. A dedicated llmMistake entry above and the F6 KDoc block on execute() reinforce it. Any future diagnostics-family tool must follow the same contract.")
        observation("profile.isToolEnabled() is intentionally used instead of isEnabledByDefault — this ensures the tool sweep matches what the user sees in the IDE editor. ListQuickFixesTool uses the same gating for the same reason.")
    }

    /**
     * ## `isError` semantics (F6 invariant)
     *
     * `ToolResult.isError` distinguishes **tool-execution failure** from
     * **problems-as-payload**:
     *
     * - `isError = true`  → the tool itself could not run: missing `path`
     *   parameter, `DumbService` blocked during indexing, file not found,
     *   PSI parse failure, or an uncaught exception during the walk.
     * - `isError = false` → the tool ran to completion. Zero problems, one
     *   problem, or a thousand problems — all `isError = false`. The problem
     *   list IS the successful payload; a populated list is not a failure.
     *
     * This mirrors the contract already documented for `SemanticDiagnosticsTool`
     * in `agent/CLAUDE.md` ("returns `isError=false` when problems are found").
     * [RunInspectionsToolTest] pins the error-path invariant at the unit-test
     * boundary; the problems-found branch is covered structurally (the only
     * `isError = true` construction in this file is at the error sites).
     */
    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError

        val minSeverity = parseSeverity(params["severity"]?.jsonPrimitive?.content ?: "WARNING")

        if (DumbService.isDumb(project)) {
            return ToolResult("IDE is still indexing. Try again shortly.", "Indexing", 5, isError = true)
        }

        return try {
            val result = smartReadAction(project) {
                val vf = LocalFileSystem.getInstance().findFileByIoFile(java.io.File(path!!))
                    ?: return@smartReadAction ToolResult("File not found: $path", "Not found", 5, isError = true)
                val psiFile = PsiManager.getInstance(project).findFile(vf)
                    ?: return@smartReadAction ToolResult("Cannot parse: $path", "Parse error", 5, isError = true)
                if (!psiFile.isValid) return@smartReadAction null

                val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
                val inspectionManager = InspectionManager.getInstance(project)
                val allProblems = mutableListOf<ProblemInfo>()

                for (toolWrapper in profile.getInspectionTools(psiFile)) {
                    if (toolWrapper !is LocalInspectionToolWrapper) continue
                    val key = HighlightDisplayKey.find(toolWrapper.shortName)
                    if (key == null || !profile.isToolEnabled(key, psiFile)) continue
                    val tool = toolWrapper.tool

                    try {
                        // TODO(phase7): F5 — the phase 6 plan proposed routing this
                        // through `LocalInspectionToolWrapper.processFile(psiFile,
                        // session)` to abstract visitor construction + session setup.
                        // That overload is not exposed on the public platform API
                        // surface we compile against (251.x) — docs/superpowers/
                        // research/2026-03-20-intellij-api-signatures.md §4 lists
                        // the manual buildVisitor walk as the documented approach
                        // and flags `InspectionEngine` as internal. Re-evaluate when
                        // the platform publishes a non-deprecated replacement.
                        val holder = ProblemsHolder(inspectionManager, psiFile, false)
                        val visitor = tool.buildVisitor(holder, false)
                        psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                            override fun visitElement(element: PsiElement) {
                                ProgressManager.checkCanceled()
                                element.accept(visitor)
                                super.visitElement(element)
                            }
                        })

                        for (problem in holder.results) {
                            // Shared canonical mapping lives in DiagnosticModels.kt
                            // (`normalizeSeverity`) so T2/T3/T4/T5 emit identical
                            // DiagnosticEntry.severity values for the same input.
                            val severityName = normalizeSeverity(problem.highlightType)
                            val severity = Severity.fromName(severityName)
                            if (severity.ordinal <= minSeverity.ordinal) {
                                val line = problem.lineNumber + 1 // ProblemDescriptor uses 0-based
                                val fixes = problem.fixes?.map { it.familyName } ?: emptyList()
                                allProblems.add(ProblemInfo(
                                    line = line,
                                    severity = severity,
                                    message = problem.descriptionTemplate,
                                    inspection = toolWrapper.shortName,
                                    fixes = fixes
                                ))
                            }
                        }
                    } catch (_: Exception) {
                        // Some inspections may fail on certain file types — skip silently
                    }
                }

                if (allProblems.isEmpty()) {
                    ToolResult("No inspection problems found in ${vf.name} (severity >= $minSeverity).", "No problems", 5)
                } else {
                    allProblems.sortBy { it.line }

                    // Build the full structured list BEFORE any cap is applied — the
                    // Phase 7 ToolOutputSpiller reads this off the result via the
                    // DIAGNOSTIC-STRUCTURED-DATA marker and routes it to disk, so it
                    // must contain every problem regardless of the prose preview cap.
                    val entries = allProblems.map { p ->
                        DiagnosticEntry(
                            file = vf.path,                // absolute path for Phase 7 link-back
                            line = p.line,                 // already 1-based
                            column = -1,                   // column not tracked by current walk; -1 = unknown
                            severity = p.severity.name,    // "ERROR" | "WARNING" | "INFO"
                            toolId = p.inspection,         // inspection short name
                            description = p.message,
                            hasQuickFix = p.fixes.isNotEmpty(),
                            category = null,               // IntelliJ group name not exposed by current walk
                        )
                    }

                    // Phase 7 structured-preview strategy:
                    // prose preview uses head-20 entries (PREVIEW_ENTRIES) — readable
                    // standalone without reading the spilled file.
                    val previewEntries = allProblems.take(PREVIEW_ENTRIES)
                    val lines = previewEntries.map { p ->
                        val fixHint = if (p.fixes.isNotEmpty()) " [fixes: ${p.fixes.joinToString(", ")}]" else ""
                        "  Line ${p.line} [${p.severity}] ${p.message} (${p.inspection})$fixHint"
                    }
                    val prose = "${allProblems.size} problem(s) in ${vf.name}:\n${lines.joinToString("\n")}"
                    val content = renderDiagnosticBody(prose, entries)
                    // F6: problems-found is a SUCCESSFUL tool result (isError=false).
                    // Phase 7: outer coroutine will call spillOrFormat on the full body.
                    ToolResult(content, "${allProblems.size} problems", TokenEstimator.estimate(content))
                }
            }

            if (result == null) {
                return ToolResult("PSI file became invalid during analysis.", "Invalid", 5, isError = true)
            }
            // Phase 7: spill the full JSON body (prose + DIAGNOSTIC_STRUCTURED_DATA_MARKER + JSON)
            // when it exceeds the 30K threshold. The prose preview is always readable inline;
            // the full structured JSON list is available on disk for read_file / search_code.
            val spilled = spillOrFormat(result.content, project)
            if (spilled.spilledToFile != null) {
                val (prose, entries) = parseDiagnosticBody(result.content)
                val spillContent = buildString {
                    append(prose)
                    append("\n\n[Full structured list (${entries.size} entries) saved to: ${spilled.spilledToFile}]")
                    append("\n[Read with read_file or search_code for inspection matching, filtering by severity/file/inspection]")
                }
                ToolResult(
                    content = spillContent,
                    summary = result.summary,
                    tokenEstimate = TokenEstimator.estimate(spillContent),
                    spillPath = spilled.spilledToFile,
                )
            } else {
                result
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult("Error running inspections: ${e.message}", "Error", 5, isError = true)
        }
    }

    private fun parseSeverity(value: String): Severity {
        return when (value.uppercase()) {
            "ERROR" -> Severity.ERROR
            "INFO" -> Severity.INFO
            else -> Severity.WARNING
        }
    }

    /**
     * Internal severity bucket used ONLY for the `minSeverity` threshold
     * comparison (ordinal-based). The emitted [DiagnosticEntry.severity]
     * string goes through the shared [normalizeSeverity] mapper — the enum
     * `.name` values here MUST agree with the shared mapper's output so the
     * filter and the emitted field are consistent.
     */
    private enum class Severity {
        ERROR, WARNING, INFO;

        companion object {
            fun fromName(name: String): Severity = when (name) {
                "ERROR" -> ERROR
                "WARNING" -> WARNING
                else -> INFO // "INFO" and any future shared-vocabulary addition
            }
        }
    }

    private data class ProblemInfo(
        val line: Int,
        val severity: Severity,
        val message: String,
        val inspection: String,
        val fixes: List<String>
    )

    companion object {
        /**
         * Head-preview entry count for the inline prose preview (Phase 7 structured-preview strategy).
         * 20 entries fit comfortably in the LLM's context and give enough signal to act without
         * reading the full spilled JSON. The full entry list is always in the spilled file when
         * the JSON body exceeds ToolOutputConfig.SPILL_THRESHOLD_CHARS (30K).
         */
        private const val PREVIEW_ENTRIES = 20
    }
}
