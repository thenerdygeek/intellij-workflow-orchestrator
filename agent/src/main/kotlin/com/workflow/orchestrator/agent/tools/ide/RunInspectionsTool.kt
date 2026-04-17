package com.workflow.orchestrator.agent.tools.ide

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
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
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
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
            val result = ReadAction.nonBlocking<ToolResult?> {
                val vf = LocalFileSystem.getInstance().findFileByIoFile(java.io.File(path!!))
                    ?: return@nonBlocking ToolResult("File not found: $path", "Not found", 5, isError = true)
                val psiFile = PsiManager.getInstance(project).findFile(vf)
                    ?: return@nonBlocking ToolResult("Cannot parse: $path", "Parse error", 5, isError = true)
                if (!psiFile.isValid) return@nonBlocking null

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
                                element.accept(visitor)
                                super.visitElement(element)
                            }
                        })

                        for (problem in holder.results) {
                            val severity = mapHighlightType(problem.highlightType)
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

                    // TODO(phase7): spill via ToolOutputSpiller instead of hard-capping at MAX_PROBLEMS
                    val shown = allProblems.take(MAX_PROBLEMS)
                    val lines = shown.map { p ->
                        val fixHint = if (p.fixes.isNotEmpty()) " [fixes: ${p.fixes.joinToString(", ")}]" else ""
                        "  Line ${p.line} [${p.severity}] ${p.message} (${p.inspection})$fixHint"
                    }
                    // TODO(phase7): replace "... and N more" preview with disk-spill reference
                    val more = if (allProblems.size > MAX_PROBLEMS) "\n... and ${allProblems.size - MAX_PROBLEMS} more" else ""
                    val prose = "${allProblems.size} problem(s) in ${vf.name}:\n${lines.joinToString("\n")}$more"
                    val content = renderDiagnosticBody(prose, entries)
                    // F6: problems-found is a SUCCESSFUL tool result (isError=false).
                    ToolResult(content, "${allProblems.size} problems", TokenEstimator.estimate(content))
                }
            }.inSmartMode(project).executeSynchronously()
            result ?: ToolResult("PSI file became invalid during analysis.", "Invalid", 5, isError = true)
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

    private fun mapHighlightType(type: ProblemHighlightType): Severity {
        return when (type) {
            ProblemHighlightType.ERROR, ProblemHighlightType.GENERIC_ERROR -> Severity.ERROR
            ProblemHighlightType.WARNING, ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> Severity.WARNING
            else -> Severity.INFO
        }
    }

    private enum class Severity { ERROR, WARNING, INFO }

    private data class ProblemInfo(
        val line: Int,
        val severity: Severity,
        val message: String,
        val inspection: String,
        val fixes: List<String>
    )

    companion object {
        // TODO(phase7): replace this hard cap with ToolOutputSpiller — Phase 7 will
        // route the full entry list to disk and leave a preview inline.
        private const val MAX_PROBLEMS = 30
    }
}
