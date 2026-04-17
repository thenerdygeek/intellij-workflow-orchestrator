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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class ListQuickFixesTool : AgentTool {
    override val name = "list_quickfixes"
    override val description = "List available quick fixes (Alt+Enter actions) for a specific line in a file. Shows what IntelliJ can auto-fix. Useful for understanding how to resolve errors."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "File path"),
            "line" to ParameterProperty(type = "integer", description = "Line number (1-based)")
        ),
        required = listOf("path", "line")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.ANALYZER, WorkerType.REVIEWER)

    /**
     * ## `isError` semantics (Task 5.6 / F6 invariant)
     *
     * `ToolResult.isError` distinguishes **tool-execution failure** from
     * **quick-fixes-as-payload**:
     *
     * - `isError = true`  → the tool itself could not run: missing `path` or
     *   `line` parameter, `line < 1`, `line` beyond end-of-file, `DumbService`
     *   blocked during indexing, file not found, PSI parse failure, or an
     *   uncaught exception during the walk.
     * - `isError = false` → the tool ran to completion. "N quick fixes
     *   available at line X" and "no quick fixes available at line X" are
     *   BOTH successful results — zero fixes is a legitimate outcome when the
     *   user asks about a line with nothing to fix. The fix list IS the
     *   successful payload; a populated list is not a failure.
     *
     * This mirrors the contract documented for `SemanticDiagnosticsTool` and
     * `RunInspectionsTool` (see agent/CLAUDE.md). [ListQuickFixesToolTest]
     * pins the error-path invariant at the unit-test boundary; the
     * problems-found branch is covered structurally (the only `isError = true`
     * construction in this file is at the error sites).
     */
    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val line = params["line"]?.jsonPrimitive?.int
            ?: return ToolResult("Error: 'line' required (1-based)", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError

        if (line < 1) {
            return ToolResult("Error: 'line' must be >= 1", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        // F3: DumbService guard placed OUTSIDE the outer try so indexing-state
        // signals are surfaced as their own ToolResult rather than being
        // swallowed by the outer catch. Matches the T2 RunInspectionsTool
        // post-fix pattern (see RunInspectionsTool.kt lines 70–72).
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

                val document = psiFile.viewProvider.document
                    ?: return@nonBlocking ToolResult("No document for: $path", "No document", 5, isError = true)

                val targetLine = line - 1 // Convert to 0-based
                if (targetLine >= document.lineCount) {
                    return@nonBlocking ToolResult(
                        "Line $line exceeds file length (${document.lineCount} lines).",
                        "Invalid line",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }

                val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
                val inspectionManager = InspectionManager.getInstance(project)
                val quickFixes = mutableListOf<QuickFixInfo>()

                // TODO(phase7): F6 — the phase 6 plan proposed reading
                // already-computed HighlightInfo from
                // `DaemonCodeAnalyzerImpl.getFileHighlightingRanges()` plus
                // `HighlightInfo.findRegisteredQuickFix(...)` (or
                // `IntentionManager.getAvailableActions(editor, psiFile)`) so
                // quick-fix extraction does not re-run the full inspection
                // suite per invocation. That API is impl-level (the `Impl`
                // suffix) and is not documented in
                // docs/superpowers/research/2026-03-20-intellij-api-signatures.md,
                // and `IntentionManager.getAvailableActions` needs an Editor
                // instance we cannot obtain for unopened files. The audit
                // (docs/research/2026-04-17-inspection-tools-audit.md §F6)
                // classifies this as redundant work (performance), not a
                // safety issue — fallback to the manual buildVisitor walk is
                // acceptable. Re-evaluate when a public replacement lands.
                for (toolWrapper in profile.getInspectionTools(psiFile)) {
                    if (toolWrapper !is LocalInspectionToolWrapper) continue
                    val key = HighlightDisplayKey.find(toolWrapper.shortName)
                    if (key == null || !profile.isToolEnabled(key, psiFile)) continue
                    val tool = toolWrapper.tool

                    try {
                        val holder = ProblemsHolder(inspectionManager, psiFile, false)
                        val visitor = tool.buildVisitor(holder, false)
                        psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                            override fun visitElement(element: PsiElement) {
                                element.accept(visitor)
                                super.visitElement(element)
                            }
                        })

                        for (problem in holder.results) {
                            val problemLine = problem.lineNumber // 0-based
                            if (problemLine == targetLine) {
                                val fixes = problem.fixes
                                if (fixes != null && fixes.isNotEmpty()) {
                                    // Shared canonical mapping lives in
                                    // DiagnosticModels.kt (`normalizeSeverity`) so
                                    // T2/T3/T4/T5 emit identical
                                    // DiagnosticEntry.severity values for the same
                                    // ProblemHighlightType input. Do NOT leak raw
                                    // enum names ("GENERIC_ERROR_OR_WARNING",
                                    // "LIKE_UNUSED_SYMBOL", "INFORMATION", …) —
                                    // Phase 7 consumers filter/sort by severity
                                    // and require the canonical vocabulary.
                                    val severityName = normalizeSeverity(problem.highlightType)
                                    for (fix in fixes) {
                                        quickFixes.add(QuickFixInfo(
                                            fixName = fix.familyName,
                                            problem = problem.descriptionTemplate,
                                            inspection = toolWrapper.shortName,
                                            problemLine = problemLine,                      // 0-based
                                            severity = severityName,                        // canonical: ERROR / WARNING / INFO (WEAK_WARNING reserved)
                                        ))
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Skip inspections that fail
                    }
                }

                if (quickFixes.isEmpty()) {
                    // F6: zero quick fixes is a LEGITIMATE successful result —
                    // the user may have asked about a line with nothing to fix.
                    // isError=false (ToolResult default).
                    ToolResult(
                        "No quick fixes available at line $line in ${vf.name}.",
                        "No fixes",
                        5
                    )
                } else {
                    // Deduplicate by fix name. Ordering contract: build the
                    // LOSSLESS DiagnosticEntry list from the de-duplicated
                    // collection BEFORE the preview cap is applied below, so
                    // Phase 7's spiller can route the full list to disk even
                    // when the inline preview is capped.
                    val unique = quickFixes.distinctBy { it.fixName }

                    // Build the full structured entry list BEFORE any cap is
                    // applied — matches the T2 RunInspectionsTool ordering.
                    // Every entry is a quick fix BY CONSTRUCTION (we only
                    // entered this branch from a non-empty `problem.fixes`),
                    // so `hasQuickFix = true` is hardcoded rather than
                    // conditional. This differs in shape from
                    // RunInspectionsTool, whose entries may or may not have
                    // fixes.
                    val entries = unique.map { qf ->
                        DiagnosticEntry(
                            file = vf.path,                                    // absolute path; DiagnosticEntry.file contract (52b0d867)
                            line = qf.problemLine + 1,                         // 0-based problem → 1-based DiagnosticEntry
                            column = -1,                                       // ProblemDescriptor does not expose column
                            severity = qf.severity,                            // canonical DiagnosticEntry vocabulary: ERROR / WARNING / INFO (via normalizeSeverity; WEAK_WARNING reserved)
                            toolId = qf.inspection,                            // inspection short name
                            description = "${qf.fixName} — ${qf.problem}",    // fix name front-loaded per T3 brief
                            hasQuickFix = true,                                // invariant: every T3 entry IS a quick fix
                            category = null,                                   // IntelliJ group name not resolved cheaply here
                        )
                    }

                    // Phase 7 structured-preview strategy:
                    // prose preview uses head-20 entries (PREVIEW_ENTRIES) — readable
                    // standalone without reading the spilled file.
                    val previewEntries = unique.take(PREVIEW_ENTRIES)
                    val lines = previewEntries.map { qf ->
                        "  - ${qf.fixName}\n    Problem: ${qf.problem} (${qf.inspection})"
                    }
                    val prose = "${unique.size} quick fix(es) at line $line in ${vf.name}:\n${lines.joinToString("\n")}"
                    // Return entries alongside prose so the outer coroutine can
                    // call spillOrFormat on the full JSON (suspend call, cannot
                    // run inside ReadAction.nonBlocking lambda).
                    ToolResult(
                        content = renderDiagnosticBody(prose, entries),
                        summary = "${unique.size} fixes at line $line",
                        tokenEstimate = TokenEstimator.estimate(prose),
                    )
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
            ToolResult("Error listing quick fixes: ${e.message}", "Error", 5, isError = true)
        }
    }

    private data class QuickFixInfo(
        val fixName: String,
        val problem: String,
        val inspection: String,
        /** 0-based — matches IntelliJ's `ProblemDescriptor.lineNumber`. Converted to 1-based for [DiagnosticEntry]. */
        val problemLine: Int,
        /**
         * Canonical [DiagnosticEntry.severity] vocabulary value — one of
         * `"ERROR" | "WARNING" | "INFO"` — produced by the shared
         * [normalizeSeverity] mapper in DiagnosticModels.kt (`"WEAK_WARNING"`
         * is reserved for a future vocabulary extension). Do NOT substitute a
         * raw `ProblemHighlightType.name` here: Phase 7 consumers filter and
         * sort by severity and require the canonical vocabulary, identical to
         * the output of T2 RunInspectionsTool.
         */
        val severity: String,
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
