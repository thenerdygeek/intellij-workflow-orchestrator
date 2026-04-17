package com.workflow.orchestrator.agent.tools.ide

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.psi.PsiDocumentManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProblemViewTool : AgentTool {
    override val name = "problem_view"
    override val description = "Get current problems (errors, warnings) from the IDE's analysis. " +
        "Shows compilation errors, inspection warnings, and unresolved references. " +
        "Note: only shows problems for files that have been opened in the editor — " +
        "for unopened files, use the diagnostics tool instead."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(
                type = "string",
                description = "Specific file to check (e.g., 'src/main/kotlin/MyService.kt'). Lists all problem files if omitted."
            ),
            "severity" to ParameterProperty(
                type = "string",
                description = "Filter by severity: 'error', 'warning', or 'all' (default: 'all')",
                enumValues = listOf("error", "warning", "all")
            )
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.CODER, WorkerType.REVIEWER)

    /**
     * ## `isError` semantics (Task 5.6 / F6 invariant)
     *
     * `ToolResult.isError` distinguishes **tool-execution failure** from
     * **problems-as-payload**.
     *
     * ### The five `isError = true` sites (exhaustive)
     *
     * 1. **Invalid `severity` enum value** — rejected before any IDE call.
     * 2. **Malformed path** (via `PathValidator.resolveAndValidate`) —
     *    traversal attempts (`../../etc/passwd`), missing `project.basePath`.
     * 3. **Valid path, missing file** (`findFileByIoFile` returns null) —
     *    the path resolved cleanly but no VFS entry exists at that location.
     *    Distinct from #2: the input was structurally valid, the target
     *    just isn't present. Emits `"File not found: $filePath"`.
     * 4. **`DumbService` blocked during indexing** — Wolf's cache may be
     *    stale while the index is still being built; short-circuit with a
     *    retry hint rather than emit misleading zero-problem results.
     * 5. **Uncaught exception** escaping the outer `try`/`catch` in
     *    [execute] — any unexpected IDE/PSI failure.
     *
     * ### Success payloads (`isError = false`)
     *
     * **Per-file mode** (caller passed `file`):
     * - `"No problems in X"` — clean file.
     * - `"Flagged but no details for X"` — Wolf flagged the file but no
     *   `HighlightInfo` is available (file not open in editor).
     * - `"N problems in X"` — full structured entry list attached.
     *
     * **All-open-files mode** (`file` omitted):
     * - `"No files are open in the editor"` — empty editor state.
     * - `"No problems found in N open file(s)"` — all clean.
     * - `"Problems in project (N file(s))"` — aggregated entries across
     *   all open files.
     *
     * The problem list IS the payload, not a failure signal. This mirrors
     * the contract documented for `SemanticDiagnosticsTool`,
     * `RunInspectionsTool` (T2), and `ListQuickFixesTool` (T3). See
     * `agent/CLAUDE.md`. [ProblemViewToolTest] pins the error-path invariant
     * at the unit-test boundary; the problems-found branch is covered
     * structurally (the only `isError = true` construction in this file is
     * at the five designated error sites above).
     */
    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file"]?.jsonPrimitive?.content
        val severity = params["severity"]?.jsonPrimitive?.content ?: "all"

        if (severity !in setOf("error", "warning", "all")) {
            return ToolResult(
                "Error: severity must be 'error', 'warning', or 'all'",
                "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        // F3 (Task 5.2): DumbService guard placed OUTSIDE the outer try so
        // indexing-state signals are surfaced as their own ToolResult rather
        // than being swallowed by the outer catch. Matches the placement
        // pattern in RunInspectionsTool.kt lines 70–72 and ListQuickFixesTool.kt
        // lines 77–79. WolfTheProblemSolver's cache may be stale during
        // indexing — short-circuiting here avoids emitting misleading
        // zero-problem results while the index is still being built.
        if (DumbService.isDumb(project)) {
            return ToolResult(
                "Indexing in progress — problem view cache may be stale. Try again shortly.",
                "Indexing", 5, isError = true
            )
        }

        return try {
            if (filePath != null) {
                getProblemsForFile(filePath, severity, project)
            } else {
                getProblemsForAllOpenFiles(severity, project)
            }
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private suspend fun getProblemsForFile(filePath: String, severity: String, project: Project): ToolResult {
        val (path, pathError) = PathValidator.resolveAndValidate(filePath, project.basePath)
        if (pathError != null) return pathError

        return ReadAction.nonBlocking<ToolResult> {
            val vf = LocalFileSystem.getInstance().findFileByIoFile(java.io.File(path!!))
                ?: return@nonBlocking ToolResult(
                    "File not found: $filePath",
                    "Not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )

            // F1 (Task 5.1): collect the FULL problem list (uncapped) so the
            // structured entry list is lossless. The preview cap applies
            // only to the prose rendering below — Phase 7's spiller reads
            // entries off the ToolResult and must see every problem, not
            // the capped subset.
            val problems = collectHighlightProblems(vf, severity, project)
            val relativePath = project.basePath?.let {
                vf.path.removePrefix(it).removePrefix("/")
            } ?: vf.name

            if (problems.isEmpty()) {
                val wolf = WolfTheProblemSolver.getInstance(project)
                val hasProblemFlag = wolf.isProblemFile(vf)
                if (hasProblemFlag) {
                    ToolResult(
                        "No detailed problems available for $relativePath (file flagged as problematic by IDE but no HighlightInfo — file may not be open in editor).",
                        "Flagged but no details", 10
                    )
                } else {
                    ToolResult("No problems in $relativePath.", "No problems", 5)
                }
            } else {
                // Build the LOSSLESS structured list BEFORE the prose cap is
                // applied inside formatFileProblemsWithCap. Every entry
                // embeds the ABSOLUTE file path from `vf.path` per the
                // DiagnosticEntry.file contract (commit 52b0d867).
                val entries = problems.map { p ->
                    DiagnosticEntry(
                        file = vf.path,             // absolute path; DiagnosticEntry.file contract (52b0d867)
                        line = p.line,
                        column = p.column,
                        severity = p.severity,      // canonical DiagnosticEntry vocabulary SUBSET: ERROR / WARNING
                        toolId = p.toolId,
                        description = p.description,
                        hasQuickFix = false,        // see TODO(phase7) on hasQuickFix resolution below
                        category = null,
                    )
                }
                val (prose, tokenEstimate) = formatFileProblemsWithCap(relativePath, problems)
                val content = renderDiagnosticBody(prose, entries)
                ToolResult(
                    content,
                    "${problems.size} problems in ${vf.name}",
                    TokenEstimator.estimate(content).coerceAtLeast(tokenEstimate)
                )
            }
        }.executeSynchronously()
    }

    private suspend fun getProblemsForAllOpenFiles(severity: String, project: Project): ToolResult {
        return ReadAction.nonBlocking<ToolResult> {
            val openFiles = FileEditorManager.getInstance(project).openFiles
            if (openFiles.isEmpty()) {
                return@nonBlocking ToolResult("No files are open in the editor.", "No open files", 5)
            }

            val wolf = WolfTheProblemSolver.getInstance(project)
            // Per-file aggregation retains both relative path (for prose) and
            // absolute path (for DiagnosticEntry.file). Prose keeps the
            // relative path for readability; the structured field MUST be
            // absolute per the DiagnosticEntry.file contract (52b0d867).
            val filesWithProblems = mutableListOf<FileProblems>()

            for (vf in openFiles) {
                val problems = collectHighlightProblems(vf, severity, project)
                val relativePath = project.basePath?.let {
                    vf.path.removePrefix(it).removePrefix("/")
                } ?: vf.name
                if (problems.isNotEmpty()) {
                    filesWithProblems.add(FileProblems(relativePath, vf.path, problems))
                } else if (wolf.isProblemFile(vf)) {
                    // Wolf flagged the file but we have no HighlightInfo to
                    // enumerate — synthesize a single placeholder entry so
                    // the LLM sees the file was flagged.
                    filesWithProblems.add(
                        FileProblems(
                            relativePath,
                            vf.path,
                            listOf(
                                ProblemEntry(
                                    severity = "WARNING",
                                    line = 0,
                                    column = -1,
                                    description = "File flagged as problematic (no detailed info available)",
                                    toolId = DiagnosticSubsystem.WOLF
                                )
                            )
                        )
                    )
                }
            }

            if (filesWithProblems.isEmpty()) {
                ToolResult("No problems found in ${openFiles.size} open file(s).", "No problems", 5)
            } else {
                val totalProblems = filesWithProblems.sumOf { it.problems.size }

                // F1 (Task 5.1): build the LOSSLESS aggregated DiagnosticEntry
                // list across ALL files BEFORE applying any per-file or
                // overall preview cap. Phase 7's spiller reads entries off
                // the ToolResult and must see every problem across every
                // file. `fp.absolutePath` is the ABSOLUTE path (sourced from
                // vf.path upstream) per the DiagnosticEntry.file contract.
                val entries = filesWithProblems.flatMap { fp ->
                    fp.problems.map { p ->
                        DiagnosticEntry(
                            file = fp.absolutePath,     // absolute; originally vf.path — DiagnosticEntry.file contract (52b0d867)
                            line = p.line,
                            column = p.column,
                            severity = p.severity,      // canonical DiagnosticEntry vocabulary SUBSET: ERROR / WARNING
                            toolId = p.toolId,
                            description = p.description,
                            hasQuickFix = false,        // see TODO(phase7) on hasQuickFix resolution below
                            category = null,
                        )
                    }
                }

                val sb = StringBuilder()
                sb.appendLine("Problems in project (${filesWithProblems.size} file(s)):")
                for (fp in filesWithProblems) {
                    sb.appendLine()
                    sb.append(formatFileProblemsWithCap(fp.relativePath, fp.problems).first)
                }
                val prose = sb.toString().trimEnd()
                val content = renderDiagnosticBody(prose, entries)
                ToolResult(
                    content,
                    "$totalProblems problems in ${filesWithProblems.size} files",
                    TokenEstimator.estimate(content)
                )
            }
        }.executeSynchronously()
    }

    /**
     * Collect the FULL (uncapped) problem list for a single virtual file.
     * Severity filter is applied here; preview capping is deferred to callers
     * so the lossless entry list can be routed to Phase 7's spiller while the
     * inline prose preview remains terse.
     */
    private fun collectHighlightProblems(
        vf: com.intellij.openapi.vfs.VirtualFile,
        severity: String,
        project: Project
    ): List<ProblemEntry> {
        val document = PsiDocumentManager.getInstance(project).run {
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
        } ?: return emptyList()

        val markupModel = DocumentMarkupModel.forDocument(document, project, false)
            ?: return emptyList()

        val problems = mutableListOf<ProblemEntry>()

        for (highlighter in markupModel.allHighlighters) {
            val info = HighlightInfo.fromRangeHighlighter(highlighter) ?: continue
            val infoSeverity = info.severity
            val description = info.description ?: continue

            // HighlightSeverity normalization (inline — see decision note
            // below). The shared `normalizeSeverity(ProblemHighlightType)`
            // in DiagnosticModels.kt handles `ProblemHighlightType`, not
            // `HighlightSeverity`, so we keep the existing 2-value SUBSET
            // of the shared 3-value vocabulary here: ERROR → "ERROR",
            // WARNING → "WARNING", everything below is dropped via
            // `continue` (matches pre-T4 behaviour). Pinned by
            // ProblemViewToolTest.`source file emits ERROR and WARNING
            // severities in canonical uppercase vocabulary`.
            //
            // DECISION: inline rather than extracting a
            // `normalizeHighlightSeverity(HighlightSeverity): String` sibling
            // helper. Rationale: T5 SemanticDiagnosticsTool MAY also use
            // HighlightSeverity, at which point extraction is justified —
            // but pre-emptive extraction with one caller is premature
            // abstraction. See the brief's recommendation and the
            // DiagnosticModels.kt kdoc.
            val severityLabel = when {
                infoSeverity >= HighlightSeverity.ERROR -> "ERROR"
                infoSeverity >= HighlightSeverity.WARNING -> "WARNING"
                else -> continue // skip INFO and below
            }

            // Apply severity filter
            when (severity) {
                "error" -> if (severityLabel != "ERROR") continue
                "warning" -> if (severityLabel != "WARNING") continue
                // "all" — no filter
            }

            val zeroBasedLine = document.getLineNumber(info.startOffset)
            val line = zeroBasedLine + 1
            // Column: cheap to compute from the startOffset we already have.
            // 1-based; DiagnosticEntry kdoc allows -1 when column is unknown.
            val lineStart = document.getLineStartOffset(zeroBasedLine)
            val column = (info.startOffset - lineStart + 1).coerceAtLeast(1)

            // toolId: `HighlightInfo.inspectionToolId` surfaces the
            // originating inspection short name when the highlight came from
            // a LocalInspectionTool pass. Null when the highlight is from
            // the compiler/parser daemon itself — fall back to "daemon" per
            // DiagnosticEntry.toolId kdoc, which explicitly allows subsystem
            // IDs ("daemon", "wolf", "provider").
            val toolId = info.inspectionToolId ?: DiagnosticSubsystem.DAEMON

            problems.add(
                ProblemEntry(
                    severity = severityLabel,
                    line = line,
                    column = column,
                    description = description,
                    toolId = toolId,
                )
            )
        }

        // Sort: errors first, then by line number. NO cap — callers decide.
        problems.sortWith(compareBy({ if (it.severity == "ERROR") 0 else 1 }, { it.line }))

        return problems
    }

    /**
     * Format the prose preview for a single file's problems, applying the
     * MAX_PROBLEMS preview cap. Returns (prose, prose-token-estimate).
     *
     * F1 (Task 5.1): the cap applies ONLY to the prose preview. Callers
     * build `DiagnosticEntry` from the LOSSLESS problem list BEFORE
     * invoking this helper so Phase 7's spiller sees every problem.
     */
    private fun formatFileProblemsWithCap(
        relativePath: String,
        problems: List<ProblemEntry>
    ): Pair<String, Int> {
        val sb = StringBuilder()
        sb.appendLine("$relativePath — ${problems.size} problem(s):")

        // TODO(phase7): replace this hard cap with ToolOutputSpiller — Phase 7
        // will route the full entry list to disk and leave a preview inline.
        val shown = problems.take(MAX_PROBLEMS)
        for (p in shown) {
            if (p.line > 0) {
                sb.appendLine("  ${p.severity} line ${p.line}: ${p.description}")
            } else {
                sb.appendLine("  ${p.severity}: ${p.description}")
            }
        }
        // TODO(phase7): replace "... and N more" preview with disk-spill reference
        if (problems.size > MAX_PROBLEMS) {
            sb.appendLine("... and ${problems.size - MAX_PROBLEMS} more")
        }
        val text = sb.toString()
        return text to TokenEstimator.estimate(text)
    }

    /**
     * Per-problem intermediate model carrying everything needed to build a
     * [DiagnosticEntry] and format a prose line. Matches the T2 `ProblemInfo`
     * / T3 `QuickFixInfo` pattern — intentionally a private data class local
     * to this file.
     *
     * TODO(phase7): `hasQuickFix` is pinned to `false` at the two
     * [DiagnosticEntry] construction sites above. `HighlightInfo.hasHint()`
     * and `HighlightInfo.quickFixActionRanges` exist but require impl-level
     * access (`com.intellij.codeInsight.daemon.impl`); the
     * `IntentionManager.getAvailableActions(editor, psiFile)` alternative
     * needs an Editor instance we cannot obtain for unopened files. Phase 7
     * or a follow-up spike can adopt a verified public API. The audit
     * (docs/research/2026-04-17-inspection-tools-audit.md) classifies
     * quick-fix enrichment here as informational, not correctness-critical.
     */
    private data class ProblemEntry(
        /** Canonical [DiagnosticEntry.severity] vocabulary SUBSET: "ERROR" or "WARNING" only. */
        val severity: String,
        /** 1-based line number. 0 indicates unknown (synthetic Wolf-flagged entry). */
        val line: Int,
        /** 1-based column; -1 when unknown. */
        val column: Int,
        val description: String,
        /** Inspection short name when available; "daemon" for compiler/daemon highlights; "wolf" for Wolf placeholders. */
        val toolId: String,
    )

    /**
     * Aggregation struct for the all-open-files path — pairs per-file prose paths
     * with the absolute path used for `DiagnosticEntry.file`. `absolutePath: String`
     * is deliberately a plain string (not a `VirtualFile` reference) so the struct
     * never leaks a VFS object past the `ReadAction.nonBlocking` lambda where it
     * was sourced. Captured absolute paths stay safe to use from any thread.
     */
    private data class FileProblems(
        val relativePath: String,
        val absolutePath: String,
        val problems: List<ProblemEntry>,
    )

    companion object {
        // TODO(phase7): replace this hard cap with ToolOutputSpiller — Phase 7
        // will route the full entry list to disk and leave a preview inline.
        private const val MAX_PROBLEMS = 30
    }
}
