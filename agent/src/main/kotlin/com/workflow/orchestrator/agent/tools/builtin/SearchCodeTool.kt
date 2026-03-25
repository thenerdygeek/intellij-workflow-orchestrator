package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class SearchCodeTool : AgentTool {
    override val name = "search_code"
    override val description = "Search for a pattern across project files. Supports three output modes: 'files' (paths only), 'content' (matching lines with context), 'count' (match counts per file)."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "pattern" to ParameterProperty(type = "string", description = "Search string or regex pattern"),
            "path" to ParameterProperty(type = "string", description = "File or directory to search. Defaults to project root."),
            "output_mode" to ParameterProperty(type = "string", description = "Output mode: 'files' (file paths only, default), 'content' (matching lines with context), 'count' (match counts per file)"),
            "file_type" to ParameterProperty(type = "string", description = "File extension filter (e.g., 'kt', 'java', 'xml'). Only search files with this extension."),
            "case_insensitive" to ParameterProperty(type = "boolean", description = "Case-insensitive search. Default: false."),
            "context_lines" to ParameterProperty(type = "integer", description = "Lines of context before and after each match (only for output_mode='content'). Default: 0."),
            "max_results" to ParameterProperty(type = "integer", description = "Maximum matches to return. Default: 50.")
        ),
        required = listOf("pattern")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    companion object {
        private const val DEFAULT_MAX_RESULTS = 50
        private val BINARY_EXTENSIONS = setOf(
            "jar", "class", "png", "jpg", "jpeg", "gif", "ico", "svg",
            "zip", "tar", "gz", "war", "ear", "so", "dll", "exe",
            "pdf", "woff", "woff2", "ttf", "eot"
        )
        private val SKIP_DIRS = setOf(
            ".git", ".idea", "node_modules", "target", "build", ".gradle", ".worktrees", ".workflow",
            "out", "dist", ".svn", ".hg", "__pycache__", ".tox", ".mypy_cache"
        )
    }

    data class SearchMatch(
        val relativePath: String,
        val lineNumber: Int,
        val lineContent: String,
        val contextBefore: List<String> = emptyList(),
        val contextAfter: List<String> = emptyList()
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
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
            val (validatedPath, pathError) = PathValidator.resolveAndValidate(searchPath, basePath)
            if (pathError != null) return pathError
            File(validatedPath!!)
        } else {
            File(basePath)
        }

        if (!searchRoot.exists() || !searchRoot.isDirectory) {
            return ToolResult("Error: Search path not found: $searchRoot", "Error: path not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val regexOpts = if (caseInsensitive) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val regex = try {
            Regex(pattern, regexOpts)
        } catch (_: Exception) {
            Regex(Regex.escape(pattern), regexOpts)
        }

        val collectContext = outputMode == "content" && contextLines > 0
        val matches = mutableListOf<SearchMatch>()
        searchFiles(searchRoot, basePath, regex, fileType, collectContext, contextLines, matches, maxResults)

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
        var prevPath: String? = null

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
            prevPath = match.relativePath
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

    private fun searchFiles(
        dir: File,
        basePath: String,
        regex: Regex,
        fileType: String?,
        collectContext: Boolean,
        contextLines: Int,
        matches: MutableList<SearchMatch>,
        maxResults: Int
    ) {
        if (Thread.currentThread().isInterrupted) return
        if (matches.size >= maxResults) return

        val files = dir.listFiles() ?: return

        for (file in files.sortedBy { it.name }) {
            if (matches.size >= maxResults) return
            if (Thread.currentThread().isInterrupted) return

            if (file.isDirectory) {
                if (file.name !in SKIP_DIRS) {
                    searchFiles(file, basePath, regex, fileType, collectContext, contextLines, matches, maxResults)
                }
                continue
            }

            if (file.extension.lowercase() in BINARY_EXTENSIONS) continue
            if (file.length() > 1_000_000) continue // Skip files > 1MB
            if (fileType != null && file.extension.lowercase() != fileType) continue

            try {
                val relativePath = file.absolutePath.removePrefix(basePath).removePrefix("/")
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
                // Skip unreadable files
            }
        }
    }
}
