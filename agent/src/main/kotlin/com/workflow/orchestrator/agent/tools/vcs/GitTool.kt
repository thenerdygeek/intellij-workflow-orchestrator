package com.workflow.orchestrator.agent.tools.vcs

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangeListManagerImpl
import com.intellij.openapi.vcs.changes.shelf.ShelveChangesManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.context.ToolOutputStore
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.PathValidator
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepositoryManager
import git4idea.util.GitFileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.*

/**
 * Consolidated Git/VCS meta-tool replacing 11 individual tools:
 * git_status, git_blame, git_diff, git_log, git_branches, git_show_file,
 * git_show_commit, git_stash_list, git_merge_base, git_file_history, changelist_shelve.
 *
 * Saves token budget per API call by collapsing all VCS operations into
 * a single tool definition with an `action` discriminator parameter.
 */
class GitTool : AgentTool {

    override val name = "git"

    override val description = """
Git operations — status, blame, diff, log, branches, file history, shelve.

Actions and their parameters:
- status() → Working tree status
- blame(path, start_line?, end_line?) → Line-by-line blame
- diff(path?, ref?, staged?) → Show diff (staged=true for staged changes)
- log(path?, ref?, max_count?, oneline?) → Commit log (default 20, max 50)
- branches(show_remote?, show_tags?) → List branches
- show_file(path, ref) → File content at git ref (local refs only)
- show_commit(commit, include_diff?) → Commit details (SHA, HEAD, HEAD~N, or local branch)
- stash_list() → List stashes
- merge_base(ref1, ref2) → Common ancestor
- file_history(path, max_count?) → File commit history (default 15, max 30)
- shelve(shelve_action, name?, comment?, shelf_index?) → Changelist shelving (shelve_action: list|list_shelves|create|shelve|unshelve)
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "status", "blame", "diff", "log", "branches", "show_file",
                    "show_commit", "stash_list", "merge_base", "file_history", "shelve"
                )
            ),
            // blame, diff, log, show_file, file_history — file/directory path
            "path" to ParameterProperty(
                type = "string",
                description = "File or directory path (relative to project root or absolute). " +
                    "Required for: blame, show_file, file_history. Optional for: diff, log."
            ),
            // diff, log, show_file, show_commit, merge_base — git ref
            "ref" to ParameterProperty(
                type = "string",
                description = "Git ref: branch name, tag, commit SHA, or HEAD~N. " +
                    "Required for: show_file, show_commit. Optional for: diff, log. " +
                    "Remote refs (origin/, upstream/) are rejected."
            ),
            // merge_base
            "ref1" to ParameterProperty(
                type = "string",
                description = "First ref for merge_base: local branch name, tag, or commit SHA."
            ),
            "ref2" to ParameterProperty(
                type = "string",
                description = "Second ref for merge_base: local branch name, tag, or commit SHA."
            ),
            // blame
            "start_line" to ParameterProperty(
                type = "integer",
                description = "Start line (1-based) for blame. Default: 1."
            ),
            "end_line" to ParameterProperty(
                type = "integer",
                description = "End line (1-based) for blame. Default: last line."
            ),
            // diff
            "staged" to ParameterProperty(
                type = "boolean",
                description = "If true, show only staged (cached) changes. Default: false. For diff action."
            ),
            // log
            "max_count" to ParameterProperty(
                type = "integer",
                description = "Maximum number of commits to show. For log (default 20, max 50) and file_history (default 15, max 30)."
            ),
            "oneline" to ParameterProperty(
                type = "boolean",
                description = "If true, show compact one-line log format. Default: false. For log action."
            ),
            // show_commit
            "commit" to ParameterProperty(
                type = "string",
                description = "Commit reference for show_commit: SHA, HEAD, HEAD~N, or local branch name."
            ),
            "include_diff" to ParameterProperty(
                type = "boolean",
                description = "If true, include the full diff in show_commit output. Default: false."
            ),
            // branches
            "show_remote" to ParameterProperty(
                type = "boolean",
                description = "Show remote branches. Default: true. For branches action."
            ),
            "show_tags" to ParameterProperty(
                type = "boolean",
                description = "Show tags. Default: false. For branches action."
            ),
            // shelve sub-action
            "shelve_action" to ParameterProperty(
                type = "string",
                description = "Sub-operation when action=shelve: list (changelists), list_shelves, create (changelist), shelve (current changes), unshelve.",
                enumValues = listOf("list", "list_shelves", "create", "shelve", "unshelve")
            ),
            "name" to ParameterProperty(
                type = "string",
                description = "Changelist name (for shelve action with shelve_action=create)."
            ),
            "comment" to ParameterProperty(
                type = "string",
                description = "Description (for shelve action with shelve_action=create or shelve_action=shelve)."
            ),
            "shelf_index" to ParameterProperty(
                type = "integer",
                description = "0-based index of shelf to unshelve (for shelve action with shelve_action=unshelve)."
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    companion object {
        private val REMOTE_REF_PATTERN = Regex("""(origin|upstream)/""")
        private const val DIFF_MAX_OUTPUT_CHARS = 30_000
        private const val LOG_MAX_OUTPUT_CHARS = 20_000
        private const val SHOW_COMMIT_MAX_OUTPUT_CHARS = 30_000
        private const val FILE_HISTORY_MAX_OUTPUT_CHARS = 15_000
        private const val SHOW_FILE_MAX_LINES = 2000
        private const val SHOW_FILE_MAX_CHARS = 50_000
        private const val LOG_DEFAULT_MAX_COUNT = 20
        private const val LOG_ABSOLUTE_MAX_COUNT = 50
        private const val FILE_HISTORY_DEFAULT_MAX_COUNT = 15
        private const val FILE_HISTORY_ABSOLUTE_MAX_COUNT = 30
        private const val MAX_BRANCHES = 100
        private const val MAX_TAGS = 50
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return when (action) {
            "status" -> executeStatus(params, project)
            "blame" -> executeBlame(params, project)
            "diff" -> executeDiff(params, project)
            "log" -> executeLog(params, project)
            "branches" -> executeBranches(params, project)
            "show_file" -> executeShowFile(params, project)
            "show_commit" -> executeShowCommit(params, project)
            "stash_list" -> executeStashList(params, project)
            "merge_base" -> executeMergeBase(params, project)
            "file_history" -> executeFileHistory(params, project)
            "shelve" -> executeShelve(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions: status, blame, diff, log, branches, show_file, show_commit, stash_list, merge_base, file_history, shelve",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    // ── status ──────────────────────────────────────────────────────────────

    private fun executeStatus(params: JsonObject, project: Project): ToolResult {
        return try {
            val repoManager = GitRepositoryManager.getInstance(project)
            val repo = repoManager.repositories.firstOrNull()
                ?: return ToolResult("No git repository found in project.", "No git repo", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            val branch = repo.currentBranch?.name ?: "DETACHED HEAD"
            val tracking = repo.currentBranch?.let {
                repo.getBranchTrackInfo(it.name)?.remoteBranch?.nameForRemoteOperations
            } ?: "none"

            val content = ReadAction.compute<String, Exception> {
                val clm = ChangeListManager.getInstance(project)
                val changes = clm.allChanges
                val untracked = clm.modifiedWithoutEditing

                buildString {
                    appendLine("Branch: $branch")
                    appendLine("Tracking: $tracking")
                    appendLine()

                    if (changes.isEmpty() && untracked.isEmpty()) {
                        appendLine("Working tree clean — no changes.")
                        return@buildString
                    }

                    if (changes.isNotEmpty()) {
                        appendLine("Changed files (${changes.size}):")
                        changes.take(50).forEach { change ->
                            val type = change.type.name.lowercase()
                            val filePath = change.virtualFile?.path
                                ?: change.afterRevision?.file?.path
                                ?: change.beforeRevision?.file?.path
                                ?: "unknown"
                            val relativePath = project.basePath?.let { base ->
                                if (filePath.startsWith(base)) filePath.removePrefix("$base/") else filePath
                            } ?: filePath
                            appendLine("  [$type] $relativePath")
                        }
                        if (changes.size > 50) {
                            appendLine("  ... and ${changes.size - 50} more")
                        }
                    }

                    if (untracked.isNotEmpty()) {
                        appendLine()
                        appendLine("Untracked/externally modified files (${untracked.size}):")
                        untracked.take(30).forEach { vf ->
                            val relativePath = project.basePath?.let { base ->
                                if (vf.path.startsWith(base)) vf.path.removePrefix("$base/") else vf.path
                            } ?: vf.path
                            appendLine("  $relativePath")
                        }
                        if (untracked.size > 30) {
                            appendLine("  ... and ${untracked.size - 30} more")
                        }
                    }
                }
            }

            ToolResult(
                content = content,
                summary = "Git status: $branch",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult("Error getting git status: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── blame ───────────────────────────────────────────────────────────────

    private suspend fun executeBlame(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' required for blame", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError

        val startLine = params["start_line"]?.jsonPrimitive?.int
        val endLine = params["end_line"]?.jsonPrimitive?.int

        val vf = LocalFileSystem.getInstance().findFileByIoFile(java.io.File(path!!))
            ?: return ToolResult("File not found: $path", "Not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        return try {
            withContext(Dispatchers.IO) {
                val vcsManager = ProjectLevelVcsManager.getInstance(project)
                val vcs = vcsManager.getVcsFor(vf)
                    ?: return@withContext ToolResult("File is not under version control.", "No VCS", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
                val annotationProvider = vcs.annotationProvider
                    ?: return@withContext ToolResult("No annotation/blame provider available.", "No blame", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

                val annotation = annotationProvider.annotate(vf)
                try {
                    val lineCount = annotation.lineCount
                    if (lineCount == 0) {
                        return@withContext ToolResult("File has no content to blame.", "Empty file", 5)
                    }

                    val start = ((startLine ?: 1) - 1).coerceIn(0, lineCount - 1)
                    val end = ((endLine ?: lineCount) - 1).coerceIn(start, lineCount - 1)
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd")

                    val content = buildString {
                        val relativePath = project.basePath?.let { base ->
                            if (path.startsWith(base)) path.removePrefix("$base/") else path
                        } ?: path
                        appendLine("Blame for $relativePath (lines ${start + 1}..${end + 1}):")
                        appendLine()

                        for (line in start..end) {
                            val rev = annotation.getLineRevisionNumber(line)?.asString()?.take(8) ?: "????????"
                            val tooltip = annotation.getToolTip(line)
                            val author = extractAuthorFromTooltip(tooltip).take(20).padEnd(20)
                            val date = annotation.getLineDate(line)?.let { dateFormat.format(it) } ?: "          "
                            appendLine("${(line + 1).toString().padStart(5)}  $rev  $author  $date")
                        }
                    }

                    ToolResult(
                        content = content,
                        summary = "Blame for ${vf.name} (${end - start + 1} lines)",
                        tokenEstimate = TokenEstimator.estimate(content)
                    )
                } finally {
                    @Suppress("DEPRECATION")
                    annotation.dispose()
                }
            }
        } catch (e: Exception) {
            ToolResult("Error running git blame: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun extractAuthorFromTooltip(tooltip: String?): String {
        if (tooltip.isNullOrBlank()) return "unknown"
        for (line in tooltip.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Author:", ignoreCase = true)) {
                return trimmed.removePrefix("Author:").removePrefix("author:").trim()
            }
        }
        return tooltip.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "unknown"
    }

    // ── diff ────────────────────────────────────────────────────────────────

    private suspend fun executeDiff(params: JsonObject, project: Project): ToolResult {
        val path = params["path"]?.jsonPrimitive?.content
        val staged = params["staged"]?.jsonPrimitive?.boolean ?: false
        val ref = params["ref"]?.jsonPrimitive?.content

        if (ref != null && REMOTE_REF_PATTERN.containsMatchIn(ref)) {
            return remoteRefBlocked()
        }

        return try {
            withContext(Dispatchers.IO) {
                val repoManager = GitRepositoryManager.getInstance(project)
                val repo = repoManager.repositories.firstOrNull()
                    ?: return@withContext ToolResult("No git repository found in project.", "No git repo", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

                val handler = GitLineHandler(project, repo.root, GitCommand.DIFF)

                if (staged) {
                    handler.addParameters("--cached")
                }

                if (ref != null) {
                    handler.addParameters(ref)
                }

                handler.addParameters("--stat-width=120")

                if (path != null) {
                    handler.addParameters("--")
                    handler.addParameters(path)
                }

                val result = Git.getInstance().runCommand(handler)
                val output = result.getOutputOrThrow()

                if (output.isBlank()) {
                    val scope = buildString {
                        if (staged) append("staged ")
                        append("diff")
                        if (ref != null) append(" against $ref")
                        if (path != null) append(" for $path")
                    }
                    return@withContext ToolResult("No differences found ($scope).", "No diff", 5)
                }

                val truncated = if (output.length > DIFF_MAX_OUTPUT_CHARS) {
                    ToolOutputStore.middleTruncate(output, DIFF_MAX_OUTPUT_CHARS)
                } else {
                    output
                }

                val summary = buildString {
                    append("Git diff")
                    if (staged) append(" (staged)")
                    if (ref != null) append(" vs $ref")
                    if (path != null) append(" — $path")
                }

                ToolResult(
                    content = truncated,
                    summary = summary,
                    tokenEstimate = TokenEstimator.estimate(truncated)
                )
            }
        } catch (e: Exception) {
            ToolResult("Error running git diff: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── log ─────────────────────────────────────────────────────────────────

    private suspend fun executeLog(params: JsonObject, project: Project): ToolResult {
        val maxCount = (params["max_count"]?.jsonPrimitive?.int ?: LOG_DEFAULT_MAX_COUNT)
            .coerceIn(1, LOG_ABSOLUTE_MAX_COUNT)
        val path = params["path"]?.jsonPrimitive?.content
        val ref = params["ref"]?.jsonPrimitive?.content
        val oneline = params["oneline"]?.jsonPrimitive?.boolean ?: false

        if (ref != null && REMOTE_REF_PATTERN.containsMatchIn(ref)) {
            return remoteRefBlocked()
        }

        return try {
            withContext(Dispatchers.IO) {
                val repoManager = GitRepositoryManager.getInstance(project)
                val repo = repoManager.repositories.firstOrNull()
                    ?: return@withContext ToolResult("No git repository found in project.", "No git repo", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

                val handler = GitLineHandler(project, repo.root, GitCommand.LOG)

                handler.addParameters("-n", maxCount.toString())

                if (oneline) {
                    handler.addParameters("--oneline")
                } else {
                    handler.addParameters("--format=%H%n%an <%ae>%n%ai%n%s%n%b%n---")
                }

                if (ref != null) {
                    handler.addParameters(ref)
                }

                if (path != null) {
                    handler.addParameters("--")
                    handler.addParameters(path)
                }

                val result = Git.getInstance().runCommand(handler)
                val output = result.getOutputOrThrow()

                if (output.isBlank()) {
                    val scope = buildString {
                        append("No commits found")
                        if (ref != null) append(" on $ref")
                        if (path != null) append(" for $path")
                    }
                    return@withContext ToolResult("$scope.", scope, 5)
                }

                val truncated = if (output.length > LOG_MAX_OUTPUT_CHARS) {
                    ToolOutputStore.middleTruncate(output, LOG_MAX_OUTPUT_CHARS)
                } else {
                    output
                }

                val summary = buildString {
                    append("Git log ($maxCount commits)")
                    if (ref != null) append(" on $ref")
                    if (path != null) append(" — $path")
                }

                ToolResult(
                    content = truncated,
                    summary = summary,
                    tokenEstimate = TokenEstimator.estimate(truncated)
                )
            }
        } catch (e: Exception) {
            ToolResult("Error running git log: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── branches ────────────────────────────────────────────────────────────

    private suspend fun executeBranches(params: JsonObject, project: Project): ToolResult {
        val showRemote = params["show_remote"]?.jsonPrimitive?.boolean ?: true
        val showTags = params["show_tags"]?.jsonPrimitive?.boolean ?: false

        return try {
            val repoManager = GitRepositoryManager.getInstance(project)
            val repo = repoManager.repositories.firstOrNull()
                ?: return ToolResult("No git repository found in project.", "No git repo", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            val currentBranch = repo.currentBranch?.name ?: "DETACHED HEAD"
            val branches = repo.branches

            val content = buildString {
                appendLine("Current branch: $currentBranch")
                appendLine()

                val localBranches = branches.localBranches.sortedBy { it.name }
                appendLine("Local branches (${localBranches.size}):")
                localBranches.take(MAX_BRANCHES).forEach { branch ->
                    val isCurrent = branch.name == currentBranch
                    val marker = if (isCurrent) "* " else "  "
                    val trackInfo = repo.getBranchTrackInfo(branch.name)
                    val tracking = if (trackInfo != null) {
                        " → ${trackInfo.remoteBranch.nameForRemoteOperations}"
                    } else ""
                    appendLine("$marker${branch.name}$tracking")
                }
                if (localBranches.size > MAX_BRANCHES) {
                    appendLine("  ... and ${localBranches.size - MAX_BRANCHES} more")
                }

                if (showRemote) {
                    val remoteBranches = branches.remoteBranches.sortedBy { it.name }
                    appendLine()
                    appendLine("Remote branches (${remoteBranches.size}):")
                    remoteBranches.take(MAX_BRANCHES).forEach { branch ->
                        appendLine("  ${branch.name}")
                    }
                    if (remoteBranches.size > MAX_BRANCHES) {
                        appendLine("  ... and ${remoteBranches.size - MAX_BRANCHES} more")
                    }
                }

                if (showTags) {
                    try {
                        val tagContent = withContext(Dispatchers.IO) {
                            val handler = GitLineHandler(project, repo.root, GitCommand.TAG)
                            handler.addParameters("--sort=-creatordate", "-l")
                            val result = Git.getInstance().runCommand(handler)
                            result.getOutputOrThrow()
                        }
                        val tagList = tagContent.lines().filter { it.isNotBlank() }
                        appendLine()
                        appendLine("Tags (${tagList.size}):")
                        tagList.take(MAX_TAGS).forEach { tag ->
                            appendLine("  ${tag.trim()}")
                        }
                        if (tagList.size > MAX_TAGS) {
                            appendLine("  ... and ${tagList.size - MAX_TAGS} more")
                        }
                    } catch (_: Exception) {
                        appendLine()
                        appendLine("Tags: (unable to list)")
                    }
                }
            }

            ToolResult(
                content = content,
                summary = "Branches: $currentBranch (${branches.localBranches.size} local)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult("Error listing branches: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── show_file ───────────────────────────────────────────────────────────

    private suspend fun executeShowFile(params: JsonObject, project: Project): ToolResult {
        val path = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' parameter is required for show_file.", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val ref = params["ref"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'ref' parameter is required for show_file.", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        if (REMOTE_REF_PATTERN.containsMatchIn(ref)) {
            return remoteRefBlocked()
        }

        return try {
            withContext(Dispatchers.IO) {
                val repoManager = GitRepositoryManager.getInstance(project)
                val repo = repoManager.repositories.firstOrNull()
                    ?: return@withContext ToolResult("No git repository found in project.", "No git repo", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

                val bytes = GitFileUtils.getFileContent(project, repo.root, ref, path)

                if (bytes.isEmpty()) {
                    return@withContext ToolResult("File '$path' is empty at ref '$ref'.", "Empty file at $ref", 5)
                }

                val fullContent = String(bytes, Charsets.UTF_8)
                val lines = fullContent.lines()

                val truncatedByLines = if (lines.size > SHOW_FILE_MAX_LINES) {
                    lines.take(SHOW_FILE_MAX_LINES).joinToString("\n") +
                        "\n\n[Truncated at $SHOW_FILE_MAX_LINES lines. Total: ${lines.size} lines.]"
                } else {
                    fullContent
                }

                val truncated = if (truncatedByLines.length > SHOW_FILE_MAX_CHARS) {
                    truncatedByLines.take(SHOW_FILE_MAX_CHARS) +
                        "\n\n[Truncated at $SHOW_FILE_MAX_CHARS characters. Total: ${fullContent.length} characters.]"
                } else {
                    truncatedByLines
                }

                val content = buildString {
                    appendLine("File: $path @ $ref (${lines.size} lines)")
                    appendLine()
                    append(truncated)
                }

                ToolResult(
                    content = content,
                    summary = "File $path at $ref (${lines.size} lines)",
                    tokenEstimate = TokenEstimator.estimate(content)
                )
            }
        } catch (e: Exception) {
            val msg = e.message ?: "unknown error"
            if (msg.contains("does not exist") || msg.contains("not found") || msg.contains("bad revision")) {
                ToolResult("File '$path' not found at ref '$ref'. Check the path and ref are correct.", "Not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            } else {
                ToolResult("Error reading file at ref: $msg", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }
        }
    }

    // ── show_commit ─────────────────────────────────────────────────────────

    private suspend fun executeShowCommit(params: JsonObject, project: Project): ToolResult {
        val commit = params["commit"]?.jsonPrimitive?.content
            ?: params["ref"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'commit' (or 'ref') parameter is required for show_commit.", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val includeDiff = params["include_diff"]?.jsonPrimitive?.boolean ?: false

        if (REMOTE_REF_PATTERN.containsMatchIn(commit)) {
            return remoteRefBlocked()
        }

        return try {
            withContext(Dispatchers.IO) {
                val repoManager = GitRepositoryManager.getInstance(project)
                val repo = repoManager.repositories.firstOrNull()
                    ?: return@withContext ToolResult("No git repository found in project.", "No git repo", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

                val handler = GitLineHandler(project, repo.root, GitCommand.SHOW)

                if (includeDiff) {
                    handler.addParameters("--stat", "--patch", commit)
                } else {
                    handler.addParameters("--stat", "--no-patch",
                        "--format=%H%n%an <%ae>%n%ai%n%s%n%n%b", commit)
                }

                val result = Git.getInstance().runCommand(handler)
                val output = result.getOutputOrThrow()

                if (output.isBlank()) {
                    return@withContext ToolResult("No commit found for '$commit'.", "Not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
                }

                val truncated = if (output.length > SHOW_COMMIT_MAX_OUTPUT_CHARS) {
                    ToolOutputStore.middleTruncate(output, SHOW_COMMIT_MAX_OUTPUT_CHARS)
                } else {
                    output
                }

                val summary = buildString {
                    append("Commit $commit")
                    if (includeDiff) append(" (with diff)")
                }

                ToolResult(
                    content = truncated,
                    summary = summary,
                    tokenEstimate = TokenEstimator.estimate(truncated)
                )
            }
        } catch (e: Exception) {
            ToolResult("Error showing commit: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── stash_list ──────────────────────────────────────────────────────────

    private suspend fun executeStashList(params: JsonObject, project: Project): ToolResult {
        return try {
            withContext(Dispatchers.IO) {
                val repoManager = GitRepositoryManager.getInstance(project)
                val repo = repoManager.repositories.firstOrNull()
                    ?: return@withContext ToolResult("No git repository found in project.", "No git repo", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

                val handler = GitLineHandler(project, repo.root, GitCommand.STASH)
                handler.addParameters("list", "--format=%gd|%ai|%gs")

                val result = Git.getInstance().runCommand(handler)
                val output = result.getOutputOrThrow()

                if (output.isBlank()) {
                    return@withContext ToolResult("No stashes found.", "No stashes", 5)
                }

                val content = buildString {
                    appendLine("Stashes:")
                    appendLine()
                    output.lines().filter { it.isNotBlank() }.forEach { line ->
                        val parts = line.split("|", limit = 3)
                        if (parts.size == 3) {
                            val index = parts[0]
                            val date = parts[1]
                            val message = parts[2]
                            appendLine("  $index  $date  $message")
                        } else {
                            appendLine("  $line")
                        }
                    }
                }

                val stashCount = output.lines().count { it.isNotBlank() }

                ToolResult(
                    content = content,
                    summary = "Stash list ($stashCount stashes)",
                    tokenEstimate = TokenEstimator.estimate(content)
                )
            }
        } catch (e: Exception) {
            ToolResult("Error listing stashes: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── merge_base ──────────────────────────────────────────────────────────

    private suspend fun executeMergeBase(params: JsonObject, project: Project): ToolResult {
        val ref1 = params["ref1"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'ref1' parameter is required for merge_base.", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val ref2 = params["ref2"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'ref2' parameter is required for merge_base.", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        if (REMOTE_REF_PATTERN.containsMatchIn(ref1) || REMOTE_REF_PATTERN.containsMatchIn(ref2)) {
            return ToolResult(
                "Error: Remote refs (origin/, upstream/) are not allowed. Use local branch names, tags, or commit SHAs instead.",
                "Error: remote ref blocked",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            withContext(Dispatchers.IO) {
                val repoManager = GitRepositoryManager.getInstance(project)
                val repo = repoManager.repositories.firstOrNull()
                    ?: return@withContext ToolResult("No git repository found in project.", "No git repo", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

                val mbHandler = GitLineHandler(project, repo.root, GitCommand.MERGE_BASE)
                mbHandler.addParameters(ref1, ref2)
                val mbResult = Git.getInstance().runCommand(mbHandler)
                val mergeBase = mbResult.getOutputOrThrow().trim()

                if (mergeBase.isBlank()) {
                    return@withContext ToolResult(
                        "No common ancestor found between '$ref1' and '$ref2'. They may be unrelated histories.",
                        "No merge base",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                }

                val content = buildString {
                    appendLine("Merge base of '$ref1' and '$ref2':")
                    appendLine("  Common ancestor: $mergeBase")
                    appendLine()

                    try {
                        val count1Handler = GitLineHandler(project, repo.root, GitCommand.REV_LIST)
                        count1Handler.addParameters("--count", "$mergeBase..$ref1")
                        val count1 = Git.getInstance().runCommand(count1Handler).getOutputOrThrow().trim()
                        appendLine("  $ref1: $count1 commits since divergence")
                    } catch (_: Exception) {
                        appendLine("  $ref1: (unable to count commits)")
                    }

                    try {
                        val count2Handler = GitLineHandler(project, repo.root, GitCommand.REV_LIST)
                        count2Handler.addParameters("--count", "$mergeBase..$ref2")
                        val count2 = Git.getInstance().runCommand(count2Handler).getOutputOrThrow().trim()
                        appendLine("  $ref2: $count2 commits since divergence")
                    } catch (_: Exception) {
                        appendLine("  $ref2: (unable to count commits)")
                    }
                }

                ToolResult(
                    content = content,
                    summary = "Merge base: ${mergeBase.take(8)} ($ref1 ∩ $ref2)",
                    tokenEstimate = TokenEstimator.estimate(content)
                )
            }
        } catch (e: Exception) {
            ToolResult("Error finding merge base: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── file_history ────────────────────────────────────────────────────────

    private suspend fun executeFileHistory(params: JsonObject, project: Project): ToolResult {
        val path = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' parameter is required for file_history.", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val maxCount = (params["max_count"]?.jsonPrimitive?.int ?: FILE_HISTORY_DEFAULT_MAX_COUNT)
            .coerceIn(1, FILE_HISTORY_ABSOLUTE_MAX_COUNT)

        return try {
            withContext(Dispatchers.IO) {
                val repoManager = GitRepositoryManager.getInstance(project)
                val repo = repoManager.repositories.firstOrNull()
                    ?: return@withContext ToolResult("No git repository found in project.", "No git repo", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

                val handler = GitLineHandler(project, repo.root, GitCommand.LOG)
                handler.addParameters(
                    "--follow",
                    "-n", maxCount.toString(),
                    "--format=%H%n%an <%ae>%n%ai%n%s%n---",
                    "--"
                )
                handler.addParameters(path)

                val result = Git.getInstance().runCommand(handler)
                val output = result.getOutputOrThrow()

                if (output.isBlank()) {
                    return@withContext ToolResult("No history found for '$path'. Check the file path is correct.", "No history", 5)
                }

                val header = "History for $path ($maxCount max):\n\n"

                val truncated = if (output.length + header.length > FILE_HISTORY_MAX_OUTPUT_CHARS) {
                    ToolOutputStore.middleTruncate(output, FILE_HISTORY_MAX_OUTPUT_CHARS - header.length)
                } else {
                    output
                }

                val content = header + truncated
                val commitCount = output.split("---").count { it.isNotBlank() }

                ToolResult(
                    content = content,
                    summary = "File history: $path ($commitCount commits)",
                    tokenEstimate = TokenEstimator.estimate(content)
                )
            }
        } catch (e: Exception) {
            ToolResult("Error getting file history: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ── shelve ──────────────────────────────────────────────────────────────

    private fun executeShelve(params: JsonObject, project: Project): ToolResult {
        val shelveAction = params["shelve_action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Error: 'shelve_action' parameter required when action=shelve. Must be one of: list, list_shelves, create, shelve, unshelve",
                "Error: missing shelve_action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return try {
            when (shelveAction) {
                "list" -> shelveListChangelists(project)
                "list_shelves" -> shelveListShelves(project)
                "create" -> shelveCreateChangelist(project, params)
                "shelve" -> shelveShelveChanges(project, params)
                "unshelve" -> shelveUnshelveChanges(project, params)
                else -> ToolResult(
                    "Invalid shelve_action '$shelveAction'. Must be one of: list, list_shelves, create, shelve, unshelve",
                    "Error",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }
        } catch (e: Exception) {
            ToolResult(
                "Error executing shelve ($shelveAction): ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    private fun shelveListChangelists(project: Project): ToolResult {
        val content = ReadAction.compute<String, Exception> {
            val clm = ChangeListManager.getInstance(project)
            val changeLists = clm.changeLists

            if (changeLists.isEmpty()) {
                return@compute "No changelists found."
            }

            buildString {
                appendLine("Changelists (${changeLists.size}):")
                changeLists.forEachIndexed { index, cl ->
                    val defaultMarker = if (cl.isDefault) "[DEFAULT] " else ""
                    val fileCount = cl.changes.size
                    val filesDesc = if (fileCount == 1) "1 file modified" else "$fileCount files modified"
                    appendLine("  ${index + 1}. $defaultMarker${cl.name} — $filesDesc")
                }
            }
        }

        return ToolResult(
            content = content,
            summary = "Listed changelists",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun shelveListShelves(project: Project): ToolResult {
        val shelveManager = ShelveChangesManager.getInstance(project)
        val shelves = shelveManager.shelvedChangeLists

        if (shelves.isEmpty()) {
            return ToolResult("No shelved changes found.", "No shelves", 5)
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val content = buildString {
            appendLine("Shelved changes (${shelves.size}):")
            shelves.forEachIndexed { index, shelf ->
                val dateStr = shelf.date?.let { dateFormat.format(it) } ?: "unknown date"
                val description = shelf.description?.takeIf { it.isNotBlank() } ?: "untitled"
                @Suppress("DEPRECATION")
                val fileCount = shelf.getChanges(project)?.size ?: 0
                val filesDesc = if (fileCount == 1) "1 file" else "$fileCount files"
                appendLine("  $index. $description ($dateStr) — $filesDesc")
            }
        }

        return ToolResult(
            content = content,
            summary = "Listed ${shelves.size} shelved changelists",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun shelveCreateChangelist(project: Project, params: JsonObject): ToolResult {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Missing required parameter: name (for shelve_action=create)",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val comment = params["comment"]?.jsonPrimitive?.contentOrNull ?: ""

        val clm = ChangeListManager.getInstance(project) as ChangeListManagerImpl
        val newList = clm.addChangeList(name, comment)

        val content = "Created changelist '${newList.name}'." +
            if (comment.isNotBlank()) " Comment: $comment" else ""

        return ToolResult(
            content = content,
            summary = "Created changelist '$name'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun shelveShelveChanges(project: Project, params: JsonObject): ToolResult {
        val clm = ChangeListManager.getInstance(project)
        val allChanges = clm.allChanges.toList()

        if (allChanges.isEmpty()) {
            return ToolResult("No changes to shelve.", "No changes", 5)
        }

        val comment = params["comment"]?.jsonPrimitive?.contentOrNull ?: "Shelved changes"

        val shelveManager = ShelveChangesManager.getInstance(project)
        val shelvedList = shelveManager.shelveChanges(allChanges, comment, true)

        val content = buildString {
            appendLine("Shelved ${allChanges.size} file(s) as '${shelvedList.description ?: comment}'.")
            appendLine("Working tree has been reverted for shelved files.")
        }

        return ToolResult(
            content = content,
            summary = "Shelved ${allChanges.size} files",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    private fun shelveUnshelveChanges(project: Project, params: JsonObject): ToolResult {
        val shelfIndex = params["shelf_index"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult(
                "Missing required parameter: shelf_index (for shelve_action=unshelve). Use action=shelve, shelve_action=list_shelves to see available indices.",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val shelveManager = ShelveChangesManager.getInstance(project)
        val shelves = shelveManager.shelvedChangeLists

        if (shelfIndex < 0 || shelfIndex >= shelves.size) {
            return ToolResult(
                "Invalid shelf_index $shelfIndex. Available range: 0..${shelves.size - 1} (${shelves.size} shelves).",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val shelf = shelves[shelfIndex]
        val clm = ChangeListManager.getInstance(project)
        val targetChangeList = clm.defaultChangeList

        shelveManager.unshelveChangeList(shelf, null, null, targetChangeList, true)

        val description = shelf.description?.takeIf { it.isNotBlank() } ?: "untitled"
        val content = "Unshelved '$description' into changelist '${targetChangeList.name}'."

        return ToolResult(
            content = content,
            summary = "Unshelved '$description'",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun remoteRefBlocked(): ToolResult = ToolResult(
        "Error: Remote refs (origin/, upstream/) are not allowed. Use a local branch, tag, or commit SHA instead.",
        "Error: remote ref blocked",
        ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )
}
