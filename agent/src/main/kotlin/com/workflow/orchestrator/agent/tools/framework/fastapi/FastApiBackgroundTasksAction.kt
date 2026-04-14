package com.workflow.orchestrator.agent.tools.framework.fastapi

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import com.workflow.orchestrator.agent.tools.framework.PythonFileScanner
import java.io.File

private data class BackgroundTaskEntry(
    val file: String,
    val function: String,
    val lineNumber: Int
)

private val BACKGROUND_TASKS_PARAM_PATTERN = Regex(
    """(?:async\s+)?def\s+(\w+)\s*\([^)]*BackgroundTasks[^)]*\)"""
)

internal suspend fun executeBackgroundTasks(params: JsonObject, project: Project): ToolResult {
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

            val entries = mutableListOf<BackgroundTaskEntry>()
            for (pyFile in pyFiles) {
                parseBackgroundTasks(pyFile, basePath, entries)
            }

            if (entries.isEmpty()) {
                return@withContext ToolResult(
                    "No functions using BackgroundTasks found in project.",
                    "No background tasks found",
                    5
                )
            }

            val content = buildString {
                appendLine("FastAPI background task handlers (${entries.size} found):")
                appendLine()
                val byFile = entries.groupBy { it.file }
                for ((file, fileEntries) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for (entry in fileEntries.sortedBy { it.lineNumber }) {
                        appendLine("  ${entry.function}()")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${entries.size} background task handler(s) across ${entries.map { it.file }.distinct().size} file(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading background tasks: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun parseBackgroundTasks(pyFile: File, basePath: String, results: MutableList<BackgroundTaskEntry>) {
    val content = pyFile.readText()
    val relPath = PythonFileScanner.relPath(pyFile, basePath)

    for (match in BACKGROUND_TASKS_PARAM_PATTERN.findAll(content)) {
        val funcName = match.groupValues[1]
        val lineNumber = content.substring(0, match.range.first).count { it == '\n' } + 1
        results.add(BackgroundTaskEntry(relPath, funcName, lineNumber))
    }
}
