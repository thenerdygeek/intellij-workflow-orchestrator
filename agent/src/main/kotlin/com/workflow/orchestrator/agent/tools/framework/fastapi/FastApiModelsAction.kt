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

private data class PydanticModelEntry(
    val file: String,
    val name: String,
    val fields: List<String>
)

private val PYDANTIC_MODEL_PATTERN = Regex(
    """^class\s+(\w+)\s*\([^)]*BaseModel[^)]*\)""",
    RegexOption.MULTILINE
)
private val FIELD_PATTERN = Regex(
    """^\s{4}(\w+)\s*:\s*(.+?)(?:\s*=.*)?$""",
    RegexOption.MULTILINE
)

internal suspend fun executeModels(params: JsonObject, project: Project): ToolResult {
    val modelFilter = params["model"]?.jsonPrimitive?.content
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

            val models = mutableListOf<PydanticModelEntry>()
            for (pyFile in pyFiles) {
                parsePydanticModels(pyFile, basePath, models)
            }

            val filtered = if (modelFilter != null) {
                models.filter { it.name.contains(modelFilter, ignoreCase = true) }
            } else {
                models
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (modelFilter != null) " matching '$modelFilter'" else ""
                return@withContext ToolResult(
                    "No Pydantic models found$filterDesc.",
                    "No Pydantic models found",
                    5
                )
            }

            val content = buildString {
                appendLine("Pydantic models (${filtered.size} total):")
                appendLine()
                val byFile = filtered.groupBy { it.file }
                for ((file, fileModels) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for (model in fileModels.sortedBy { it.name }) {
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
                summary = "${filtered.size} Pydantic models across ${filtered.map { it.file }.distinct().size} file(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading models: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun parsePydanticModels(pyFile: File, basePath: String, results: MutableList<PydanticModelEntry>) {
    val content = pyFile.readText()
    val relPath = pyFile.absolutePath.removePrefix(basePath).trimStart(File.separatorChar)

    for (match in PYDANTIC_MODEL_PATTERN.findAll(content)) {
        val className = match.groupValues[1]
        if (className == "Config") continue
        val classStart = match.range.first
        val classEnd = findClassEnd(content, classStart)
        val classBody = content.substring(classStart, classEnd)
        val fields = FIELD_PATTERN.findAll(classBody)
            .filter { fm ->
                val fieldName = fm.groupValues[1]
                // Skip class-level keywords and inner class definitions
                fieldName != "class" && fieldName != "model_config" && !fieldName.startsWith("_")
            }
            .map { fm -> "${fm.groupValues[1]}: ${fm.groupValues[2].trim()}" }
            .toList()
        results.add(PydanticModelEntry(relPath, className, fields))
    }
}

private fun findClassEnd(content: String, classStart: Int): Int {
    val nextClass = Regex("""^class\s+\w+""", RegexOption.MULTILINE)
        .find(content, classStart + 1)
    return nextClass?.range?.first ?: content.length
}
