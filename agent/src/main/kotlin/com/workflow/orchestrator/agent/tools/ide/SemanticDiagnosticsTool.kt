package com.workflow.orchestrator.agent.tools.ide

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.psi.PsiManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class SemanticDiagnosticsTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    companion object {
        /** Lines of buffer around the edited range to include in diagnostics. */
        private const val EDIT_LINE_BUFFER = 5

        // TODO(phase7): replace this hard cap with ToolOutputSpiller — Phase 7
        // will route the full entry list to disk and leave a preview inline.
        private const val MAX_ISSUES = 20
    }

    override val name = "diagnostics"
    override val description = "Check a file for compilation errors using the IDE's semantic analysis engine — syntax errors, unresolved references, type mismatches, missing imports. Faster and more precise than running mvn compile or gradle build. Use this instead of shell build commands to verify code correctness. When run after edit_file, automatically scopes to only NEW issues near the edited lines."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "File path to check (e.g., 'src/main/kotlin/UserService.kt')")
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    /**
     * ## `isError` semantics (Task 5.6 / F6 invariant)
     *
     * `ToolResult.isError` distinguishes **tool-execution failure** from
     * **diagnostics-as-payload**. T5 carries a richer success taxonomy than
     * T2/T3/T4 because of the edit-range filter — enumerating all branches
     * here prevents a future refactor from silently flipping the invariant.
     *
     * ### The seven `isError = true` sites (exhaustive)
     *
     * 1. **Missing `path` parameter** — rejected before any IDE call.
     * 2. **Malformed path** (via `PathValidator.resolveAndValidate`) —
     *    traversal attempts (`../../etc/passwd`), missing `project.basePath`.
     * 3. **`DumbService` blocked during indexing** (Task 5.2 / F3) — the
     *    language provider may use index-dependent APIs (PsiShortNamesCache,
     *    reference resolution, ClassInheritorsSearch); short-circuit with a
     *    retry hint rather than emit misleading partial results. Placed
     *    BEFORE the outer `try`/`catch` so indexing signals are not swallowed.
     * 4. **Valid path, missing file** (`findFileByIoFile` returns null) —
     *    path resolved cleanly but no VFS entry at that location. Emits
     *    `"File not found: $path"`.
     * 5. **PSI parse failure** (`PsiManager.findFile` returns null) — VFS
     *    entry exists but IntelliJ cannot build a PSI tree for it (e.g.
     *    unsupported file extension). Emits `"Cannot parse: $path"`.
     * 6. **PSI invalidation mid-analysis** — the `ReadAction.nonBlocking`
     *    lambda returns `null` when `!psiFile.isValid`; the outer
     *    `result ?: …` fallback surfaces this as
     *    `"PSI file became invalid during analysis."`
     * 7. **Uncaught exception** escaping the outer `try`/`catch` in
     *    [execute] — any unexpected IDE/provider failure.
     *
     * ### Success payloads (`isError = false`)
     *
     * - `"No errors in {file} near your changes."` — clean file with an edit
     *   range set. Optional `" (N pre-existing issue(s) outside your edit
     *   range were excluded)"` suffix when Wolf/pre-existing issues were
     *   filtered out.
     * - `"No errors in {file} near your changes."` — clean file with no edit
     *   range (whole-file check, nothing found).
     * - `"Code intelligence not available for {language}"` — no provider
     *   registered for this language (e.g. YAML, JSON, Go). Token estimate = 5.
     *   This is a legitimate outcome, not a failure — the agent should move
     *   on rather than retry.
     * - `"N issue(s) in {file}[ (lines X-Y)]: ..."` — per-issue prose
     *   preview with optional scope note, skipped-count note, and
     *   Wolf-flagged note. Full lossless list attached via
     *   `renderDiagnosticBody(prose, entries)`.
     *
     * The problem list IS the payload. A populated list is not a failure
     * signal. This mirrors the contract pinned for `RunInspectionsTool` (T2),
     * `ListQuickFixesTool` (T3), and `ProblemViewTool` (T4). See
     * `agent/CLAUDE.md`. [SemanticDiagnosticsToolTest] pins the error-path
     * invariant at the unit-test boundary; the problems-found branches are
     * covered structurally (the only `isError = true` constructions in this
     * file are at the seven designated error sites above).
     */
    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError

        // Task 5.2 / F3: DumbService guard placed OUTSIDE the outer try so
        // indexing-state signals are surfaced as their own ToolResult rather
        // than being swallowed by the outer catch. Matches the placement
        // pattern in RunInspectionsTool.kt lines 69–71, ListQuickFixesTool.kt
        // lines 77–79, and ProblemViewTool.kt lines 106–111.
        //
        // Why the top-level guard is sufficient for the provider delegate:
        // `ReadAction.nonBlocking { … }.inSmartMode(project).executeSynchronously()`
        // blocks until indexing completes (platform contract), so the lambda
        // body is guaranteed to run in smart mode. Inside the lambda, both
        // JavaKotlinProvider.getDiagnostics and PythonProvider.getDiagnostics
        // do index-dependent work — PsiRecursiveElementWalkingVisitor over
        // the file, PsiReference.resolve() / multiResolve() for unresolved
        // reference detection, and PsiErrorElement collection. These are all
        // safe under smart mode. The top-level `isDumb` check short-circuits
        // callers that arrive during active indexing so they don't queue up
        // waiting behind `inSmartMode` — which could otherwise hang the tool
        // for the full reindex duration. Provider-internal re-checks are not
        // required because `inSmartMode` is the stronger guarantee.
        if (DumbService.isDumb(project)) {
            return ToolResult("IDE is still indexing. Try again shortly.", "Indexing", 5, isError = true)
        }

        // Check if there's a recent edit range for this file (set by EditFileTool)
        val canonicalPath = try { java.io.File(path!!).canonicalPath } catch (_: Exception) { path!! }
        val scopedKey = com.workflow.orchestrator.agent.tools.builtin.EditFileTool.scopedKey(canonicalPath)
        val editRange = com.workflow.orchestrator.agent.tools.builtin.EditFileTool.lastEditLineRanges.remove(scopedKey)

        return try {
            val result = ReadAction.nonBlocking<ToolResult?> {
                val vf = LocalFileSystem.getInstance().findFileByIoFile(java.io.File(path))
                    ?: return@nonBlocking ToolResult("File not found: $path", "Not found", 5, isError = true)
                val psiFile = PsiManager.getInstance(project).findFile(vf)
                    ?: return@nonBlocking ToolResult("Cannot parse: $path", "Parse error", 5, isError = true)
                if (!psiFile.isValid) return@nonBlocking null

                // Resolve the provider for this file's language
                val provider = registry.forFile(psiFile)
                    ?: return@nonBlocking ToolResult(
                        "Code intelligence not available for ${psiFile.language.displayName}",
                        "Unsupported type",
                        5
                    )

                // Compute the line filter: if we have an edit range, only report issues near it
                val filterRange = editRange?.let {
                    val start = maxOf(1, it.first - EDIT_LINE_BUFFER)
                    val end = it.last + EDIT_LINE_BUFFER
                    start..end
                }

                // Delegate diagnostics to the language provider
                val allProblems = provider.getDiagnostics(psiFile, null)

                // WolfTheProblemSolver check (file-level, always included — platform API)
                val wolf = WolfTheProblemSolver.getInstance(project)
                val hasProblemFlag = wolf.isProblemFile(vf)

                // Filter to only issues near the edited lines (if edit range is known).
                // `relevantProblems` is the SCOPED set — it reflects the semantic
                // contract of the edit-range feature ("report only issues near
                // your changes"). Structured DiagnosticEntry output uses the same
                // scoped set, NOT the pre-filter `allProblems`, so Phase 7
                // consumers see exactly what the prose preview describes.
                val relevantProblems = if (filterRange != null) {
                    allProblems.filter { it.line in filterRange }
                } else {
                    allProblems
                }

                val skippedCount = allProblems.size - relevantProblems.size

                if (relevantProblems.isEmpty() && !hasProblemFlag) {
                    val suffix = if (filterRange != null && skippedCount > 0) {
                        " ($skippedCount pre-existing issue(s) outside your edit range were excluded)"
                    } else ""
                    ToolResult("No errors in ${vf.name} near your changes.$suffix", "No errors", 5)
                } else {
                    // Task 5.1 / F1: build the LOSSLESS structured list from
                    // `relevantProblems` (the edit-range-filtered set) BEFORE
                    // any cap is applied below. Phase 7's spiller reads entries
                    // off the ToolResult and must see every problem the tool
                    // legitimately considered — not the capped prose preview.
                    // Using `relevantProblems` (not `allProblems`) preserves
                    // the tool's semantic contract: the edit-range filter
                    // scopes problems, and the structured entries respect that
                    // scope.
                    val entries = relevantProblems.map { diag ->
                        DiagnosticEntry(
                            file = vf.path,                                      // absolute path — DiagnosticEntry.file contract (52b0d867)
                            line = diag.line,                                    // 1-based per DiagnosticInfo; providers compute via getLineNumber(...).plus(1)
                            // TODO(phase7): column is -1 because
                            // LanguageIntelligenceProvider.DiagnosticInfo does
                            // not expose a column field today; adding one
                            // requires a provider-interface change. Tracked
                            // for a Phase 7 or follow-up spike.
                            column = -1,
                            severity = normalizeProviderSeverity(diag.severity), // canonical "ERROR" | "WARNING" | "INFO"
                            toolId = DiagnosticSubsystem.PROVIDER,               // closed vocabulary — constant added in b2e62c27 for T5
                            description = diag.message,
                            // TODO(phase7): hasQuickFix is pinned to false —
                            // providers don't expose quick-fix availability
                            // through the DiagnosticInfo contract. Same
                            // limitation as T4 ProblemViewTool; see
                            // DiagnosticEntry.hasQuickFix kdoc for the
                            // lower-bound semantics Phase 7 consumers rely on.
                            hasQuickFix = false,
                            category = null,
                        )
                    }

                    // TODO(phase7): replace this hard cap with ToolOutputSpiller — Phase 7
                    // will route the full entry list to disk and leave a preview inline.
                    val shown = relevantProblems.take(MAX_ISSUES)
                    val lines = shown.map { diag -> "  Line ${diag.line}: ${diag.message}" }
                    // TODO(phase7): replace "... and N more" preview with disk-spill reference
                    val more = if (relevantProblems.size > MAX_ISSUES) "\n... and ${relevantProblems.size - MAX_ISSUES} more" else ""
                    val scopeNote = if (filterRange != null) " (lines ${filterRange.first}-${filterRange.last})" else ""
                    val skippedNote = if (skippedCount > 0) "\n  ($skippedCount pre-existing issue(s) outside edit range excluded)" else ""
                    val flagNote = if (hasProblemFlag && filterRange != null) "\n  Note: IDE flags this file as problematic (may have issues outside your edit)" else ""
                    val prose = "${relevantProblems.size} issue(s) in ${vf.name}$scopeNote:\n${lines.joinToString("\n")}$more$skippedNote$flagNote"
                    val content = renderDiagnosticBody(prose, entries)
                    // F6: problems-found is a SUCCESSFUL tool result (isError=false).
                    ToolResult(content, "${relevantProblems.size} issues", TokenEstimator.estimate(content), isError = false)
                }
            }.inSmartMode(project).executeSynchronously()
            result ?: ToolResult("PSI file became invalid during analysis.", "Invalid", 5, isError = true)
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", "Error", 5, isError = true)
        }
    }
}
