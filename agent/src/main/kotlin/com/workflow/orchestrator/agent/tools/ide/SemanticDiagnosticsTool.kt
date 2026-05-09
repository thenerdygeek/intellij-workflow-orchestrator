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
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
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

    override fun documentation(): ToolDocumentation = toolDoc("diagnostics") {
        summary {
            technical("Single-file semantic diagnostics via the registered LanguageIntelligenceProvider — runs PSI traversal under ReadAction.nonBlocking().inSmartMode() to surface compile errors, unresolved references, type mismatches, and PsiErrorElements; optional start_line/end_line scopes the result to an edit range; full structured DiagnosticEntry list spills to disk above 30K, prose preview (head 20) stays inline.")
            plain("Like the squiggly red and yellow underlines in your IDE — but as a structured list the agent can read. Tells the LLM exactly which lines have errors, what's broken, and at what severity, without it having to spawn a Maven or Gradle build.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without diagnostics, the LLM iterates `run_command ./gradlew build` (or mvn compile / pytest --collect-only) just to learn whether its last edit compiles — 30-90s per cycle, 100K+ chars of build noise to filter, and zero line-precise mapping. After three failed-build cycles the context is half full of stack traces. diagnostics gives the same signal in <1s, scoped to the file, with 1-based line numbers ready for edit_file. This tool is the dominant reason agent edit-then-verify loops finish in seconds rather than minutes."
        )
        llmMistake("Reads `isError=false` as 'no problems found'. WRONG — `isError=false` means 'the tool ran successfully'; the problem list IS the payload. Problems-found and clean-file results both return `isError=false`. Only path validation, indexing, missing file, parse failure, PSI invalidation, and uncaught exceptions return `isError=true`. The LLM must inspect the content (`No errors in X.` vs `N issue(s) in X: ...`) to decide success-vs-fix-needed.")
        llmMistake("Calls diagnostics on a file the IDE hasn't finished indexing yet — receives `IDE is still indexing. Try again shortly.` (`isError=true`) and immediately re-fires instead of waiting or doing other work first. The DumbService guard is intentional; retry-with-backoff or pivoting to non-PSI work is the right move.")
        llmMistake("Calls diagnostics immediately after edit_file without realising the SyntaxValidator that edit_file ran already covered the same file at write time — duplicate work. edit_file's post-write validation is local and fast; diagnostics is the broader sweep. Use diagnostics for the *next* iteration after the structural edit has settled, not as a redundant check on the line you just wrote.")
        llmMistake("Provides only `start_line` (or only `end_line`) expecting partial scoping — both are required to filter, and a missing pair causes the tool to fall through to whole-file analysis silently. Easy to miss because there is no explicit error.")
        llmMistake("Calls diagnostics on JSON / YAML / Go / TypeScript and gets `Code intelligence not available for {language}` (`isError=false`, token estimate 5). The LLM occasionally retries with the same path expecting a different result. Move on — there is no provider; use search_code or run_inspections instead.")
        params {
            required("path", "string") {
                llmSeesIt("File path to check (e.g., 'src/main/kotlin/UserService.kt')")
                humanReadable("Where the file lives — relative to the project root or absolute. Same path semantics as read_file.")
                whenPresent("Path is canonicalised, validated against project boundaries via PathValidator, the VFS entry is resolved, PSI is built, and the language provider runs its diagnostics walk inside `inSmartMode`.")
                constraint("must point inside the project root or another allow-listed root — traversal (`../etc/passwd`) is rejected before any IDE call")
                constraint("must point to a file, not a directory")
                constraint("file must already exist in the VFS — pass-through `findFileByIoFile` returns null for unsaved/scratch paths")
                example("src/main/kotlin/com/example/UserService.kt")
                example("agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/ide/SemanticDiagnosticsToolTest.kt")
            }
            optional("start_line", "integer") {
                llmSeesIt("First line of the range to check (1-based, inclusive). Requires end_line.")
                humanReadable("Lower bound of the line range to scope diagnostics to — useful right after an edit when you only care about issues near your change.")
                whenPresent("Combined with end_line to filter the diagnostic list. The full file is still parsed (PSI doesn't do partial parses); only the result list is filtered and a `(N issue(s) outside the specified range were excluded)` suffix is added.")
                whenAbsent("Whole-file diagnostics are returned; no scope filter is applied.")
                constraint("must be paired with end_line — providing only one is silently ignored and falls back to whole-file analysis")
                constraint("1-based")
                example("40")
            }
            optional("end_line", "integer") {
                llmSeesIt("Last line of the range to check (1-based, inclusive). Requires start_line.")
                humanReadable("Upper bound of the line range — pair with start_line to scope to a specific edit window.")
                whenPresent("Combined with start_line to filter the diagnostic list to lines within the inclusive range.")
                whenAbsent("Whole-file diagnostics are returned.")
                constraint("must be paired with start_line — providing only one is silently ignored")
                constraint("1-based, inclusive")
                example("80")
            }
        }
        verdict {
            keep(
                "Foundational for any edit-then-verify loop. Replaces the need to shell out to `./gradlew build` / `mvn compile` / `pytest --collect-only` after every edit — same signal, ~100x faster, line-precise, no build-system noise. Without it, agent iteration cycles balloon from seconds to minutes and contexts fill with stack traces. Core tool, registered always when a language provider is registered.",
                VerdictSeverity.STRONG,
            )
        }
        related("run_inspections", Relationship.ALTERNATIVE, "Project-wide or scope-wide inspection sweep — heavier, slower, but covers IntelliJ inspection profiles diagnostics doesn't see (style, performance, deprecation). Use diagnostics for fast per-file checks, run_inspections for broader audits.")
        related("list_quickfixes", Relationship.COMPLEMENT, "Once diagnostics surfaces a problem, list_quickfixes returns the IDE-suggested fixes for it — the natural next step after the LLM reads the diagnostic line.")
        related("problem_view", Relationship.SEE_ALSO, "Reads the IDE's existing Problems tool window state instead of running a fresh analysis — overlapping payload but reflects what the IDE has already computed (Wolf flag, persisted issues) rather than re-walking PSI.")
        related("edit_file", Relationship.COMPOSE_WITH, "edit_file runs SyntaxValidator on every write — diagnostics is the broader follow-up sweep. Typical loop: edit_file → diagnostics (verify nothing else broke) → list_quickfixes if issues found.")
        related("find_references", Relationship.COMPOSE_WITH, "When diagnostics reports an unresolved reference, find_references / find_definition help the LLM locate the missing symbol's actual definition.")
        downside("Depends on indexing being complete — returns `IDE is still indexing` (isError=true) during DumbService periods (project import, plugin reload, VCS branch switch). The agent has to back off and retry, which adds latency at session start.")
        downside("Not all languages have a provider — JSON, YAML, Go, TypeScript, etc. return `Code intelligence not available for {language}`. Coverage is currently Java/Kotlin (JavaKotlinProvider) and Python (PythonProvider via reflection on PythonCore).")
        downside("Column numbers are pinned to -1 in the structured DiagnosticEntry output — providers don't expose column through the DiagnosticInfo contract today (Phase 7 follow-up).")
        downside("`hasQuickFix` is pinned to false in DiagnosticEntry — providers don't propagate quick-fix availability (same limitation as ProblemViewTool).")
        downside("Severity classification reflects the inspection profile / language provider's own thresholds, not a stable cross-language taxonomy. An ERROR in Kotlin and an ERROR in Python may have very different blast radii.")
        downside("Mid-refactor false positives: when the user (or the agent itself) is mid-edit and references are temporarily unresolved, diagnostics will report them — matches IDE behaviour but means the LLM should not panic on transient errors right after a large edit.")
        observation("isError=false on problems-found is unusual enough that it warrants a llmMistake entry above and a dedicated KDoc block on `execute`. Same contract as RunInspectionsTool, ListQuickFixesTool, ProblemViewTool — the problem list IS the payload.")
    }

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
