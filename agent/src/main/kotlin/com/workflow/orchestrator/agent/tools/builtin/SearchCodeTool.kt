package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class SearchCodeTool : AgentTool {
    override val name = "search_code"
    override val description = "Search for a pattern across project files. Returns matching lines with file paths and line numbers."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "query" to ParameterProperty(type = "string", description = "Search string or regex pattern"),
            "scope" to ParameterProperty(type = "string", description = "Subdirectory to limit search. Optional, defaults to project root."),
            "max_results" to ParameterProperty(type = "integer", description = "Maximum number of matches to return. Optional, defaults to 50.")
        ),
        required = listOf("query")
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
            ".git", ".idea", "node_modules", "target", "build", ".gradle", ".worktrees"
        )
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'query' parameter required", "Error: missing query", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val scope = params["scope"]?.jsonPrimitive?.content
        val maxResults = params["max_results"]?.jsonPrimitive?.int ?: DEFAULT_MAX_RESULTS

        val basePath = project.basePath
            ?: return ToolResult("Error: Project base path not available", "Error: no project path", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val searchRoot = if (scope != null) {
            val (validatedScope, scopeError) = PathValidator.resolveAndValidate(scope, basePath)
            if (scopeError != null) return scopeError
            File(validatedScope!!)
        } else {
            File(basePath)
        }

        if (!searchRoot.exists() || !searchRoot.isDirectory) {
            return ToolResult("Error: Search scope not found: $searchRoot", "Error: scope not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val regex = try {
            Regex(query)
        } catch (_: Exception) {
            // Fall back to literal search if regex is invalid
            Regex(Regex.escape(query))
        }

        val matches = mutableListOf<String>()
        searchFiles(searchRoot, basePath, regex, matches, maxResults)

        if (matches.isEmpty()) {
            return ToolResult(
                "No matches found for: $query",
                "No matches for '$query'",
                ToolResult.ERROR_TOKEN_ESTIMATE
            )
        }

        val content = matches.joinToString("\n")
        val truncatedNote = if (matches.size >= maxResults) "\n... (results limited to $maxResults)" else ""
        val fullContent = content + truncatedNote

        return ToolResult(
            content = fullContent,
            summary = "Found ${matches.size} matches for '$query'",
            tokenEstimate = TokenEstimator.estimate(fullContent)
        )
    }

    private fun searchFiles(
        dir: File,
        basePath: String,
        regex: Regex,
        matches: MutableList<String>,
        maxResults: Int
    ) {
        val files = dir.listFiles() ?: return

        for (file in files.sortedBy { it.name }) {
            if (matches.size >= maxResults) return

            if (file.isDirectory) {
                if (file.name !in SKIP_DIRS) {
                    searchFiles(file, basePath, regex, matches, maxResults)
                }
                continue
            }

            if (file.extension.lowercase() in BINARY_EXTENSIONS) continue
            if (file.length() > 1_000_000) continue // Skip files > 1MB

            try {
                val lines = file.readLines(Charsets.UTF_8)
                val relativePath = file.absolutePath.removePrefix(basePath).removePrefix("/")
                for ((lineNum, line) in lines.withIndex()) {
                    if (matches.size >= maxResults) return
                    if (regex.containsMatchIn(line)) {
                        matches.add("$relativePath:${lineNum + 1}: ${line.trim()}")
                    }
                }
            } catch (_: Exception) {
                // Skip unreadable files
            }
        }
    }
}
