package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class SearchCodeTool : AgentTool {
    override val name = "search_code"
    override val description = "Regex search across files in a directory OR within a single file, with context-rich results. Use for finding code patterns, definitions, imports, error messages, or grepping one specific file (e.g. a spilled tool-output dump under the agent data dir ~/.workflow-orchestrator/sessions/{id}/tool-output/). Output modes: 'files' (paths only — default, lightweight), 'content' (matching lines + context), 'count' (per-file counts)."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "pattern" to ParameterProperty(type = "string", description = "The regular expression pattern to search for. Uses standard regex syntax. Literal strings are also accepted and will be auto-escaped if they contain invalid regex."),
            "path" to ParameterProperty(type = "string", description = "The path of a directory or single file to search in (absolute or relative to the project root). Directories are walked recursively; a file path greps the lines of that one file. May also point under ~/.workflow-orchestrator/ (e.g. agent session tool-output dir) to search spilled output. Defaults to project root."),
            "output_mode" to ParameterProperty(type = "string", description = "Output mode: 'files' (file paths only, default — lightweight for discovery), 'content' (matching lines with surrounding context), 'count' (match counts per file).", enumValues = listOf("files", "content", "count")),
            "file_type" to ParameterProperty(type = "string", description = "File extension filter (e.g., 'kt' for Kotlin files, 'java' for Java files). If not provided, it will search all files."),
            "case_insensitive" to ParameterProperty(type = "boolean", description = "Case-insensitive search. Default: false."),
            "context_lines" to ParameterProperty(type = "integer", description = "Lines of context before and after each match (only for output_mode='content'). Default: 0."),
            "max_results" to ParameterProperty(type = "integer", description = "Maximum matches to return. Default: 50.")
        ),
        required = listOf("pattern")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override fun documentation(): ToolDocumentation = toolDoc("search_code") {
        summary {
            technical("Recursive regex search over files under a directory using Kotlin's `Regex` engine (java.util.regex). Returns one of three output modes — 'files' (default — distinct matching paths), 'content' (line-numbered matches with optional before/after context), or 'count' (per-file match tallies). Streams files line-by-line for the no-context path; reads whole files into memory only when context is requested. Hard-coded skip-list (.git, build, node_modules, target, .gradle, .idea, etc.), per-file 1MB cap, binary-extension filter. PathValidator-restricted to the project root or `~/.workflow-orchestrator/`. Auto-falls back to `Regex.escape()` when the pattern is invalid regex, so literal strings 'just work'.")
            plain("Like `grep -rE` in a terminal, but it knows to skip junk folders (.git, build, node_modules), caps each file at 1MB, and gives you a choice of three output styles — just paths (cheap discovery), full matching lines (with context, like `grep -C 2`), or per-file counts. Good for 'where in the codebase do we call this method?' or 'which files mention this error string?'.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without search_code, the LLM falls back to `run_command grep -r 'pat' .` (or `Select-String` on Windows), which: (1) is approval-gated per invocation because run_command is `ALWAYS_PER_INVOCATION` — a 5-search exploration costs 5 user clicks, (2) emits divergent output between BSD grep (macOS) and GNU grep (Linux/CI) — `-E` flags, color codes, byte offsets all differ — making downstream parsing fragile, (3) bypasses PathValidator (the LLM can grep `/etc` or arbitrary user dirs), (4) doesn't honor the build/VCS skip-list, so output explodes with build/, .git/, node_modules/ noise on real projects, and (5) bypasses ToolOutputSpiller — large match sets just truncate instead of spilling to disk for follow-up. Net: ~5x more shell calls per investigation plus a real security regression."
        )
        llmMistake("Uses an unanchored pattern that matches too broadly — e.g. `pattern='User'` returns thousands of hits across `UserService`, `userId`, `user.name`, comments, etc. Should add word boundaries (`\\bUser\\b`), file_type filter, or scope via `path=` to the relevant module.")
        llmMistake("Passes a literal string with regex metacharacters un-escaped — e.g. `pattern='List<String>'` is interpreted as a regex with a character class. The tool auto-falls back to `Regex.escape()` when the pattern is invalid, but a *valid-but-wrong* regex (e.g. `'a.b.c'` matching `axbyc`) silently does the wrong thing. The LLM should escape literal dots/parens/brackets explicitly.")
        llmMistake("Uses search_code when find_definition / find_references / find_implementations would be more precise — regex sees `class Foo`, `// Foo is`, and `String foo = \"Foo\"` as equivalent hits. PSI-aware tools resolve symbols and skip comments/strings.")
        llmMistake("Picks `output_mode='content'` for a sweeping pattern with no `max_results` cap, then has the result truncated mid-match by ToolOutputSpiller. Should start with `output_mode='files'` (default — lightweight) to bound the search, then `output_mode='content'` with a tighter `path=`/`file_type=` once the candidate set is small.")
        llmMistake("Forgets that `context_lines` only applies in `output_mode='content'` — setting it under `output_mode='files'` or `'count'` is silently dropped (no warning, no error). Wastes a parameter slot in the tool call.")
        llmMistake("Uses search_code on a directory that requires globally-deterministic results (e.g. enforcing CI-vs-local parity) — the walk visits files in alphabetical order per directory but `max_results` early-terminates, so a broad pattern returns a different first-50 across machines if `mtime` or filesystem layout differs.")
        params {
            required("pattern", "string") {
                llmSeesIt("The regular expression pattern to search for. Uses standard regex syntax. Literal strings are also accepted and will be auto-escaped if they contain invalid regex.")
                humanReadable("The regex (or literal string) to search for. Same syntax as `java.util.regex` — `\\b` for word boundaries, `(?i)` for inline case-insensitive, `(?m)` for multi-line anchors. If the pattern fails to compile, the tool falls back to a `Regex.escape()`'d literal match instead of erroring — so plain strings like `List<String>` work without manual escaping.")
                whenPresent("Compiled to a `kotlin.text.Regex` (with `IGNORE_CASE` if `case_insensitive=true`). Tested against each line via `containsMatchIn` — matching is per-line, not multi-line — so `\\n` in the pattern will never match. On a `PatternSyntaxException` the pattern is silently re-compiled as `Regex.escape(pattern)` so literals always work.")
                constraint("standard `java.util.regex` syntax — `\\b`, `\\s`, `\\d`, lookahead, named groups all supported")
                constraint("matched per-line — anchors `^` and `\$` bind to line start/end, not file start/end; `.` does NOT match newlines unless you use `(?s)` (which is moot here since each line is matched separately)")
                constraint("invalid regex auto-falls-back to escaped-literal match — no error surfaced to the LLM, so a typo'd pattern can silently match the wrong thing")
                example("\\bclass\\s+UserService\\b")
                example("TODO\\(.*\\)")
                example("(?i)deprecated")
                example("List<String>")
            }
            optional("path", "string") {
                llmSeesIt("The path of a directory or single file to search in (absolute or relative to the project root). Directories are walked recursively; a file path greps the lines of that one file. May also point under ~/.workflow-orchestrator/ (e.g. agent session tool-output dir) to search spilled output. Defaults to project root.")
                humanReadable("Where to grep. A directory walks all matching files recursively; a single file path narrows the grep to that one file (handy for spilled tool-output dumps). Relative paths anchor on the project root; absolute paths must still resolve under the project or under the agent's `~/.workflow-orchestrator/` data dir.")
                whenPresent("Resolved + canonicalized via `PathValidator.resolveAndValidateForRead`. If it's a directory, the walk descends from it (honoring the skip-list and `file_type` filter). If it's a file, only that file is grepped (still subject to the 1MB cap and binary-extension filter). Emitted match paths are relative-to-project for files inside the project, absolute canonical otherwise.")
                whenAbsent("Defaults to the project root (walked recursively).")
                constraint("must resolve under the project root or `~/.workflow-orchestrator/` — paths outside both are rejected by PathValidator before any read")
                constraint("if pointing to a file, the file's extension still has to clear the binary-extension filter (`jar`, `png`, `zip`, etc. are rejected) and the 1MB size cap; otherwise it returns zero matches with no warning")
                example("src/main/kotlin")
                example("agent/src/main")
                example(".workflow-orchestrator/repo-abc123/agent/sessions/<id>/tool-output/bamboo_builds-1234567-output.txt")
            }
            optional("output_mode", "string") {
                llmSeesIt("Output mode: 'files' (file paths only, default — lightweight for discovery), 'content' (matching lines with surrounding context), 'count' (match counts per file).")
                humanReadable("Three styles — pick by how much detail you actually need. 'files' is just a deduped list of matching paths (cheapest, like `grep -l`). 'content' shows each matching line with optional before/after context (like `grep -nC 2`). 'count' returns one `path: N matches` per file (like `grep -c`).")
                whenPresent("Switches the formatter: 'files' → distinct relative paths joined by newline; 'content' → `path:line: text` per match (or `path:line:> text` for the match line and `path:line:  text` for context lines, with `---` separators between match groups); 'count' → `path: N matches` per file.")
                whenAbsent("Defaults to 'files' — the lightweight path-only mode.")
                enumValue("files", "content", "count")
                constraint("any value other than the three enums silently falls through to 'files' (default branch in `when`) — no error, but unexpected if the LLM typos `'contents'` or `'list'`")
                example("files")
                example("content")
                example("count")
            }
            optional("file_type", "string") {
                llmSeesIt("File extension filter (e.g., 'kt' for Kotlin files, 'java' for Java files). If not provided, it will search all files.")
                humanReadable("Restrict to one extension — like `find . -name '*.kt' -exec grep` but baked in. Massively cuts noise on polyglot projects (e.g. limit to `kt` to skip `.md`/`.html` matches).")
                whenPresent("Lower-cased and compared with `file.extension.lowercase()` — only files whose extension matches exactly are searched.")
                whenAbsent("All non-binary files under 1MB are searched.")
                constraint("single extension only — no comma-separated list, no glob (use multiple search_code calls or `path=` narrowing instead)")
                constraint("compared exact-match against `file.extension` — `'kotlin'` will match nothing because the extension is `'kt'`")
                example("kt")
                example("java")
                example("py")
            }
            optional("case_insensitive", "boolean") {
                llmSeesIt("Case-insensitive search. Default: false.")
                humanReadable("If true, `User` matches `user` and `USER`. Equivalent to inlining `(?i)` at the start of the pattern.")
                whenPresent("`RegexOption.IGNORE_CASE` is added to the compiled regex.")
                whenAbsent("Defaults to false — case-sensitive match.")
                example("true")
            }
            optional("context_lines", "integer") {
                llmSeesIt("Lines of context before and after each match (only for output_mode='content'). Default: 0.")
                humanReadable("Like `grep -C N` — emit N lines before and N lines after each match line. Helps the LLM see surrounding code without a follow-up read_file. Only meaningful with `output_mode='content'`.")
                whenPresent("Triggers the slower whole-file-into-memory read path (instead of the streaming line-by-line path), so each match is bracketed by N lines of context with `---` separators between match groups.")
                whenAbsent("Defaults to 0 — no context, streaming line-by-line read (cheaper).")
                constraint("only honored under `output_mode='content'` — silently ignored otherwise")
                constraint("each context line counts toward the default 50K output cap, so wide context (e.g. `context_lines=20`) on a broad pattern can blow the budget fast")
                example("2")
                example("5")
            }
            optional("max_results", "integer") {
                llmSeesIt("Maximum matches to return. Default: 50.")
                humanReadable("Caps the result count — like `head -50` on a long match list. The walk early-terminates as soon as this many matches are collected (alphabetical order per directory).")
                whenPresent("Search aborts as soon as `matches.size >= maxResults`; result includes a `... (results limited to N)` footer in 'content' mode when the cap clips.")
                whenAbsent("Defaults to 50.")
                constraint("early-termination is in walk order (alphabetical per directory), NOT relevance order — a broad pattern returns 'first 50 we hit' which may not be the most useful 50")
                example("20")
                example("200")
            }
            optional("grep_pattern", "string") {
                llmSeesIt("Regex pattern to filter output lines. Only lines matching this pattern are returned. Use when you only need specific information from a potentially large output.")
                humanReadable("Auto-injected by AgentLoop because search_code is in `OUTPUT_FILTERABLE_TOOLS`. A second-stage filter applied to the formatted result lines AFTER the main search runs — like piping `search_code | grep <grep_pattern>`. Useful for narrowing 'content' mode output to just lines containing a secondary keyword without a follow-up call.")
                whenPresent("After search_code formats its result, lines not matching `grep_pattern` are dropped before the result reaches the LLM. Filtering is applied pre-spill, so it also reduces the size of any spilled file.")
                whenAbsent("No second-stage filter — full formatted result is returned (subject to ToolOutputSpiller).")
                constraint("does NOT replace `pattern` — the main search still runs in full; this is post-formatting line filtering only")
                example("ERROR|WARN")
                example("class\\s+\\w+Service")
            }
            optional("output_file", "boolean") {
                llmSeesIt("If true, save full output to a file and return a preview with the file path. Use for large outputs you may need to search later. Read the file with read_file or search_code.")
                humanReadable("Auto-injected by AgentLoop. When `true`, the full search result is spilled to `{sessionDir}/tool-output/search_code-<epoch>-output.txt` and the LLM gets a head-20 + tail-10 preview plus the file path. Lets the LLM run a broad search once, then re-grep the saved file with read_file/search_code instead of re-running the search.")
                whenPresent("Result is spilled to disk and the LLM receives only a preview (head 20 lines + tail 10 lines + file reference). Subsequent calls can `read_file` the spill or `search_code` it with a tighter pattern.")
                whenAbsent("Output is returned inline; if it exceeds 30K chars, ToolOutputSpiller spills it automatically anyway.")
                example("true")
            }
        }
        verdict {
            keep(
                "Foundational discovery primitive and the only PSI-free content search the LLM has. It complements glob_files (paths) and find_definition/find_references (PSI) by covering the case where you don't know enough to ask PSI: searching string literals, comments, log messages, configuration values, error strings, TODO markers — anything that lives in file bytes rather than language semantics. Removing this would force every content search through `run_command grep`, which is approval-gated per invocation, format-divergent across BSD/GNU grep, bypasses PathValidator, and pollutes the chat with build/ and node_modules/ noise. The three output modes (files/content/count) earn their slots — 'files' is the cheap default for narrowing, 'content' is for inspection, 'count' is for triaging hot spots before drilling in.",
                VerdictSeverity.STRONG,
            )
        }
        related("glob_files", Relationship.COMPLEMENT, "Use when matching on file NAMES rather than file CONTENTS — `**/*Test.kt` is a glob_files job, `class.*Test` inside files is a search_code job. Often paired: glob to narrow candidate files, then search_code with `path=` on the result.")
        related("find_definition", Relationship.ALTERNATIVE, "Use instead when you want a symbol's source — PSI resolves `class Foo` precisely whereas search_code returns every textual `Foo` including comments, strings, and unrelated identifiers.")
        related("find_references", Relationship.ALTERNATIVE, "Use instead when you want callers/usages of a known symbol — PSI follows imports, generics, and overloads; search_code can't distinguish `foo.bar()` (method call) from `// foo.bar` (comment).")
        related("read_file", Relationship.COMPOSE_WITH, "Use after search_code locates the right file — `output_mode='files'` to find candidates, then read_file to inspect the full surrounding code.")
        related("structural_search", Relationship.ALTERNATIVE, "Use for AST-shape queries (e.g. 'all `try { } catch (Exception) { }` blocks') — search_code's regex can't see code structure, structural_search can.")
        downside("Regex-only — no PSI semantics. Hits inside comments, string literals, and unrelated identifiers are all returned equally. For a precise symbol-shaped search prefer find_definition / find_references / structural_search.")
        downside("Hard-coded skip-list. There is no way to opt-in to scanning `.gradle/` or `node_modules/` even when the user explicitly wants to (e.g. greppinng a vendored dep for a known string). The LLM has to fall back to `run_command grep` for those cases.")
        downside("Per-file 1MB cap means generated SQL dumps, large JSON fixtures, and minified bundles are silently skipped — no warning surfaces to the LLM that the file was excluded. A search that 'finds nothing' may actually be missing the file entirely.")
        downside("Per-line matching only — patterns spanning newlines (`function foo\\(\\) {\\n  return`) will never match. The regex sees one line at a time. For multi-line patterns, use structural_search or read_file + manual inspection.")
        downside("Invalid regex silently auto-escapes via `Regex.escape(pattern)` — no error surfaces to the LLM. A pattern with a typo'd backslash falls back to literal-string match, which can silently return the wrong results.")
        downside("Walk order is alphabetical per directory and `max_results` early-terminates, so a broad pattern returns 'first 50 we hit', NOT the most-relevant 50 or the most-recently-modified 50. Differs from glob_files which sorts by mtime DESC.")
        downside("`.gitignore` is not honored — generated files outside the fixed skip-list (e.g. `vendor/`, custom output dirs, `target/generated-sources/` once the path crosses outside `target/`) appear in matches.")
        downside("Backward-compat aliases `query`→`pattern` and `scope`→`path` are accepted but undocumented in the description — the LLM never learns they exist, so they only help on replayed sessions or hand-edited tool calls.")
        flowchart("""
            flowchart TD
                A[LLM calls search_code] --> B{path validates?}
                B -- no --> X1[Return path-traversal error]
                B -- yes --> C{searchRoot exists?}
                C -- no --> X2[Return path-does-not-exist error]
                C -- yes --> D{Compile regex}
                D -- invalid --> E[Fall back to Regex.escape literal]
                D -- valid --> F{searchRoot type?}
                E --> F
                F -- file --> S[Match one file (matchSingleFile)]
                F -- directory --> W[Walk searchRoot]
                S --> M{matches >= max_results?}
                W --> G{In SKIP_DIRS?}
                G -- yes --> W
                G -- no --> H{Binary ext or >1MB?}
                H -- yes --> W
                H -- no --> I{file_type matches?}
                I -- no --> W
                I -- yes --> J{output_mode=content with context?}
                J -- yes --> K[Read full file, collect ctxBefore+ctxAfter]
                J -- no --> L[Stream lines, collect matches only]
                K --> M
                L --> M
                M -- no --> W
                M -- yes --> N[Stop walk]
                N --> O{output_mode}
                O -- files --> P1[Distinct paths]
                O -- content --> P2[path:line: text + context]
                O -- count --> P3[path: N matches]
                P1 --> Q[Apply grep_pattern + spill if output_file or >30K]
                P2 --> Q
                P3 --> Q
        """)
    }

    companion object {
        private const val DEFAULT_MAX_RESULTS = 50
        /** Files larger than this are skipped to bound ReDoS exposure (5 MB). */
        internal const val FILE_SIZE_CAP_BYTES = 5_000_000L
        private val BINARY_EXTENSIONS = setOf(
            "jar", "class", "png", "jpg", "jpeg", "gif", "ico", "svg",
            "zip", "tar", "gz", "war", "ear", "so", "dll", "exe",
            "pdf", "woff", "woff2", "ttf", "eot"
        )
        private val SKIP_DIRS = setOf(
            ".git", ".idea", "node_modules", "target", "build", ".gradle", ".worktrees", ".workflow",
            "out", "dist", ".svn", ".hg", "__pycache__", ".tox", ".mypy_cache",
            "api-debug"
        )
    }

    private val log = Logger.getInstance(SearchCodeTool::class.java)

    data class SearchMatch(
        val relativePath: String,
        val lineNumber: Int,
        val lineContent: String,
        val contextBefore: List<String> = emptyList(),
        val contextAfter: List<String> = emptyList()
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        // Backward compat: accept "query" as alias for "pattern"
        val pattern = (params["pattern"] ?: params["query"])?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'pattern' parameter required", "Error: missing pattern", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        // Backward compat: accept "scope" as alias for "path"
        val searchPath = (params["path"] ?: params["scope"])?.jsonPrimitive?.content
        val outputMode = params["output_mode"]?.jsonPrimitive?.content?.lowercase() ?: "files"
        val fileType = params["file_type"]?.jsonPrimitive?.content?.lowercase()
        val caseInsensitive = try { params["case_insensitive"]?.jsonPrimitive?.boolean } catch (_: Exception) { null } ?: false
        val contextLines = try { params["context_lines"]?.jsonPrimitive?.int } catch (_: Exception) { null } ?: 0
        val maxResults = try { params["max_results"]?.jsonPrimitive?.int } catch (_: Exception) { null } ?: DEFAULT_MAX_RESULTS

        val basePath = project.basePath
            ?: return ToolResult("Error: Project base path not available", "Error: no project path", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val searchRoot = if (searchPath != null) {
            val (validatedPath, pathError) = PathValidator.resolveAndValidateForRead(searchPath, basePath)
            if (pathError != null) return pathError
            File(validatedPath!!)
        } else {
            File(basePath)
        }

        if (!searchRoot.exists()) {
            return ToolResult("Error: Search path does not exist: $searchRoot", "Error: path does not exist", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val regexOpts = if (caseInsensitive) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val regex = try {
            Regex(pattern, regexOpts)
        } catch (_: Exception) {
            Regex(Regex.escape(pattern), regexOpts)
        }

        val collectContext = outputMode == "content" && contextLines > 0
        val matches = mutableListOf<SearchMatch>()
        val canonicalProjectRoot = try {
            File(basePath).canonicalPath
        } catch (e: Exception) {
            log.warn("canonicalPath failed for $basePath; falling back to non-canonical (inside-project relative paths may degrade)", e)
            basePath
        }
        if (searchRoot.isFile) {
            // Single-file mode: when the caller explicitly names a file, a silent zero-match
            // on a file_type mismatch is almost always a mistake (the LLM picked the wrong
            // extension). Fail loudly so the LLM can correct rather than retry the same
            // wrong call. Binary-extension and >1MB rejections still surface as silent
            // empty matches because those are legitimate skip conditions documented in the
            // DSL, but file_type is an LLM-supplied filter we can validate up-front.
            if (fileType != null && searchRoot.extension.lowercase() != fileType.lowercase()) {
                return ToolResult(
                    "Error: file_type='$fileType' does not match the extension of '${searchRoot.name}' " +
                        "(extension is '${searchRoot.extension}'). Drop file_type to search this file, " +
                        "or point path at a directory containing $fileType files.",
                    "file_type mismatch on single file",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true,
                )
            }
            matchSingleFile(searchRoot, canonicalProjectRoot, regex, fileType, collectContext, contextLines, matches, maxResults)
        } else {
            searchFiles(searchRoot, canonicalProjectRoot, regex, fileType, collectContext, contextLines, matches, maxResults)
        }

        if (matches.isEmpty()) {
            return ToolResult(
                "No matches found for: $pattern",
                "No matches for '$pattern'",
                ToolResult.ERROR_TOKEN_ESTIMATE
            )
        }

        return when (outputMode) {
            "content" -> formatContentMode(matches, pattern, maxResults)
            "count" -> formatCountMode(matches, pattern)
            else -> formatFilesMode(matches, pattern)
        }
    }

    private fun formatFilesMode(matches: List<SearchMatch>, pattern: String): ToolResult {
        val uniqueFiles = matches.map { it.relativePath }.distinct()
        val content = uniqueFiles.joinToString("\n")
        return ToolResult(
            content = content,
            summary = "${uniqueFiles.size} files match '$pattern'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun formatContentMode(matches: List<SearchMatch>, pattern: String, maxResults: Int): ToolResult {
        val hasContext = matches.any { it.contextBefore.isNotEmpty() || it.contextAfter.isNotEmpty() }
        val sb = StringBuilder()

        for ((index, match) in matches.withIndex()) {
            if (hasContext) {
                // With context: add separator between match groups
                if (index > 0) sb.appendLine("---")

                for ((i, ctxLine) in match.contextBefore.withIndex()) {
                    val ctxLineNum = match.lineNumber - match.contextBefore.size + i
                    sb.appendLine("${match.relativePath}:$ctxLineNum:  $ctxLine")
                }
                sb.appendLine("${match.relativePath}:${match.lineNumber}:> ${match.lineContent}")
                for ((i, ctxLine) in match.contextAfter.withIndex()) {
                    val ctxLineNum = match.lineNumber + 1 + i
                    sb.appendLine("${match.relativePath}:$ctxLineNum:  $ctxLine")
                }
            } else {
                sb.appendLine("${match.relativePath}:${match.lineNumber}: ${match.lineContent}")
            }
        }

        val content = sb.toString().trimEnd()
        val truncatedNote = if (matches.size >= maxResults) "\n... (results limited to $maxResults)" else ""
        val fullContent = content + truncatedNote

        return ToolResult(
            content = fullContent,
            summary = "Found ${matches.size} matches for '$pattern'",
            tokenEstimate = TokenEstimator.estimate(fullContent)
        )
    }

    private fun formatCountMode(matches: List<SearchMatch>, pattern: String): ToolResult {
        val countByFile = matches.groupBy { it.relativePath }.mapValues { it.value.size }
        val totalMatches = countByFile.values.sum()
        val content = countByFile.entries.joinToString("\n") { "${it.key}: ${it.value} matches" }

        return ToolResult(
            content = content,
            summary = "$totalMatches matches across ${countByFile.size} files for '$pattern'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private suspend fun searchFiles(
        dir: File,
        canonicalProjectRoot: String,
        regex: Regex,
        fileType: String?,
        collectContext: Boolean,
        contextLines: Int,
        matches: MutableList<SearchMatch>,
        maxResults: Int
    ) {
        // Propagate AgentLoop's withTimeoutOrNull(120s) cancellation so that an
        // LLM-supplied regex with catastrophic backtracking (e.g. (a+)+b) cannot
        // keep an IO thread burning after the tool's timeout fires.
        coroutineContext.ensureActive()
        if (matches.size >= maxResults) return

        val files = dir.listFiles() ?: return

        for (file in files.sortedBy { it.name }) {
            if (matches.size >= maxResults) return
            // Check cancellation between every file — not just at directory boundaries.
            coroutineContext.ensureActive()

            if (file.isDirectory) {
                if (file.name !in SKIP_DIRS) {
                    searchFiles(file, canonicalProjectRoot, regex, fileType, collectContext, contextLines, matches, maxResults)
                }
                continue
            }

            matchSingleFile(file, canonicalProjectRoot, regex, fileType, collectContext, contextLines, matches, maxResults)
        }
    }

    private fun matchSingleFile(
        file: File,
        canonicalProjectRoot: String,
        regex: Regex,
        fileType: String?,
        collectContext: Boolean,
        contextLines: Int,
        matches: MutableList<SearchMatch>,
        maxResults: Int
    ) {
        if (matches.size >= maxResults) return
        if (file.extension.lowercase() in BINARY_EXTENSIONS) return
        // Cap at 5 MB — reading a file larger than this into memory to run a
        // potentially-catastrophic regex is a denial-of-service risk. The limit is
        // intentionally generous (most source files are <100 KB) but still finite.
        if (file.length() > FILE_SIZE_CAP_BYTES) {
            log.debug("SearchCodeTool: skipping '${file.name}' (${file.length()} bytes > ${FILE_SIZE_CAP_BYTES} byte cap)")
            return
        }
        if (fileType != null && file.extension.lowercase() != fileType) return

        try {
            val canonical = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
            val relativePath = if (canonical.startsWith(canonicalProjectRoot + File.separator)) {
                canonical.removePrefix(canonicalProjectRoot + File.separator).replace('\\', '/')
            } else {
                canonical.replace('\\', '/')
            }
            if (collectContext) {
                // Need full file lines for context window — read all lines
                val lines = file.readLines(Charsets.UTF_8)
                for ((lineIdx, line) in lines.withIndex()) {
                    if (matches.size >= maxResults) return
                    if (regex.containsMatchIn(line)) {
                        val ctxBefore = run {
                            val start = maxOf(0, lineIdx - contextLines)
                            lines.subList(start, lineIdx)
                        }
                        val ctxAfter = run {
                            val end = minOf(lines.size, lineIdx + 1 + contextLines)
                            lines.subList(lineIdx + 1, end)
                        }
                        matches.add(SearchMatch(
                            relativePath = relativePath,
                            lineNumber = lineIdx + 1,
                            lineContent = line.trim(),
                            contextBefore = ctxBefore,
                            contextAfter = ctxAfter
                        ))
                    }
                }
            } else {
                // Stream lines — avoid loading entire file into memory
                file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEachIndexed { lineIdx, line ->
                        if (matches.size >= maxResults) return@useLines
                        if (regex.containsMatchIn(line)) {
                            matches.add(SearchMatch(
                                relativePath = relativePath,
                                lineNumber = lineIdx + 1,
                                lineContent = line.trim()
                            ))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Skip unreadable file
        }
    }
}
