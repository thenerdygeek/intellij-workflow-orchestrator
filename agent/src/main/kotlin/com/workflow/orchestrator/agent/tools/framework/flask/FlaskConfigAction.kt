package com.workflow.orchestrator.agent.tools.framework.flask

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.workflow.orchestrator.agent.tools.framework.PythonFileScanner
import java.io.File

private val CONFIG_CLASS_PATTERN = Regex(
    """^class\s+(\w*Config\w*)\s*(?:\([^)]*\))?\s*:""",
    RegexOption.MULTILINE
)
private val CONFIG_KEY_PATTERN = Regex(
    """^\s{4}([A-Z][A-Z0-9_]+)\s*=\s*(.+)""",
    RegexOption.MULTILINE
)
private val FROM_OBJECT_PATTERN = Regex(
    """(?:app|config)\.(?:from_object|from_pyfile|from_envvar)\s*\(\s*["']?([^"')]+)["']?\s*\)"""
)
private val TOP_LEVEL_CONFIG_PATTERN = Regex(
    """^([A-Z][A-Z0-9_]+)\s*=\s*(.+)""",
    RegexOption.MULTILINE
)

internal suspend fun executeConfig(params: JsonObject, project: Project): ToolResult {
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

            val configFiles = PythonFileScanner.scanPythonFiles(baseDir) { file ->
                file.extension == "py" && (
                    file.name == "config.py" ||
                        file.name == "settings.py" ||
                        file.name == "default_settings.py" ||
                        (file.parentFile?.name == "config" && file.extension == "py")
                    )
            }

            val allPyFiles = PythonFileScanner.scanAllPyFiles(baseDir)

            val fromObjectRefs = mutableListOf<Pair<String, String>>()
            for (pyFile in allPyFiles) {
                val content = pyFile.readText()
                val relPath = PythonFileScanner.relPath(pyFile, basePath)
                for (match in FROM_OBJECT_PATTERN.findAll(content)) {
                    fromObjectRefs.add(relPath to match.groupValues[1])
                }
            }

            if (configFiles.isEmpty() && fromObjectRefs.isEmpty()) {
                return@withContext ToolResult(
                    "No config.py, settings.py, or config/ directory found.",
                    "No config files found",
                    5
                )
            }

            val content = buildString {
                if (fromObjectRefs.isNotEmpty()) {
                    appendLine("Config loading references:")
                    for ((file, target) in fromObjectRefs) {
                        appendLine("  $file -> from_object('$target')")
                    }
                    appendLine()
                }

                for (configFile in configFiles.sortedBy { it.name }) {
                    val relPath = PythonFileScanner.relPath(configFile, basePath)
                    val fileContent = configFile.readText()

                    val classes = CONFIG_CLASS_PATTERN.findAll(fileContent).toList()
                    if (classes.isNotEmpty()) {
                        appendLine("[$relPath]")
                        for (classMatch in classes) {
                            val className = classMatch.groupValues[1]
                            val classStart = classMatch.range.first
                            val classEnd = PythonFileScanner.findClassEnd(fileContent, classStart)
                            val classBody = fileContent.substring(classStart, classEnd)

                            appendLine("  class $className:")
                            val keys = CONFIG_KEY_PATTERN.findAll(classBody)
                                .filter { matchResult ->
                                    filter == null || matchResult.groupValues[1].contains(filter, ignoreCase = true)
                                }
                                .toList()
                            for (km in keys) {
                                val key = km.groupValues[1]
                                val value = PythonFileScanner.redactIfSensitive(key, km.groupValues[2].trim().take(120))
                                appendLine("    $key = $value")
                            }
                        }
                        appendLine()
                    } else {
                        val settings = TOP_LEVEL_CONFIG_PATTERN.findAll(fileContent)
                            .filter { m ->
                                filter == null || m.groupValues[1].contains(filter, ignoreCase = true)
                            }
                            .toList()
                        if (settings.isNotEmpty()) {
                            appendLine("[$relPath]")
                            for (m in settings) {
                                val key = m.groupValues[1]
                                val value = PythonFileScanner.redactIfSensitive(key, m.groupValues[2].trim().take(120))
                                appendLine("  $key = $value")
                            }
                            appendLine()
                        }
                    }
                }
            }

            if (content.lines().count { it.isNotBlank() } <= 1) {
                val filterDesc = if (filter != null) " matching '$filter'" else ""
                return@withContext ToolResult("No config entries found$filterDesc.", "No config", 5)
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "Config from ${configFiles.size} file(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading config: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

