package com.workflow.orchestrator.agent.tools.framework.fastapi

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import com.workflow.orchestrator.agent.tools.framework.PythonFileScanner
import java.io.File

private data class MiddlewareEntry(
    val file: String,
    val middlewareClass: String,
    val kwargs: String,
    val lineNumber: Int
)

private val ADD_MIDDLEWARE_PATTERN = Regex(
    """(\w+)\.add_middleware\s*\(\s*(\w+)(?:\s*,\s*(.+))?\s*\)"""
)

internal suspend fun executeMiddleware(params: JsonObject, project: Project): ToolResult {
    val basePath = project.basePath
        ?: return ToolResult(
            "Error: project base path not available",
            "Error: missing base path",
            ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )

    return try {
        withContext(Dispatchers.IO) {
            val baseDir = File(basePath)
            val pyFiles = PythonFileScanner.scanAllPyFiles(baseDir)

            if (pyFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No Python files found in project.",
                    "No Python files found",
                    5
                )
            }

            val middlewares = mutableListOf<MiddlewareEntry>()
            for (pyFile in pyFiles) {
                parseMiddleware(pyFile, basePath, middlewares)
            }

            if (middlewares.isEmpty()) {
                return@withContext ToolResult(
                    "No add_middleware() calls found in project.",
                    "No middleware found",
                    5
                )
            }

            val content = buildString {
                appendLine("FastAPI middleware (${middlewares.size} registration(s)):")
                appendLine()
                val byFile = middlewares.groupBy { it.file }
                for ((file, entries) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for ((idx, entry) in entries.sortedBy { it.lineNumber }.withIndex()) {
                        val kwargsStr = if (entry.kwargs.isNotBlank()) " (${entry.kwargs})" else ""
                        appendLine("  ${idx + 1}. ${entry.middlewareClass}$kwargsStr")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${middlewares.size} middleware registration(s) across ${middlewares.map { it.file }.distinct().size} file(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading middleware: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun parseMiddleware(pyFile: File, basePath: String, results: MutableList<MiddlewareEntry>) {
    val content = pyFile.readText()
    val relPath = PythonFileScanner.relPath(pyFile, basePath)
    val lines = content.lines()

    for ((index, line) in lines.withIndex()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#")) continue

        val match = ADD_MIDDLEWARE_PATTERN.find(trimmed) ?: continue
        val middlewareClass = match.groupValues[2]
        val kwargs = match.groupValues[3].trim()
        results.add(MiddlewareEntry(relPath, middlewareClass, kwargs, index + 1))
    }
}
