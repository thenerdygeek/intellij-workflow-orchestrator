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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class GlobFilesTool : AgentTool {
    override val name = "glob_files"
    override val description = "List files and directories matching a glob pattern within a specified directory. Returns file paths sorted by modification time (newest first). If a recursive pattern (e.g., '**/*.kt') is used, it will list all matching files recursively. Use this for file discovery — finding what files exist matching a name pattern. The path may also point under the agent's data directory (~/.workflow-orchestrator/) to discover spilled tool-output files; matches outside the project are emitted as absolute canonical paths. Do not use this tool to confirm the existence of files you may have created, as the tool result will let you know if the files were created successfully. For searching file CONTENTS, use search_code instead."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "pattern" to ParameterProperty(type = "string", description = "Glob pattern to match files against (e.g., '**/*.kt' for all Kotlin files, 'src/**/*.java' for Java files under src, '*.xml' for top-level XML files, 'build.gradle*' for Gradle build files)."),
            "path" to ParameterProperty(type = "string", description = "The path of the directory to list contents for (absolute or relative to the project root). May also point under ~/.workflow-orchestrator/ to find spilled tool output. Defaults to project root."),
            "max_results" to ParameterProperty(type = "integer", description = "Maximum files to return. Default: 50.")
        ),
        required = listOf("pattern")
    )

    private val log = Logger.getInstance(GlobFilesTool::class.java)
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override fun documentation(): ToolDocumentation = toolDoc("glob_files") {
        summary {
            technical("Walks the filesystem from a directory root using `java.nio.file.PathMatcher` (`glob:` syntax), prunes a hard-coded set of build/VCS dirs (.git, node_modules, build, target, .gradle, etc.), and returns matches sorted by mtime newest-first. PathValidator-restricted to the project root or `~/.workflow-orchestrator/`.")
            plain("Like `find -name` in a terminal, but it knows to skip junk folders (.git, node_modules, build) and shows you the most recently changed files first. Good for 'where do my Kotlin test files live?' kinds of questions.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without glob_files, the LLM falls back to `run_command find . -name '*.kt'` (or `Get-ChildItem` on Windows), which: (1) doesn't skip .git/build/node_modules so output explodes on real projects, (2) bypasses PathValidator (the LLM can list anywhere on the filesystem), (3) returns lexicographic order rather than mtime-sorted (newest-first is the LLM's actual signal for 'what's relevant'), and (4) costs an approval gate per call because run_command is ALWAYS_PER_INVOCATION. Net: ~3-5x more shell calls per discovery task plus a real security regression."
        )
        llmMistake("Uses glob_files to find content (e.g. `pattern='**/*TODO*.kt'` to find TODO comments) — glob matches on file names only, not file contents. Should use search_code with a regex.")
        llmMistake("Forgets `**/` for recursive descent — `*.kt` from project root only matches top-level files (zero in most projects). The pattern needs `**/*.kt` to recurse.")
        llmMistake("Calls glob_files on a single file path expecting it to confirm existence — returns 'directory not found'. Should use read_file (existence check is implicit) or trust the prior tool's success message, which the description explicitly warns about.")
        llmMistake("Treats the response order as alphabetical and writes follow-up code that assumes sort stability — results are sorted by mtime DESC, so the same pattern returns a different order between runs as files are touched.")
        params {
            required("pattern", "string") {
                llmSeesIt("Glob pattern to match files against (e.g., '**/*.kt' for all Kotlin files, 'src/**/*.java' for Java files under src, '*.xml' for top-level XML files, 'build.gradle*' for Gradle build files).")
                humanReadable("A wildcard expression that says which file names to keep. `**` means 'any depth of folders', `*` means 'any chars but /', `?` means 'one char'. Same syntax as `.gitignore` patterns.")
                whenPresent("Compiled to a `java.nio.file.PathMatcher` (`glob:` syntax) and tested against each visited path three ways: relative-to-search-root, relative-to-project-root, and bare filename — so `*.kt` at any depth still matches even when search_root is the project root.")
                constraint("must be valid `glob:` syntax — `**`, `*`, `?`, character classes `[abc]`, and brace alternation `{a,b}` are supported; an unbalanced `[` or `{` returns 'invalid glob pattern' before any I/O")
                constraint("matches file names only, not file contents — for content search use search_code")
                example("**/*.kt")
                example("src/**/*.java")
                example("**/*Test.kt")
                example("build.gradle*")
            }
            optional("path", "string") {
                llmSeesIt("The path of the directory to list contents for (absolute or relative to the project root). May also point under ~/.workflow-orchestrator/ to find spilled tool output. Defaults to project root.")
                humanReadable("Where to start the search. Relative paths anchor on the project root; absolute paths must still resolve under the project (or under the agent's `~/.workflow-orchestrator/` data dir for read tools).")
                whenPresent("Resolved + canonicalized via `PathValidator.resolveAndValidateForRead`. Search walks from this directory; emitted match paths are relative-to-project when the file is inside the project, absolute otherwise.")
                whenAbsent("Defaults to the project root (canonicalized via `Path.toRealPath()`).")
                constraint("must be a directory — pointing to a file returns 'directory not found'")
                constraint("must resolve under the project root or `~/.workflow-orchestrator/` — paths outside both are rejected by PathValidator before any walk")
                example("src/main/kotlin")
                example("agent/src/test")
            }
            optional("max_results", "integer") {
                llmSeesIt("Maximum files to return. Default: 50.")
                humanReadable("Caps the result count — like `head -50` on a long listing. The walk over-collects (2x cap) to ensure mtime-DESC sort returns the truly newest files, then trims.")
                whenPresent("After mtime-DESC sort, the first N matches are returned; result includes a `... (limited to N of M results)` footer when the cap clips.")
                whenAbsent("Defaults to 50.")
                constraint("walk early-terminates at `2 * max_results` candidates — patterns that match many tens of thousands of files will return a stable but partial set")
                example("20")
                example("200")
            }
        }
        verdict {
            keep(
                "Foundational discovery primitive. The combination of (a) hard-coded skip of build/VCS dirs, (b) PathValidator gating, (c) mtime-DESC sort, and (d) no approval gate (read-only) makes it 3-5x cheaper per call than the run_command find equivalent, and the LLM uses it on most non-trivial tasks. Removing this would force every discovery task into run_command — which is approval-gated and pollutes the chat with build-output noise.",
                VerdictSeverity.STRONG,
            )
        }
        related("search_code", Relationship.ALTERNATIVE, "Use instead when matching on file CONTENTS (regex on what's inside) rather than file NAMES.")
        related("read_file", Relationship.COMPOSE_WITH, "Glob first to discover candidate files, then read_file the most-recently-modified match.")
        related("find_definition", Relationship.ALTERNATIVE, "Use instead when looking for a symbol's source file — PSI knows where `class Foo` lives without a filename guess.")
        related("file_structure", Relationship.COMPOSE_WITH, "Glob to find a candidate file, then file_structure to see its top-level shape before reading.")
        downside("Skip-list is hard-coded — there is no way to opt-in to scanning `.gradle/` or `node_modules/` even when the user explicitly wants to (e.g. inspecting a published artifact's pom). The agent must fall back to `run_command find` for those cases.")
        downside("`max_results * 2` early-termination means very-broad patterns (`**/*`) on large repos can return a non-deterministic 'first 100 we hit during walk' set rather than the globally-newest files. The mtime sort applies only to the over-collected candidates.")
        downside("Glob matching is case-sensitive on Linux/macOS and case-insensitive on Windows (NIO PathMatcher inherits filesystem semantics) — patterns that work on the developer's macOS may behave differently in CI on Linux when filenames differ in case.")
        downside("Symlinks are followed by `Files.walkFileTree` with default options, so a symlink loop inside the project can stall the walk. No cycle protection beyond the OS-level `FileSystemLoopException` (which is silently swallowed by `visitFileFailed`).")
        downside("`.gitignore` is not honoured — the skip-list is a fixed set of common build/VCS dirs, so generated/vendored files outside that list (e.g. `vendor/`, `target/generated-sources/` once the path crosses outside `target/`) will appear in matches.")
        downside("Returns mtime-DESC order rather than relevance/lexicographic — recent test churn can push a freshly-touched throwaway file to the top of an `**/*.kt` listing, hiding the canonical match.")
    }

    companion object {
        private const val DEFAULT_MAX_RESULTS = 50
        private val SKIP_DIRS = setOf(
            ".git", ".idea", "node_modules", "target", "build", ".gradle", ".worktrees", ".workflow",
            "out", "dist", ".svn", ".hg", "__pycache__", ".tox", ".mypy_cache",
            "api-debug"
        )
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val pattern = params["pattern"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'pattern' parameter required", "Error: missing pattern", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val path = params["path"]?.jsonPrimitive?.content
        val maxResults = try { params["max_results"]?.jsonPrimitive?.int } catch (_: Exception) { null } ?: DEFAULT_MAX_RESULTS

        val basePath = project.basePath
            ?: return ToolResult("Error: Project base path not available", "Error: no project path", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val searchRoot = if (path != null) {
            val (validatedPath, pathError) = PathValidator.resolveAndValidateForRead(path, basePath)
            if (pathError != null) return pathError
            Paths.get(validatedPath!!)
        } else {
            canonicalize(basePath)
        }

        if (!Files.isDirectory(searchRoot)) {
            return ToolResult("Error: directory not found: $path", "Error: directory not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val matcher = try {
            FileSystems.getDefault().getPathMatcher("glob:$pattern")
        } catch (e: Exception) {
            return ToolResult("Error: invalid glob pattern '$pattern': ${e.message}", "Error: invalid pattern", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val matches = mutableListOf<Pair<String, Long>>() // (relativePath, lastModified)
        val projectRoot = canonicalize(basePath)

        Files.walkFileTree(searchRoot, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir.fileName?.toString() in SKIP_DIRS) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Early termination: collect enough candidates for sorting, then stop
                if (matches.size >= maxResults * 2) return FileVisitResult.TERMINATE
                val relativeToSearch = searchRoot.relativize(file)
                val insideProject = file.startsWith(projectRoot)
                val relativeToProject = if (insideProject) projectRoot.relativize(file) else null
                if (matcher.matches(relativeToSearch) || (relativeToProject != null && matcher.matches(relativeToProject)) || matcher.matches(file.fileName)) {
                    val emitted = (relativeToProject ?: file).toString().replace("\\", "/")
                    matches.add(emitted to attrs.lastModifiedTime().toMillis())
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException?): FileVisitResult {
                return FileVisitResult.CONTINUE
            }
        })

        if (matches.isEmpty()) {
            return ToolResult("No files found matching: $pattern", "No matches", ToolResult.ERROR_TOKEN_ESTIMATE)
        }

        // Sort by modification time (newest first)
        matches.sortByDescending { it.second }

        // Apply max_results limit after sorting
        val limited = matches.take(maxResults)
        val content = limited.joinToString("\n") { it.first }
        val truncatedNote = if (matches.size > maxResults) "\n... (limited to $maxResults of ${matches.size} results)" else ""
        val fullContent = content + truncatedNote

        return ToolResult(
            content = fullContent,
            summary = "Found ${limited.size} files matching '$pattern'",
            tokenEstimate = TokenEstimator.estimate(fullContent)
        )
    }

    private fun canonicalize(path: String): Path = try {
        Paths.get(path).toRealPath()
    } catch (e: Exception) {
        log.warn("toRealPath failed for $path; falling back to non-canonical (inside-project relative paths may degrade)", e)
        Paths.get(path)
    }
}
