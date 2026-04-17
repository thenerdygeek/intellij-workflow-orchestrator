package com.workflow.orchestrator.agent.tools.ide

import com.intellij.codeInspection.InspectionManager
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
            }.inSmartMode(project).executeSynchronously()

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
