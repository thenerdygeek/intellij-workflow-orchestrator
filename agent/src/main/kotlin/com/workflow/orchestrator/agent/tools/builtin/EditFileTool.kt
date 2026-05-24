package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.core.util.ProjectIdentifier
import com.workflow.orchestrator.core.vfs.PostMutationRefresh
import java.io.File
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.util.DiffUtil
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

/**
 * Edit file via exact string replacement (old_string -> new_string).
 * Uses Claude Code's Edit pattern (exact match replacement), not Cline's
 * SEARCH/REPLACE diff block format. The old_string must match exactly once
 * in the file unless replace_all is true.
 */
class EditFileTool : AgentTool {

    /**
     * Result of [preview] — pre-validation outcome consumed by the approval gate.
     *
     * The approval gate uses this to decide whether to even show the user a card:
     *  - [ValidationFailed] → skip the card; let [execute] run, fail, and surface the precise
     *    error to the LLM (defense-in-depth: execute re-validates everything).
     *  - [Ready] → show a card whose diff is anchored on real file contents, so the diff2html
     *    hunk header carries the actual match offset instead of `@@ -1,N +1,M @@`.
     */
    sealed class EditPreview {
        /** Validation will fail in [execute]; don't show the approval card. */
        object ValidationFailed : EditPreview()

        /**
         * Validation passed; safe to show this real file-anchored diff.
         *
         * @param realDiff unified diff built from full file contents (carries real line offsets in the @@ header).
         * @param matchStartLine 1-based line number of the first occurrence of `old_string`.
         */
        data class Ready(val realDiff: String, val matchStartLine: Int) : EditPreview()
    }

    companion object {
        /**
         * Pre-validate `edit_file` params and compute the real file-anchored diff for the
         * approval card. Returns [EditPreview.ValidationFailed] for any error (missing param,
         * path traversal, file not found, old_string not matched, ambiguous match without
         * `replace_all`); returns [EditPreview.Ready] on success.
         *
         * Defense-in-depth: [execute] re-validates everything. [preview] is purely a UX
         * concern — it determines whether the approval card is shown and what diff/line
         * numbers it displays. This function MUST NOT mutate the file under any circumstance.
         *
         * Wrapped in a try/catch — any unexpected exception → [EditPreview.ValidationFailed]
         * so the approval gate falls back to "let execute() surface the real error".
         */
        suspend fun preview(params: JsonObject, project: Project): EditPreview {
            return try {
                val rawPath = params["path"]?.jsonPrimitive?.content
                    ?: return EditPreview.ValidationFailed
                val oldString = params["old_string"]?.jsonPrimitive?.content
                    ?: return EditPreview.ValidationFailed
                val newString = params["new_string"]?.jsonPrimitive?.content
                    ?: return EditPreview.ValidationFailed
                val replaceAll = params["replace_all"]?.jsonPrimitive?.boolean ?: false

                val extraRoots = project.basePath?.let {
                    listOf(
                        File(ProjectIdentifier.agentDir(it), "memory").absolutePath,
                        ProjectIdentifier.researchDir(it).absolutePath,
                    )
                } ?: emptyList()
                val (path, pathError) = PathValidator.resolveAndValidateForWrite(rawPath, project.basePath, extraRoots)
                if (pathError != null) return EditPreview.ValidationFailed
                val resolvedPath = path ?: return EditPreview.ValidationFailed

                val vFile = findVirtualFileForPreview(resolvedPath)
                val file = java.io.File(resolvedPath)
                if (vFile == null && (!file.exists() || !file.isFile)) {
                    return EditPreview.ValidationFailed
                }

                val content = readFileContentForPreview(vFile, file)
                val occurrences = countOccurrencesShared(content, oldString)
                if (occurrences == 0) return EditPreview.ValidationFailed
                if (occurrences > 1 && !replaceAll) return EditPreview.ValidationFailed

                val newContent = if (replaceAll) content.replace(oldString, newString)
                else content.replaceFirst(oldString, newString)

                val matchOffset = content.indexOf(oldString)
                // matchOffset must be >= 0 because occurrences > 0; guard anyway.
                if (matchOffset < 0) return EditPreview.ValidationFailed
                val matchStartLine = content.substring(0, matchOffset).count { it == '\n' } + 1

                val realDiff = DiffUtil.unifiedDiff(content, newContent, rawPath)
                EditPreview.Ready(realDiff = realDiff, matchStartLine = matchStartLine)
            } catch (_: Exception) {
                EditPreview.ValidationFailed
            }
        }

        /**
         * Read-only VFS lookup for [preview]. Mirrors the instance [findVirtualFile] —
         * duplicated here because [preview] is a companion-level entry point and cannot
         * call instance methods.
         */
        private fun findVirtualFileForPreview(resolvedPath: String): VirtualFile? {
            return try {
                if (ApplicationManager.getApplication() == null) return null
                LocalFileSystem.getInstance().findFileByPath(resolvedPath)
                    ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(resolvedPath)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Read-only content fetch for [preview]. Mirrors the instance [readFileContent] —
         * Document → VFS → java.io.File fallback chain. Strictly read-only.
         */
        private suspend fun readFileContentForPreview(vFile: VirtualFile?, file: java.io.File): String {
            if (vFile != null) {
                try {
                    return readAction {
                        val document = FileDocumentManager.getInstance().getDocument(vFile)
                        document?.text ?: String(vFile.contentsToByteArray(), vFile.charset)
                    }
                } catch (_: Exception) {
                    // readAction unavailable — fall through to java.io.File
                }
            }
            return file.readText(Charsets.UTF_8)
        }

        private fun countOccurrencesShared(text: String, search: String): Int {
            if (search.isEmpty()) return 0
            var count = 0
            var index = text.indexOf(search)
            while (index >= 0) {
                count++
                index = text.indexOf(search, index + 1)
            }
            return count
        }
    }

    override val name = "edit_file"
    override val description = "Make targeted edits to an existing file using exact string replacement. This tool should be used when you need to make targeted changes to specific parts of a file. The old_string must match EXACTLY — character-for-character including whitespace, indentation, and line endings. Include enough surrounding context (3-5 lines) to ensure old_string matches uniquely in the file. If old_string matches multiple times, the edit will fail — provide a larger context to disambiguate, or set replace_all=true to replace all occurrences. You MUST read the file with read_file before editing to see the exact content. Keep edits concise — include just the changing lines, and a few surrounding lines if needed for uniqueness. Do not include long runs of unchanging lines. Prefer this over create_file for modifying existing files."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "The path of the file to modify (absolute or relative to the project root)."),
            "old_string" to ParameterProperty(type = "string", description = "The exact text to find in the file. Must match character-for-character including whitespace, indentation, and line endings. Include just enough lines to uniquely match the section that needs to change. If the file content came from read_file with line numbers (e.g., '42\tconst x = 1'), do NOT include the line number prefix — match only the raw file text."),
            "new_string" to ParameterProperty(type = "string", description = "The new content to replace old_string with. To delete code, use an empty string."),
            "replace_all" to ParameterProperty(type = "boolean", description = "Replace all occurrences of old_string instead of requiring a unique match. Default: false."),
            "description" to ParameterProperty(type = "string", description = "Brief description of what this edit does and why (shown to user in approval dialog).")
        ),
        required = listOf("path", "old_string", "new_string", "description")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override fun documentation(): ToolDocumentation = toolDoc("edit_file") {
        summary {
            technical("Targeted in-place edit via Claude Code-style exact-string replacement (old_string -> new_string, optional replace_all). Routed through Document API + WriteCommandAction for undo support, with VFS and java.io.File fallbacks. Strict character-for-character match — no whitespace tolerance, no fuzzy matching. Kotlin/Java edits run a non-blocking syntax-validator gate that warns but does not roll back.")
            plain("Like a precise find-and-replace that refuses to guess. You give it the exact text you want to change (copied from what read_file showed) plus its replacement, and it makes the swap. If the old text appears in two places, it stops and asks you to be more specific instead of picking one at random.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.FILE_WRITE)
        counterfactual(
            "Without edit_file the LLM has two bad options. (1) `create_file` overwrites the whole file — destroys unsaved editor changes, dilutes the approval gate (user has to read 500 lines to approve a 3-line change), and any LLM truncation in mid-stream silently nukes the back half of the file. (2) `run_command sed -i ...` bypasses VFS (no undo, no editor sync, IDE diagnostics go stale until refresh), bypasses PathValidator (sed will happily edit ~/.ssh/config), and fails on macOS-vs-GNU sed flag differences ~10% of the time. edit_file is the only call site that gives the user a granular, line-level approval prompt with a unified diff."
        )
        llmMistake("Includes the `42\\t` line-number prefix from `read_file` output in `old_string` — the prefix is metadata, not file content, so the exact-text match returns 0 occurrences. The matcher is character-strict; it can't see through the prefix. Recovery: re-craft `old_string` with raw file bytes only.")
        llmMistake("Provides too little context in `old_string` (e.g. just `return null`) — the same line appears 12 times in the file, so the unique-match check fails with `old_string found 12 times`. Recovery: add 3-5 surrounding lines for uniqueness, or set `replace_all=true` only when the goal is genuinely 'every occurrence'.")
        llmMistake("Guesses at whitespace — e.g. assumes 4-space indent when the file uses tabs, or omits trailing whitespace the file actually has. The matcher is character-for-character, so even a single different space returns 0 occurrences. Recovery: paste raw bytes from a fresh `read_file`, do not retype.")
        llmMistake("Uses `replace_all=true` to rename a symbol across a file — works for the literal string but breaks when the same identifier appears as a substring of another (e.g. renaming `User` also rewrites `UserProfile`). Recovery: use `refactor_rename` (deferred tool) for symbol-level renames; reserve `edit_file replace_all` for genuinely string-level changes.")
        llmMistake("Skips `read_file` and edits based on what it 'remembers' the file looks like from a few iterations ago. Another tool, another sub-agent, or the user may have changed the file since. The match still works if the bytes still match, but when they don't, the LLM has to debug a stale-content failure that wouldn't have happened with a fresh read.")
        llmMistake("Assumes a syntax warning rolled back the edit. It did not — the file is already on disk with the broken content. The LLM must either follow up with a fixing edit or call `revert_file` to restore the previous version.")
        params {
            required("path", "string") {
                llmSeesIt("The path of the file to modify (absolute or relative to the project root).")
                humanReadable("Where the file lives. Relative paths resolve against the project root; absolute paths must canonicalise to inside the project root or the agent's memory directory.")
                whenPresent("The path is canonicalised, validated against the project root + `{agentDir}/memory/` allow-list via PathValidator, and the file is opened for editing.")
                constraint("must point inside the project root or `{agentDir}/memory/` — `../etc/passwd`-style traversal is rejected before any file I/O")
                constraint("must be an existing file — edit_file does not create files; use create_file for that")
                example("src/main/kotlin/Foo.kt")
                example("/Users/me/projects/myrepo/build.gradle")
            }
            required("old_string", "string") {
                llmSeesIt("The exact text to find in the file. Must match character-for-character including whitespace, indentation, and line endings. Include just enough lines to uniquely match the section that needs to change. If the file content came from read_file with line numbers (e.g., '42\tconst x = 1'), do NOT include the line number prefix — match only the raw file text.")
                humanReadable("The exact bytes you want replaced — copied verbatim from what `read_file` showed (without the `N\\t` line-number prefix). The matcher uses `String.indexOf` — there is no whitespace tolerance, no fuzzy matching, and no re-indentation. If a single character differs, the match fails with 0 occurrences.")
                whenPresent("The file is searched for this exact byte sequence. Found exactly once: edit proceeds. Found zero times: error 'old_string not found'. Found 2+ times with `replace_all=false`: error 'old_string not unique'.")
                constraint("character-for-character literal match — whitespace, tabs, CRLF/LF line endings all matter")
                constraint("must NOT include the `N\\t` line-number prefix from read_file output")
                constraint("must occur exactly once in the file unless `replace_all=true`")
                example("    val foo = computeFoo()\n    return foo")
            }
            required("new_string", "string") {
                llmSeesIt("The new content to replace old_string with. To delete code, use an empty string.")
                humanReadable("The replacement bytes. Indentation and line endings should match the surrounding file style — the tool does not re-indent for you. Pass `\"\"` (empty string) to delete the matched region.")
                whenPresent("Each occurrence (one, or all if `replace_all=true`) of `old_string` is replaced by this content. The result is written back through Document API → VFS → file I/O fallback chain.")
                constraint("preserve the file's existing indentation style (tabs vs spaces, depth) — the tool will not normalise it")
                example("    val foo = computeFoo()\n    require(foo != null)\n    return foo")
                example("")
            }
            required("description", "string") {
                llmSeesIt("Brief description of what this edit does and why (shown to user in approval dialog).")
                humanReadable("The one-liner the user sees in the approval dialog. Vague descriptions ('edit file') give the user nothing actionable; specific ones ('inline getFoo() into bar since it's only called once') let them decide quickly. The tool itself does not read this — it's purely UX glue.")
                whenPresent("Surfaced in the approval dialog and the chat history's tool-call bubble. Stored on the ToolResult for trace replay.")
                constraint("required — no default, no fallback")
                example("Inline getFoo() into bar — only call site")
                example("Add null-check before dereferencing user.profile")
            }
            optional("replace_all", "boolean") {
                llmSeesIt("Replace all occurrences of old_string instead of requiring a unique match. Default: false.")
                humanReadable("Switches from 'unique match required' mode to 'replace every occurrence' mode. Useful for genuinely repetitive changes (e.g. updating every `TODO(2024)` to `TODO(2026)`). Brittle for symbol renames — prefer `refactor_rename` for those.")
                whenPresent("All occurrences of `old_string` are replaced in a single write. The Document-API path replaces from end-to-start to preserve offsets. Result summary includes the count: `Replaced 47 chars with 52 chars in foo.kt (3 occurrences)`.")
                whenAbsent("Defaults to false — a non-unique `old_string` returns an error rather than guessing which occurrence to replace.")
                constraint("must be a JSON boolean (`true` / `false`), not a string")
                example("true")
                example("false")
            }
        }
        verdict {
            keep(
                "The agent's primary code-modification primitive. Removing it would force overwrite-the-whole-file edits via create_file (destroys precision and the granular approval gate) or shell-out via `run_command sed` (bypasses VFS undo, editor sync, PathValidator, and the IDE diagnostics integration). The character-strict matcher is also a feature, not a bug — it makes edits deterministic and reviewable.",
                VerdictSeverity.STRONG,
            )
        }
        related("read_file", Relationship.COMPOSE_WITH, "ALWAYS read first — edit_file's matcher is character-strict, so the `old_string` must come from a fresh read of the actual file bytes (without the `N\\t` line-number prefix).")
        related("create_file", Relationship.ALTERNATIVE, "Use instead when the file does not yet exist, or when the change is so sweeping that an in-place edit would be more disruptive than just rewriting.")
        related("revert_file", Relationship.FALLBACK, "Use to undo a bad edit (e.g. one that introduced a syntax error the LLM can't fix in another round). Single-file revert via VCS history.")
        related("refactor_rename", Relationship.ALTERNATIVE, "Use instead for symbol-level renames — understands scope, references, and language semantics. `edit_file replace_all` is string-level and will rewrite substrings of unrelated identifiers.")
        related("diagnostics", Relationship.COMPLEMENT, "Call after editing non-Java/Kotlin files to catch errors the syntax validator can't see (Python typos, broken JSON, malformed YAML).")
        downside("`old_string` must be unique in the file unless `replace_all=true` — a too-small snippet errors out with 'old_string not unique'. Forces the LLM to add 3-5 lines of surrounding context, which slightly inflates token cost on every edit.")
        downside("Character-strict matcher with no whitespace tolerance: a single different space, tab, or CRLF/LF mismatch makes the match fail with 0 occurrences. The error message says 'verify exact text', but the LLM cannot fix a content drift it doesn't know about.")
        downside("Whole-file rewrites are not the use case — for those, `create_file` (overwrite-with-confirmation) is the right tool. edit_file silently struggles when `old_string` is the entire file and `new_string` is also the entire file.")
        downside("Syntax validation is Java/Kotlin-only and best-effort — Python, JS, JSON, YAML, Markdown all bypass the gate. Failures only surface when the LLM (or user) later runs `diagnostics` or build/test tools.")
        downside("Syntax warnings do NOT roll back the edit — the file is already on disk with the broken content. The LLM must follow up with a fix or `revert_file`.")
        downside("`description` is a required parameter even when the user has approved the tool for the whole session and won't see the dialog — burns tokens on every call regardless.")
        flowchart("""
            flowchart TD
                A[LLM calls edit_file] --> B{Path validates?}
                B -- no --> X1[Return path-outside-project error]
                B -- yes --> C{File exists?}
                C -- no --> X2[Return file-not-found error]
                C -- yes --> D[Read content via Document then VFS then java.io.File]
                D --> E[Count occurrences of old_string]
                E --> F{Occurrences?}
                F -- 0 --> X3[Return old_string-not-found error]
                F -- 2+ and not replace_all --> X4[Return not-unique error]
                F -- 1 or replace_all --> G{Kotlin/Java?}
                G -- yes --> H[SyntaxValidator.validate]
                H --> I{errors?}
                I -- yes --> J[Capture warning, do NOT roll back]
                I -- no --> K[Try Document API + WriteCommandAction]
                G -- no --> K
                J --> K
                K -- failed --> L[Try VFS setBinaryContent]
                L -- failed --> M[Try java.io.File.writeText]
                K -- success --> N[Build context + diff]
                L -- success --> N
                M -- success --> N
                M -- failed --> X5[Return write-failed error]
                N --> O[Return summary + context + diff + optional warning]
        """)
        narrative("edit_file")
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' parameter required", "Error: missing path", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val oldString = params["old_string"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'old_string' parameter required", "Error: missing old_string", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val newString = params["new_string"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'new_string' parameter required", "Error: missing new_string", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val replaceAll = params["replace_all"]?.jsonPrimitive?.boolean ?: false

        val extraRoots = project.basePath?.let {
            listOf(
                File(ProjectIdentifier.agentDir(it), "memory").absolutePath,
                ProjectIdentifier.researchDir(it).absolutePath,
            )
        } ?: emptyList()
        val (path, pathError) = PathValidator.resolveAndValidateForWrite(rawPath, project.basePath, extraRoots)
        if (pathError != null) return pathError
        val resolvedPath = path!!

        // Try VFS-backed path first (undo support, sees unsaved changes), fall back to java.io.File
        val vFile = findVirtualFile(resolvedPath)
        val file = java.io.File(resolvedPath)

        if (vFile == null && (!file.exists() || !file.isFile)) {
            return ToolResult("Error: File not found: $resolvedPath", "Error: file not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        // Read content: prefer Document (sees unsaved editor changes), then VFS, then java.io.File
        val content = readFileContent(vFile, file)

        val occurrences = countOccurrences(content, oldString)

        if (occurrences == 0) {
            return ToolResult(
                "Error: old_string not found in $rawPath. Verify the exact text including whitespace.",
                "Error: old_string not found",
                5,
                isError = true
            )
        }

        if (occurrences > 1 && !replaceAll) {
            return ToolResult(
                "Error: old_string found $occurrences times in $rawPath. Provide a larger, unique string with more context or use replace_all=true.",
                "Error: old_string not unique ($occurrences occurrences)",
                5,
                isError = true
            )
        }

        // Capture content before edit for unified diff generation
        val contentBeforeEdit = content

        // Compute new content for syntax validation and fallback writes
        val newContent = if (replaceAll) content.replace(oldString, newString)
        else content.replaceFirst(oldString, newString)

        // Syntax validation gate: warn about syntax errors but don't block
        val extension = resolvedPath.substringAfterLast('.', "").lowercase()
        var syntaxWarning: String? = null
        if (extension in setOf("kt", "java")) {
            try {
                val errors = SyntaxValidator.validate(project, resolvedPath, newContent)
                if (errors.isNotEmpty()) {
                    val errorDetails = errors.joinToString("\n") { "  Line ${it.line}:${it.column}: ${it.message}" }
                    syntaxWarning = "WARNING: This edit introduced ${errors.size} syntax error(s). You should fix these:\n$errorDetails"
                }
            } catch (_: Exception) {
                // Syntax validation unavailable (e.g., no PSI in test) — proceed without gate
            }
        }

        // Apply the edit: try Document API (undo-aware), then VFS, then direct file I/O
        val written = writeViaDocument(vFile, project, rawPath, oldString, newString, replaceAll)
            || writeViaVfs(vFile, project, newContent)
            || writeViaFileIo(file, newContent)

        if (!written) {
            return ToolResult(
                "Error: Failed to write to $rawPath",
                "Error: write failed",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Drop JPS's in-memory incremental-build snapshot so the next
        // CompilerManager.make / ProjectTaskManager.build re-reads source stamps
        // from disk. Without this, edits whose write path bypasses the standard
        // VFS-change listener chain (notably the writeViaFileIo fallback, but
        // also some Document-path edge cases under concurrent agent activity)
        // can leave JPS believing nothing has changed; the next "build" then
        // silently no-ops and downstream test runs load the old .class file —
        // the long-standing "newly-added test method not found" symptom that
        // previously required an IDE restart to clear. Best-effort: never let a
        // cache-clear failure block a successful write.
        try {
            if (ApplicationManager.getApplication() != null) {
                PostMutationRefresh.clearJpsCache(project)
            }
        } catch (_: Exception) { /* best-effort */ }

        // Change tracking: AgentLoop.modifiedFiles collects artifacts from ToolResult

        // Compute the 1-based line range of the edit for diff context display.
        var startLine = 0
        var endLine = 0
        try {
            val editStart = content.indexOf(oldString)
            if (editStart >= 0) {
                startLine = content.substring(0, editStart).count { it == '\n' } + 1
                val newLines = newString.count { it == '\n' } + 1
                endLine = startLine + newLines - 1
            }
        } catch (_: Exception) { /* best-effort */ }

        // Build diff context (3 lines before/after edit) for LLM verification
        val contextLines = try {
            val newFileContent = readFileContent(vFile, file)
            val allNewLines = newFileContent.lines()
            val contextStart = (startLine - 4).coerceAtLeast(0)
            val contextEnd = (endLine + 3).coerceAtMost(allNewLines.size)
            if (contextStart < contextEnd) {
                allNewLines.subList(contextStart, contextEnd).mapIndexed { idx, line ->
                    "${contextStart + idx + 1}\t$line"
                }.joinToString("\n")
            } else null
        } catch (_: Exception) { null }

        val contextSection = if (contextLines != null) "\n\nContext after edit:\n$contextLines" else ""
        val occurrenceSuffix = if (replaceAll && occurrences > 1) " ($occurrences occurrences)" else ""
        val summary = "Replaced ${oldString.length} chars with ${newString.length} chars in $rawPath$occurrenceSuffix"

        // Generate unified diff for UI display
        val editDiff = try {
            DiffUtil.unifiedDiff(contentBeforeEdit, newContent, rawPath)
        } catch (_: Exception) { null }

        if (syntaxWarning != null) {
            return ToolResult(
                content = "$summary\n$syntaxWarning$contextSection",
                summary = "$summary (${syntaxWarning.count { it == '\n' }} syntax warnings)",
                tokenEstimate = TokenEstimator.estimate(summary + syntaxWarning + (contextLines ?: "")),
                artifacts = listOf(resolvedPath),
                isError = false,
                diff = editDiff
            )
        }

        return ToolResult(
            content = "$summary$contextSection",
            summary = summary,
            tokenEstimate = TokenEstimator.estimate(summary + (contextLines ?: "")),
            artifacts = listOf(resolvedPath),
            diff = editDiff
        )
    }

    /**
     * Find VirtualFile via LocalFileSystem. Returns null if VFS is unavailable (e.g., unit tests).
     */
    private fun findVirtualFile(resolvedPath: String): VirtualFile? {
        return try {
            if (ApplicationManager.getApplication() == null) return null
            LocalFileSystem.getInstance().findFileByPath(resolvedPath)
                ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(resolvedPath)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Read file content. Prefers Document (sees unsaved editor changes), then VFS, then java.io.File.
     */
    private suspend fun readFileContent(vFile: VirtualFile?, file: java.io.File): String {
        if (vFile != null) {
            try {
                return readAction {
                    val document = FileDocumentManager.getInstance().getDocument(vFile)
                    document?.text ?: String(vFile.contentsToByteArray(), vFile.charset)
                }
            } catch (_: Exception) {
                // readAction unavailable — fall through to java.io.File
            }
        }
        return file.readText(Charsets.UTF_8)
    }

    /**
     * Write via Document API + WriteCommandAction. Provides undo support and immediate editor sync.
     * Returns true if write succeeded via Document.
     */
    private fun writeViaDocument(
        vFile: VirtualFile?,
        project: Project,
        rawPath: String,
        oldString: String,
        newString: String,
        replaceAll: Boolean
    ): Boolean {
        if (vFile == null) return false
        return try {
            var success = false
            invokeAndWaitIfNeeded {
                WriteCommandAction.runWriteCommandAction(project, "Agent: edit $rawPath", null, Runnable {
                    val document = FileDocumentManager.getInstance().getDocument(vFile) ?: return@Runnable
                    if (replaceAll) {
                        // Replace all occurrences from end to preserve offsets
                        var text = document.text
                        var offset = text.lastIndexOf(oldString)
                        while (offset >= 0) {
                            document.replaceString(offset, offset + oldString.length, newString)
                            text = document.text
                            offset = if (offset > 0) text.lastIndexOf(oldString, offset - 1) else -1
                        }
                    } else {
                        val offset = document.text.indexOf(oldString)
                        if (offset >= 0) {
                            document.replaceString(offset, offset + oldString.length, newString)
                        }
                    }
                    success = true
                })
            }
            success
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Write via VFS setBinaryContent inside WriteCommandAction.
     * Used when Document is null (file not open in editor).
     */
    private fun writeViaVfs(vFile: VirtualFile?, project: Project, newContent: String): Boolean {
        if (vFile == null) return false
        return try {
            invokeAndWaitIfNeeded {
                WriteCommandAction.runWriteCommandAction(project) {
                    vFile.setBinaryContent(newContent.toByteArray(vFile.charset))
                }
            }
            // Force VFS refresh so IDE diagnostics see changes immediately
            // (avoids 1-2s VFS watcher delay before diagnostics update)
            try { vFile.refresh(false, false) } catch (_: Exception) { }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Final fallback: direct java.io.File write. Used in test environments without full IDE.
     */
    private fun writeViaFileIo(file: java.io.File, newContent: String): Boolean {
        return try {
            file.writeText(newContent, Charsets.UTF_8)
            // Refresh VFS so IDE sees the change
            try { LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath) } catch (_: Exception) { }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun countOccurrences(text: String, search: String): Int {
        if (search.isEmpty()) return 0
        var count = 0
        var index = text.indexOf(search)
        while (index >= 0) {
            count++
            index = text.indexOf(search, index + 1)
        }
        return count
    }
}
