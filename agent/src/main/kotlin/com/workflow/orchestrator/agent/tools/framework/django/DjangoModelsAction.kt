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

private data class ModelEntry(
    val app: String,
    val name: String,
    val fields: List<String>
)

private val MODEL_CLASS_PATTERN = Regex(
    """^class\s+(\w+)\s*\([^)]*(?:Model|models\.Model)[^)]*\)""",
    RegexOption.MULTILINE
)
private val FIELD_PATTERN = Regex(
    """^\s{4}(\w+)\s*=\s*models\.(\w+Field[^(]*)""",
    RegexOption.MULTILINE
)

internal suspend fun executeModels(params: JsonObject, project: Project): ToolResult {
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
            val modelFiles = PythonFileScanner.scanPythonFiles(baseDir) {
                it.name == "models.py"
            }

            if (modelFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No models.py files found in project.",
                    "No model files found",
                    5
                )
            }

            val allModels = mutableListOf<ModelEntry>()
            for (modelFile in modelFiles) {
                parseModels(modelFile, basePath, allModels)
            }

            val filtered = if (filter != null) {
                allModels.filter { m ->
                    m.name.contains(filter, ignoreCase = true) ||
                        m.app.contains(filter, ignoreCase = true)
                }
            } else {
                allModels
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (filter != null) " matching '$filter'" else ""
                return@withContext ToolResult("No models found$filterDesc.", "No models", 5)
            }

            val content = buildString {
                appendLine("Django models (${filtered.size} total from ${modelFiles.size} file(s)):")
                appendLine()
                val byApp = filtered.groupBy { it.app }
                for ((app, models) in byApp.toSortedMap()) {
                    appendLine("[$app]")
                    for (model in models.sortedBy { it.name }) {
                        appendLine("  ${model.name}")
                        for (field in model.fields.take(10)) {
                            appendLine("    - $field")
                        }
                        if (model.fields.size > 10) appendLine("    ... (${model.fields.size - 10} more fields)")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} models across ${filtered.map { it.app }.distinct().size} app(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading models: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun parseModels(modelFile: File, basePath: String, results: MutableList<ModelEntry>) {
    val content = modelFile.readText()
    val relPath = modelFile.absolutePath.removePrefix(basePath).trimStart(File.separatorChar)
    val appName = modelFile.parentFile?.name ?: "unknown"

    for (match in MODEL_CLASS_PATTERN.findAll(content)) {
        val className = match.groupValues[1]
        if (className == "Meta" || className == "Admin") continue
        val classStart = match.range.first
        val classEnd = findClassEnd(content, classStart)
        val classBody = content.substring(classStart, classEnd)
        val fields = FIELD_PATTERN.findAll(classBody).map { fm ->
            "${fm.groupValues[1]}: ${fm.groupValues[2].trim()}"
        }.toList()
        results.add(ModelEntry(appName, className, fields))
    }
}

private fun findClassEnd(content: String, classStart: Int): Int {
    // Simple heuristic: find the next class definition at the same indent level
    val nextClass = Regex("""^class\s+\w+""", RegexOption.MULTILINE)
        .find(content, classStart + 1)
    return nextClass?.range?.first ?: content.length
}
