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
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
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

    override fun documentation(): ToolDocumentation = toolDoc("list_quickfixes") {
        summary {
            technical(
                "Enumerates IDE quick-fix actions (Alt+Enter menu) available at a specific line in a file — " +
                "runs the active inspection profile via buildVisitor/ProblemsHolder under ReadAction.nonBlocking().inSmartMode(), " +
                "collects LocalInspectionTool problems whose 0-based lineNumber matches the target, " +
                "deduplicates by fixName, and returns a structured DiagnosticEntry list (full JSON spilled above 30K, " +
                "prose head-20 inline); isError=false on both empty and non-empty results; never applies any fix."
            )
            plain(
                "Like pressing Alt+Enter on a red or yellow squiggle in IntelliJ — the lightbulb menu pops up " +
                "showing you every possible automatic fix. This tool gives the agent that exact list for any line " +
                "in any file, without touching the code. The agent can then decide which fix to apply and do it " +
                "with edit_file."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without list_quickfixes, the LLM must guess which edit to apply after diagnostics surfaces a problem — " +
            "it improvises a fix by pattern-matching the error message against its training data, which frequently " +
            "produces wrong or incomplete repairs (adding a missing import with an incorrect package, adding a null " +
            "check instead of unwrapping an Optional, generating a method stub with wrong parameter types). " +
            "list_quickfixes shows the exact IDE-vetted options: the LLM sees the fix family names " +
            "(\"Add import for…\", \"Surround with try/catch\", \"Implement abstract methods\") and can pick the " +
            "right one and then replicate its effect precisely via edit_file. The cost of the wrong guess is a " +
            "second diagnostics → list_quickfixes → edit_file cycle; this tool halves average repair iterations."
        )
        llmMistake(
            "Expects list_quickfixes to APPLY the selected fix. It does not — the tool is strictly read-only. " +
            "It enumerates what the IDE could do; the LLM must implement the chosen fix itself via edit_file " +
            "or a refactor tool. Calling list_quickfixes and then waiting for side effects is wrong."
        )
        llmMistake(
            "Calls list_quickfixes in a loop over every line in a file looking for fixes. One call per problem " +
            "line is already redundant if diagnostics was called first — diagnostics already surfaces the lines. " +
            "list_quickfixes is a precision tool: call it once for a specific line where you want to know which " +
            "fix to apply, not as a sweep over the whole file."
        )
        llmMistake(
            "Reads isError=false as 'no quick fixes available'. WRONG — isError=false means 'the tool ran " +
            "successfully'. Both 'N quick fix(es) at line X' and 'No quick fixes available at line X' return " +
            "isError=false. isError=true is reserved for tool-execution failures: missing path/line param, " +
            "line < 1, line beyond end-of-file, DumbService indexing block, file not found, PSI parse failure, " +
            "PSI invalidation, and uncaught exceptions. The LLM must inspect the content to see whether fixes " +
            "were found."
        )
        llmMistake(
            "Calls list_quickfixes on a line that had no problem reported by diagnostics or run_inspections. " +
            "The inspection walk still runs the full profile and returns the result, but on a clean line the " +
            "result is almost always empty — wasted work. list_quickfixes is most valuable on lines that " +
            "diagnostics or run_inspections confirmed have a problem."
        )
        llmMistake(
            "Assumes the fix name in the result describes an exact refactoring the LLM should mimic verbatim. " +
            "Fix family names (e.g. 'Add import for com.example.Foo') are a hint, not a complete recipe. " +
            "The LLM still needs to understand the fix intent and translate it into an edit_file SEARCH/REPLACE block."
        )
        params {
            required("path", "string") {
                llmSeesIt("File path")
                humanReadable(
                    "Where the file lives — relative to the project root or absolute. " +
                    "Same path semantics as read_file and diagnostics."
                )
                whenPresent(
                    "Path is canonicalised and validated against project boundaries via PathValidator; " +
                    "the VFS entry and PSI file are resolved; the full inspection profile is walked inside inSmartMode."
                )
                constraint("must point inside the project root or another allow-listed root — traversal attacks rejected")
                constraint("must point to a file that exists in the VFS")
                example("src/main/kotlin/com/example/UserService.kt")
                example("agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/ListQuickFixesTool.kt")
            }
            required("line", "integer") {
                llmSeesIt("Line number (1-based)")
                humanReadable(
                    "Which line to check — the same 1-based numbering you see in the IDE gutter. " +
                    "The tool converts this to the 0-based index IntelliJ uses internally."
                )
                whenPresent(
                    "Only problems whose 0-based ProblemDescriptor.lineNumber equals (line - 1) are collected; " +
                    "all other problems from the inspection walk are discarded."
                )
                constraint("must be >= 1")
                constraint("must be <= the file's line count; lines beyond EOF return isError=true")
                example("42")
                example("118")
            }
        }
        verdict {
            keep(
                "Closes the diagnostics→fix loop without requiring the LLM to guess repair intent from first " +
                "principles. The diagnostic tools (diagnostics, run_inspections, problem_view) surface what is " +
                "wrong; list_quickfixes surfaces what IntelliJ knows how to do about it — a qualitatively " +
                "different signal. Even when the LLM ultimately writes its own edit, seeing the canonical fix " +
                "names dramatically narrows the search space and reduces incorrect repairs. Deferred tool, " +
                "registered when a language provider or inspection engine is available (Java/Kotlin scope).",
                VerdictSeverity.NORMAL,
            )
        }
        related(
            "diagnostics",
            Relationship.COMPOSE_WITH,
            "Diagnose first: diagnostics surfaces which lines have errors and at what severity. " +
            "Then call list_quickfixes on the specific problem line to see what the IDE can fix. " +
            "These two tools are the canonical two-step: DIAGNOSE → LIST_FIXES → (pick one) → edit_file."
        )
        related(
            "run_inspections",
            Relationship.COMPOSE_WITH,
            "run_inspections surfaces inspection-profile problems (style, null safety, unused code) — " +
            "list_quickfixes returns the available fixes for a specific problem line. Use run_inspections " +
            "for a broader sweep, then list_quickfixes to look up fix options for any flagged line."
        )
        related(
            "edit_file",
            Relationship.COMPLEMENT,
            "list_quickfixes SHOWS what the IDE can fix; edit_file APPLIES the fix. The typical pattern: " +
            "list_quickfixes → choose fix by family name → implement the equivalent change via edit_file " +
            "SEARCH/REPLACE. list_quickfixes never writes; edit_file never reads fix availability."
        )
        related(
            "problem_view",
            Relationship.SEE_ALSO,
            "problem_view reads the IDE Problems tool window (already-computed state); list_quickfixes " +
            "re-runs the inspection walk fresh for a specific line. Use problem_view for a quick snapshot " +
            "of what the IDE already knows; use list_quickfixes when you need to know fix options for a " +
            "specific line right now."
        )
        downside(
            "Re-runs the full inspection profile on every call — the same redundant work that the " +
            "TODO(phase7) F6 note inside the source identifies. `IntentionManager.getAvailableActions` " +
            "would be faster (reads from already-computed HighlightInfo) but requires an open Editor " +
            "instance not available for unopened files. Performance cost is acceptable for a deferred tool " +
            "called on a specific line, but do not call it in a loop over all lines."
        )
        downside(
            "Depends on the active inspection profile — tools the user has disabled in their profile " +
            "will not surface fixes. If a well-known fix is absent, the user may have silenced that " +
            "inspection. list_quickfixes respects `profile.isToolEnabled()`, not `isEnabledByDefault`."
        )
        downside(
            "Column numbers are -1 in every DiagnosticEntry — ProblemDescriptor does not expose a " +
            "column through the API used here. Phase 7 consumers that group by (file, line, column) " +
            "must treat all list_quickfixes entries as line-granularity only."
        )
        downside(
            "Deduplication is by fixName only — two inspections that offer a fix with the same family " +
            "name but different implementations will collapse to one entry. The inspection short name " +
            "(toolId) in the kept entry is whichever appears first in the profile walk order, which is " +
            "not guaranteed to be stable across IntelliJ versions."
        )
        downside(
            "Blocked during DumbService indexing (returns isError=true with a retry hint) — same " +
            "constraint as diagnostics, run_inspections, and problem_view."
        )
        downside(
            "hasQuickFix is hardcoded to true in every DiagnosticEntry — by construction, every entry " +
            "in the list_quickfixes result IS a quick fix. This is the inverse of the diagnostics / " +
            "problem_view tools where hasQuickFix is pinned to false (unknown). Phase 7 consumers " +
            "filtering on hasQuickFix==true will correctly include all list_quickfixes entries."
        )
        observation(
            "isError=false on 'no quick fixes found' is the same unusual contract as diagnostics, " +
            "run_inspections, and problem_view — the result list IS the payload, not an error signal. " +
            "Documented in the tool KDoc and pinned by ListQuickFixesToolTest."
        )
        observation(
            "The question 'should list_quickfixes APPLY a selected fix instead of just listing?' was " +
            "explicitly considered during authoring. Answer: NO. Applying a fix requires invoking " +
            "`QuickFix.applyFix()` in a WriteCommandAction on the EDT — a destructive write-path " +
            "operation with no preview, no diff, and no undo integration at the agent boundary. " +
            "The correct pattern is: list_quickfixes (read) → LLM selects fix by name → edit_file " +
            "(controlled, diffed, reversible write). Collapsing list+apply into one tool would make " +
            "the tool a FILE_WRITE tool requiring approval, would eliminate the LLM's ability to " +
            "inspect the proposed change before applying it, and would break the read/write boundary " +
            "that allows read-only tools to run in parallel. The separation is intentional and correct."
        )
        mergeOpportunity(
            "list_quickfixes and run_inspections share the identical inspection-walk setup " +
            "(profile.getInspectionTools → LocalInspectionToolWrapper → HighlightDisplayKey.find → " +
            "profile.isToolEnabled → buildVisitor → PsiRecursiveElementWalkingVisitor). " +
            "A future refactor could extract a shared InspectionWalker utility and let both tools " +
            "call it with different problem-extraction callbacks (all-problems vs fixes-at-line). " +
            "This would remove ~60 duplicate lines and let a single performance fix apply to both."
        )
    }

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
