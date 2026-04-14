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

private val SETTING_PATTERN = Regex(
    """^([A-Z][A-Z0-9_]+)\s*=\s*(.+)""",
    RegexOption.MULTILINE
)

internal suspend fun executeSettings(params: JsonObject, project: Project): ToolResult {
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
            val settingsFiles = PythonFileScanner.scanPythonFiles(baseDir) {
                it.name.startsWith("settings") && it.extension == "py"
            }

            if (settingsFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No settings*.py files found in project.",
                    "No settings files found",
                    5
                )
            }

            val content = buildString {
                appendLine("Django settings (from ${settingsFiles.size} file(s)):")
                appendLine()

                for (settingsFile in settingsFiles.sortedBy { it.name }) {
                    val relPath = PythonFileScanner.relPath(settingsFile, basePath)
                    val fileContent = settingsFile.readText()
                    val settings = SETTING_PATTERN.findAll(fileContent)
                        .map { it.groupValues[1] to it.groupValues[2].trim().take(120) }
                        .filter { (key, _) ->
                            filter == null || key.contains(filter, ignoreCase = true)
                        }
                        .toList()

                    if (settings.isNotEmpty()) {
                        appendLine("[$relPath]")
                        for ((key, value) in settings) {
                            appendLine("  $key = ${PythonFileScanner.redactIfSensitive(key, value)}")
                        }
                        appendLine()
                    }
                }
            }

            if (content.lines().count { it.isNotBlank() } <= 2) {
                val filterDesc = if (filter != null) " matching '$filter'" else ""
                return@withContext ToolResult("No settings found$filterDesc.", "No settings", 5)
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "Settings from ${settingsFiles.size} file(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading settings: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}
