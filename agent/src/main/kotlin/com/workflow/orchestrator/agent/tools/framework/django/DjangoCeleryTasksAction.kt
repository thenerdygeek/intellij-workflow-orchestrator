package com.workflow.orchestrator.agent.tools.framework.django

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.workflow.orchestrator.agent.tools.framework.PythonFileScanner
import java.io.File

private data class CeleryTaskEntry(
    val file: String,
    val name: String,
    val options: String?
)

// Matches @shared_task or @app.task decorators followed by a def
private val SHARED_TASK_PATTERN = Regex(
    """@shared_task(?:\([^)]*\))?\s*\ndef\s+(\w+)""",
    RegexOption.MULTILINE
)
private val APP_TASK_PATTERN = Regex(
    """@\w+\.task(?:\([^)]*\))?\s*\ndef\s+(\w+)""",
    RegexOption.MULTILINE
)
private val TASK_DECORATOR_OPTIONS = Regex(
    """@(?:shared_task|[\w.]+\.task)\(([^)]*)\)"""
)

internal suspend fun executeCeleryTasks(params: JsonObject, project: Project): ToolResult {
    val filter = params["filter"]?.jsonPrimitive?.content
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
            val taskFiles = PythonFileScanner.scanPythonFiles(baseDir) {
                it.extension == "py" && it.name.contains("task")
            }

            if (taskFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No task files found in project (looked for *task*.py files).",
                    "No task files found",
                    5
                )
            }

            val allTasks = mutableListOf<CeleryTaskEntry>()

            for (taskFile in taskFiles) {
                val content = taskFile.readText()
                if (!content.contains("@shared_task") && !content.contains(".task")) continue
                val relPath = PythonFileScanner.relPath(taskFile, basePath)

                SHARED_TASK_PATTERN.findAll(content).forEach { match ->
                    val name = match.groupValues[1]
                    val optMatch = TASK_DECORATOR_OPTIONS.find(match.value)
                    val opts = optMatch?.groupValues?.get(1)?.trim()?.ifBlank { null }
                    allTasks.add(CeleryTaskEntry(relPath, name, opts))
                }
                APP_TASK_PATTERN.findAll(content).forEach { match ->
                    val name = match.groupValues[1]
                    if (allTasks.none { it.file == relPath && it.name == name }) {
                        val optMatch = TASK_DECORATOR_OPTIONS.find(match.value)
                        val opts = optMatch?.groupValues?.get(1)?.trim()?.ifBlank { null }
                        allTasks.add(CeleryTaskEntry(relPath, name, opts))
                    }
                }
            }

            val filtered = if (filter != null) {
                allTasks.filter { t ->
                    t.name.contains(filter, ignoreCase = true) ||
                        t.file.contains(filter, ignoreCase = true)
                }
            } else {
                allTasks
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (filter != null) " matching '$filter'" else ""
                return@withContext ToolResult("No Celery tasks found$filterDesc.", "No tasks", 5)
            }

            val content = buildString {
                appendLine("Celery tasks (${filtered.size} total from ${taskFiles.size} file(s)):")
                appendLine()
                val byFile = filtered.groupBy { it.file }
                for ((file, tasks) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for (task in tasks.sortedBy { it.name }) {
                        val optsStr = if (task.options != null) " [${task.options}]" else ""
                        appendLine("  ${task.name}$optsStr")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} Celery tasks",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading Celery tasks: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}
