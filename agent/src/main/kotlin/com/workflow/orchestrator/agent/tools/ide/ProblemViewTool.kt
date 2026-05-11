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
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
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

    override fun documentation(): ToolDocumentation = toolDoc("problem_view") {
        summary {
            technical(
                "Reads the IDE's in-memory Problems panel state — aggregates Wolf-flagged files and " +
                "DocumentMarkupModel HighlightInfo entries (HighlightSeverity.ERROR / WARNING) from all " +
                "currently open editors; optional `file` param scopes to a single open file, optional " +
                "`severity` param filters ERROR or WARNING; full structured DiagnosticEntry list spills " +
                "to disk above 30K; DumbService guard prevents misleading zero-problem results during indexing."
            )
            plain(
                "Like opening the Problems panel in IntelliJ and reading what's already highlighted in red " +
                "and yellow — zero extra analysis, just the issues the IDE has already found while the files " +
                "were open. Instant, no indexing wait, but only covers files you have actually opened."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without problem_view the LLM would iterate diagnostics per file to get a project-wide error " +
            "snapshot — calling it N times across N files the agent has been editing. Each call dispatches " +
            "ReadAction.nonBlocking + inSmartMode (heavier PSI walk), produces its own token payload, and " +
            "risks missing Wolf-flagged files the LLM does not think to probe. problem_view sweeps ALL open " +
            "files in one call and surfaces both the structured HighlightInfo list and the Wolf flag in a " +
            "single result — particularly useful when the agent needs a quick triage of its whole edit " +
            "session after a batch of writes rather than checking each file individually."
        )
        llmMistake(
            "Expects results for files that are not open in the editor. problem_view reads " +
            "DocumentMarkupModel, which is only populated when the file has been opened in an editor tab. " +
            "A file that exists on disk but has never been opened returns 'No problems' (or a Wolf-flag " +
            "placeholder at best). For unopened files, use diagnostics — it walks PSI directly and does " +
            "not require the file to be in the editor."
        )
        llmMistake(
            "Treats the result as a live, fresh analysis equivalent to diagnostics or run_inspections. " +
            "It is not — it reflects what the IDE daemon has highlighted so far in the currently open " +
            "documents. If the editor has just opened the file and the background daemon hasn't finished " +
            "its pass yet, problem_view may return zero problems even though errors exist. Especially " +
            "misleading at project open time before the daemon has run its initial pass."
        )
        llmMistake(
            "Confuses problem_view severity categories with the full IntelliJ inspection severity vocabulary. " +
            "The tool only surfaces ERROR and WARNING from HighlightSeverity; INFO, WEAK_WARNING, and " +
            "INFORMATION entries are silently dropped (continue skip). The LLM should not assume silence " +
            "means no lower-severity findings exist."
        )
        llmMistake(
            "Reads isError=false as 'no problems found'. WRONG — isError=false means the tool ran " +
            "successfully; the problem list IS the payload. 'No problems in X.' is a clean-file success " +
            "result, while 'N problems in X' is also a success result (isError=false). Only invalid " +
            "severity enum, path validation failure, missing file, DumbService block, and uncaught " +
            "exceptions return isError=true."
        )
        llmMistake(
            "Passes a file path for a file that IS open in the editor but gets back 'Flagged but no " +
            "details for X (file flagged as problematic by IDE but no HighlightInfo — file may not be " +
            "open in editor).' This happens when Wolf has marked the file but DocumentMarkupModel has no " +
            "HighlightInfo yet — typically a race between the Wolf update and the daemon highlight pass. " +
            "The LLM should not retry immediately; a brief wait or falling back to diagnostics is correct."
        )
        params {
            optional("file", "string") {
                llmSeesIt("Specific file to check (e.g., 'src/main/kotlin/MyService.kt'). Lists all problem files if omitted.")
                humanReadable(
                    "Which file to inspect. Scopes the result to a single file's problems. " +
                    "Omit to get a sweep of all currently open editor tabs."
                )
                whenPresent(
                    "Path is resolved via PathValidator, VFS entry is looked up, and problems are read " +
                    "from that file's DocumentMarkupModel. Returns 'No problems', 'Flagged but no details', " +
                    "or a structured N-problem list for that file only."
                )
                whenAbsent(
                    "All open editor tabs are swept. Returns 'No open files', 'No problems found in N " +
                    "open file(s)', or an aggregated per-file problem list."
                )
                constraint("must point inside the project root — path traversal is rejected via PathValidator")
                constraint("must refer to a file currently tracked in the VFS; if the file does not exist, returns isError=true 'File not found'")
                constraint("file need not be the active tab, but it must have been opened at some point during this IDE session for HighlightInfo to be available")
                example("src/main/kotlin/com/example/OrderService.kt")
                example("automation/src/main/kotlin/com/workflow/orchestrator/automation/service/QueueService.kt")
            }
            optional("severity", "string") {
                llmSeesIt("Filter by severity: 'error', 'warning', or 'all' (default: 'all')")
                humanReadable(
                    "Which severity levels to include in the result. 'error' returns only ERROR-level " +
                    "entries; 'warning' returns only WARNING-level; 'all' (default) returns both."
                )
                whenPresent("Only HighlightInfo entries matching the requested severity are included in the output.")
                whenAbsent("Defaults to 'all' — both ERROR and WARNING entries are returned.")
                constraint("must be one of: 'error', 'warning', 'all' — any other value returns isError=true before any IDE call")
                enumValue("error", "warning", "all")
                example("error")
                example("all")
            }
        }
        verdict {
            keep(
                "Provides a uniquely cheap project-wide snapshot of already-computed IDE highlighting: " +
                "zero PSI re-walk, zero build cost, instant result, covers ALL open files in one call. " +
                "Distinct from diagnostics (single-file, fresh PSI walk, works for unopened files) and " +
                "run_inspections (explicit on-demand scope sweep). Best used after a batch edit session " +
                "when the agent needs a quick triage without spawning a build or N separate diagnostics calls. " +
                "The Wolf-flag integration surfaces problems that might otherwise require explicit per-file probing.",
                VerdictSeverity.NORMAL,
            )
        }
        related(
            "diagnostics",
            Relationship.ALTERNATIVE,
            "Use diagnostics instead when the target file has not been opened in the editor, when you need " +
            "a guaranteed fresh analysis rather than cached IDE state, or when you need per-file line-precise " +
            "PSI errors for a specific file after an edit. diagnostics walks PSI directly and works for any " +
            "file in the project regardless of whether it is currently open."
        )
        related(
            "run_inspections",
            Relationship.ALTERNATIVE,
            "Use run_inspections for an explicit on-demand inspection sweep across a scope (file, module, " +
            "project) that respects the active inspection profile — it surfaces style, performance, and " +
            "deprecation issues beyond compiler-level errors that problem_view and diagnostics both miss."
        )
        related(
            "list_quickfixes",
            Relationship.COMPOSE_WITH,
            "Once problem_view surfaces a line-level ERROR or WARNING, list_quickfixes returns IDE-suggested " +
            "quick fixes for that location — the natural next step before the LLM decides whether to fix " +
            "manually or apply an IDE action."
        )
        downside(
            "Only covers files that have been opened in the editor during this IDE session. Any file that " +
            "exists on disk but has never been opened returns no HighlightInfo — the LLM gets 'No problems' " +
            "even if the file has compile errors. This is a fundamental constraint of the DocumentMarkupModel " +
            "approach and cannot be worked around without switching to diagnostics."
        )
        downside(
            "Results reflect IDE daemon state at the time of the call, not necessarily current file " +
            "content. After a large batch of edits the daemon may not have completed its re-highlight pass " +
            "yet, so problem_view can return a stale snapshot. DumbService blocks during active indexing " +
            "and returns isError=true, but mid-session partial staleness (daemon not yet done) is not " +
            "detected — there is no reliable way to query 'is the daemon finished'."
        )
        downside(
            "Severity vocabulary is narrowed: only HighlightSeverity.ERROR and HighlightSeverity.WARNING " +
            "are forwarded; INFO / WEAK_WARNING / INFORMATION are silently dropped. The structured " +
            "DiagnosticEntry severity field uses only 'ERROR' or 'WARNING' — a two-value subset of the " +
            "three-value canonical vocabulary (which includes 'INFO')."
        )
        downside(
            "hasQuickFix is pinned to false in all DiagnosticEntry output — HighlightInfo.hasHint() and " +
            "HighlightInfo.quickFixActionRanges require impl-level access or an Editor instance unavailable " +
            "for unopened files. Use list_quickfixes to discover available fixes after the diagnostic is found."
        )
        downside(
            "Wolf-flagged files with no HighlightInfo produce a synthetic placeholder entry " +
            "(severity=WARNING, line=0, toolId='wolf') rather than a real structured diagnostic — the LLM " +
            "must fall back to diagnostics if it needs line-precise details for these files."
        )
        observation(
            "problem_view vs diagnostics(scope=project) vs run_inspections — the three look similar but " +
            "serve different needs. problem_view: instant, pre-computed, open-files-only, zero PSI walk. " +
            "diagnostics: fresh per-file PSI walk, any file, requires path, one file per call. " +
            "run_inspections: explicit full-scope sweep, respects inspection profile, slowest but most complete. " +
            "They are NOT redundant: problem_view is the cheapest project-wide triage; diagnostics is the " +
            "precise per-file verifier; run_inspections is the thoroughness sweep."
        )
    }

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

        val rawResult = ReadAction.nonBlocking<ToolResult> {
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

        // Phase 7: spill the full JSON body when it exceeds the 30K threshold.
        // The prose preview is always readable inline; the full structured JSON
        // list is available on disk for read_file / search_code.
        return applySpillOrFormat(rawResult, project)
    }

    private suspend fun getProblemsForAllOpenFiles(severity: String, project: Project): ToolResult {
        val rawResult = ReadAction.nonBlocking<ToolResult> {
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

        // Phase 7: spill the full JSON body when it exceeds the 30K threshold.
        return applySpillOrFormat(rawResult, project)
    }

    /**
     * Phase 7 structured-preview strategy: spill the full JSON body (prose +
     * DIAGNOSTIC_STRUCTURED_DATA_MARKER + JSON) when it exceeds the 30K threshold.
     * The prose preview is always readable inline; the full structured JSON list is
     * available on disk for read_file / search_code drill-in.
     *
     * Error and zero-result ToolResults (no DIAGNOSTIC_STRUCTURED_DATA_MARKER) are
     * returned unchanged — spilling only applies when a structured entry list is present.
     */
    private suspend fun applySpillOrFormat(result: ToolResult, project: Project): ToolResult {
        if (result.isError) return result
        // Only spill results that contain the structured data marker (i.e. have a non-empty entry list).
        if (!result.content.contains(DIAGNOSTIC_STRUCTURED_DATA_MARKER)) return result

        val spilled = spillOrFormat(result.content, project)
        if (spilled.spilledToFile == null) return result

        val (prose, entries) = parseDiagnosticBody(result.content)
        val spillContent = buildString {
            append(prose)
            append("\n\n[Full structured list (${entries.size} entries) saved to: ${spilled.spilledToFile}]")
            append("\n[Read with read_file or search_code for inspection matching, filtering by severity/file/inspection]")
        }
        return ToolResult(
            content = spillContent,
            summary = result.summary,
            tokenEstimate = TokenEstimator.estimate(spillContent),
            spillPath = spilled.spilledToFile,
        )
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
     * PREVIEW_ENTRIES head-preview cap (Phase 7 structured-preview strategy).
     * Returns (prose, prose-token-estimate).
     *
     * F1 (Task 5.1): the cap applies ONLY to the prose preview. Callers
     * build `DiagnosticEntry` from the LOSSLESS problem list BEFORE
     * invoking this helper so Phase 7's spiller sees every problem.
     *
     * Phase 7: "... and N more" is no longer appended — when the full JSON body
     * exceeds ToolOutputConfig.SPILL_THRESHOLD_CHARS (30K), the outer coroutine
     * appends a spill-path footer instead. When under threshold, all entries are
     * present in the inline body via `renderDiagnosticBody`.
     */
    private fun formatFileProblemsWithCap(
        relativePath: String,
        problems: List<ProblemEntry>
    ): Pair<String, Int> {
        val sb = StringBuilder()
        sb.appendLine("$relativePath — ${problems.size} problem(s):")

        // Phase 7 structured-preview strategy: show head-PREVIEW_ENTRIES entries inline.
        // The full entry list is always present in the renderDiagnosticBody JSON suffix
        // and will be routed to disk by spillOrFormat when it exceeds the 30K threshold.
        val shown = problems.take(PREVIEW_ENTRIES)
        for (p in shown) {
            if (p.line > 0) {
                sb.appendLine("  ${p.severity} line ${p.line}: ${p.description}")
            } else {
                sb.appendLine("  ${p.severity}: ${p.description}")
            }
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
        /**
         * Head-preview entry count for the inline prose preview (Phase 7 structured-preview strategy).
         * 20 entries fit comfortably in the LLM's context and give enough signal to act without
         * reading the full spilled JSON. The full entry list is always in the spilled file when
         * the JSON body exceeds ToolOutputConfig.SPILL_THRESHOLD_CHARS (30K).
         */
        private const val PREVIEW_ENTRIES = 20
    }
}
