package com.workflow.orchestrator.agent.tools.framework.fastapi

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.workflow.orchestrator.agent.tools.framework.PythonFileScanner
import java.io.File

private data class ConfigEntry(
    val file: String,
    val name: String,
    val fields: List<String>
)

private val BASE_SETTINGS_PATTERN = Regex(
    """^class\s+(\w+)\s*\([^)]*BaseSettings[^)]*\)""",
    RegexOption.MULTILINE
)
private val CONFIG_FIELD_PATTERN = Regex(
    """^\s{4}(\w+)\s*:\s*(.+?)(?:\s*=.*)?$""",
    RegexOption.MULTILINE
)

internal suspend fun executeConfig(params: JsonObject, project: Project): ToolResult {
    val classNameFilter = params["class_name"]?.jsonPrimitive?.content
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

            val configs = mutableListOf<ConfigEntry>()
            for (pyFile in pyFiles) {
                parseConfigs(pyFile, basePath, configs)
            }

            val filtered = if (classNameFilter != null) {
                configs.filter { it.name.contains(classNameFilter, ignoreCase = true) }
            } else {
                configs
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (classNameFilter != null) " matching '$classNameFilter'" else ""
                return@withContext ToolResult(
                    "No BaseSettings classes found$filterDesc.",
                    "No config classes found",
                    5
                )
            }

            val content = buildString {
                appendLine("FastAPI configuration classes (${filtered.size} BaseSettings subclass(es)):")
                appendLine()
                val byFile = filtered.groupBy { it.file }
                for ((file, fileConfigs) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for (config in fileConfigs.sortedBy { it.name }) {
                        appendLine("  ${config.name}")
                        for (field in config.fields.take(10)) {
                            appendLine("    - $field")
                        }
                        if (config.fields.size > 10) appendLine("    ... (${config.fields.size - 10} more fields)")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} config class(es) across ${filtered.map { it.file }.distinct().size} file(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading config: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun parseConfigs(pyFile: File, basePath: String, results: MutableList<ConfigEntry>) {
    val content = pyFile.readText()
    val relPath = pyFile.absolutePath.removePrefix(basePath).trimStart(File.separatorChar)

    for (match in BASE_SETTINGS_PATTERN.findAll(content)) {
        val className = match.groupValues[1]
        if (className == "Config") continue
        val classStart = match.range.first
        val classEnd = findConfigClassEnd(content, classStart)
        val classBody = content.substring(classStart, classEnd)
        val fields = CONFIG_FIELD_PATTERN.findAll(classBody)
            .filter { fm ->
                val name = fm.groupValues[1]
                name != "class" && name != "model_config" && !name.startsWith("_")
            }
            .map { fm ->
                val fieldName = fm.groupValues[1]
                val fieldType = PythonFileScanner.redactIfSensitive(fieldName, fm.groupValues[2].trim())
                "$fieldName: $fieldType"
            }
            .toList()
        results.add(ConfigEntry(relPath, className, fields))
    }
}

private fun findConfigClassEnd(content: String, classStart: Int): Int {
    val nextClass = Regex("""^class\s+\w+""", RegexOption.MULTILINE)
        .find(content, classStart + 1)
    return nextClass?.range?.first ?: content.length
}
