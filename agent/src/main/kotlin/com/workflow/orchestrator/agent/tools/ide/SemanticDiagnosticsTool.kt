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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class SemanticDiagnosticsTool(
    private val registry: LanguageProviderRegistry
) : AgentTool {
    companion object {
        /**
         * Head-preview entry count for the inline prose preview (Phase 7 structured-preview strategy).
         * 20 entries fit comfortably in the LLM's context and give enough signal to act without
         * reading the full spilled JSON. The full entry list is always in the spilled file when
         * the JSON body exceeds ToolOutputConfig.SPILL_THRESHOLD_CHARS (30K).
         */
        private const val PREVIEW_ENTRIES = 20
    }

    override val name = "diagnostics"
    override val description = "Check a file for compilation errors using the IDE's semantic analysis engine — syntax errors, unresolved references, type mismatches, missing imports. Faster and more precise than running mvn compile or gradle build. Use this instead of shell build commands to verify code correctness. Checks the whole file by default; pass start_line and end_line to scope diagnostics to a specific line range."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "File path to check (e.g., 'src/main/kotlin/UserService.kt')"),
            "start_line" to ParameterProperty(type = "integer", description = "First line of the range to check (1-based, inclusive). Requires end_line."),
            "end_line" to ParameterProperty(type = "integer", description = "Last line of the range to check (1-based, inclusive). Requires start_line."),
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
     * - `"No errors in {file}."` — clean whole-file check, nothing found.
     * - `"No errors in {file}. (N issue(s) outside the specified range were excluded)"` —
     *   clean within the requested line range; issues outside the range were filtered.
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

        val startLine = params["start_line"]?.jsonPrimitive?.intOrNull
        val endLine = params["end_line"]?.jsonPrimitive?.intOrNull

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

                val filterRange = if (startLine != null && endLine != null) startLine..endLine else null

                // Delegate diagnostics to the language provider
                val allProblems = provider.getDiagnostics(psiFile, null)

                // WolfTheProblemSolver check (file-level, always included — platform API)
                val wolf = WolfTheProblemSolver.getInstance(project)
                val hasProblemFlag = wolf.isProblemFile(vf)

                // Filter to the caller-specified line range if provided; otherwise whole file.
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
                        " ($skippedCount issue(s) outside the specified range were excluded)"
                    } else ""
                    ToolResult("No errors in ${vf.name}.$suffix", "No errors", 5)
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

                    // Phase 7 structured-preview strategy: prose preview uses
                    // head-PREVIEW_ENTRIES entries (20) — readable standalone.
                    val previewEntries = relevantProblems.take(PREVIEW_ENTRIES)
                    val lines = previewEntries.map { diag -> "  Line ${diag.line}: ${diag.message}" }
                    val scopeNote = if (filterRange != null) " (lines ${filterRange.first}-${filterRange.last})" else ""
                    val skippedNote = if (skippedCount > 0) "\n  ($skippedCount issue(s) outside the specified range excluded)" else ""
                    val flagNote = if (hasProblemFlag && filterRange != null) "\n  Note: IDE flags this file as problematic (may have issues outside the specified range)" else ""
                    val prose = "${relevantProblems.size} issue(s) in ${vf.name}$scopeNote:\n${lines.joinToString("\n")}$skippedNote$flagNote"
                    val content = renderDiagnosticBody(prose, entries)
                    // F6: problems-found is a SUCCESSFUL tool result (isError=false).
                    // Phase 7: outer coroutine will call spillOrFormat on the full body.
                    ToolResult(content, "${relevantProblems.size} issues", TokenEstimator.estimate(content), isError = false)
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
                    isError = false,
                )
            } else {
                result
            }
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", "Error", 5, isError = true)
        }
    }
}
