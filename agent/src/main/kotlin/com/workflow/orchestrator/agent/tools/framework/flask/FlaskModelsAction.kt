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

private data class FlaskModelEntry(
    val file: String,
    val name: String,
    val columns: List<String>
)

private val MODEL_CLASS_PATTERN = Regex(
    """^class\s+(\w+)\s*\([^)]*(?:db\.Model|Model)[^)]*\)""",
    RegexOption.MULTILINE
)
private val COLUMN_PATTERN = Regex(
    """^\s{4}(\w+)\s*=\s*db\.(Column|relationship|backref)\s*\(([^)]*)\)""",
    RegexOption.MULTILINE
)

/**
 * Regex that detects any .py file that likely defines SQLAlchemy model classes.
 * We check for the presence of at least one class extending db.Model, Model, or
 * BaseModel (imported from flask_sqlalchemy or sqlalchemy) rather than relying on
 * the filename.  This allows split-model projects (user_model.py, orders/schema.py,
 * auth/models/user.py, etc.) to be discovered correctly.
 */
private val FILE_CONTAINS_MODEL_PATTERN = Regex(
    """class\s+\w+\s*\([^)]*(?:db\.Model|(?<![.\w])Model(?![.\w])|BaseModel)[^)]*\)""",
    RegexOption.MULTILINE
)

internal suspend fun executeModels(params: JsonObject, project: Project): ToolResult {
    val filter = params["model"]?.jsonPrimitive?.content
        ?: params["filter"]?.jsonPrimitive?.content
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
            // Scan ALL .py files and keep only those that contain at least one
            // db.Model / Model / BaseModel class definition — this way split-model
            // projects (user_model.py, orders/schema.py, etc.) are found even when
            // there is no file literally named models.py.
            val modelFiles = PythonFileScanner.scanPythonFiles(baseDir) { file ->
                file.extension == "py" &&
                    runCatching { FILE_CONTAINS_MODEL_PATTERN.containsMatchIn(file.readText()) }
                        .getOrDefault(false)
            }

            if (modelFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No SQLAlchemy model files found in project.",
                    "No model files found",
                    5
                )
            }

            val allModels = mutableListOf<FlaskModelEntry>()
            for (modelFile in modelFiles) {
                parseFlaskModels(modelFile, basePath, allModels)
            }

            val filtered = if (filter != null) {
                allModels.filter { m ->
                    m.name.contains(filter, ignoreCase = true) ||
                        m.file.contains(filter, ignoreCase = true)
                }
            } else {
                allModels
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (filter != null) " matching '$filter'" else ""
                return@withContext ToolResult("No SQLAlchemy models found$filterDesc.", "No models", 5)
            }

            val content = buildString {
                appendLine("Flask-SQLAlchemy models (${filtered.size} total from ${modelFiles.size} file(s)):")
                appendLine()
                val byFile = filtered.groupBy { it.file }
                for ((file, models) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for (model in models.sortedBy { it.name }) {
                        appendLine("  ${model.name}(db.Model)")
                        for (col in model.columns.take(10)) {
                            appendLine("    - $col")
                        }
                        if (model.columns.size > 10) appendLine("    ... (${model.columns.size - 10} more)")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} SQLAlchemy models across ${filtered.map { it.file }.distinct().size} file(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading models: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun parseFlaskModels(modelFile: File, basePath: String, results: MutableList<FlaskModelEntry>) {
    val content = modelFile.readText()
    val relPath = PythonFileScanner.relPath(modelFile, basePath)

    for (match in MODEL_CLASS_PATTERN.findAll(content)) {
        val className = match.groupValues[1]
        if (className == "Meta") continue
        val classStart = match.range.first
        val classEnd = PythonFileScanner.findClassEnd(content, classStart)
        val classBody = content.substring(classStart, classEnd)
        val columns = COLUMN_PATTERN.findAll(classBody).map { cm ->
            val colName = cm.groupValues[1]
            val colType = cm.groupValues[2]
            val args = cm.groupValues[3].trim().take(80)
            "$colName: $colType($args)"
        }.toList()
        results.add(FlaskModelEntry(relPath, className, columns))
    }
}

