package com.workflow.orchestrator.agent.tools.framework.fastapi

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private data class DbModelEntry(
    val file: String,
    val name: String,
    val orm: String,
    val fields: List<String>
)

// SQLAlchemy declarative Base subclass: class User(Base):
private val SQLALCHEMY_MODEL_PATTERN = Regex(
    """^class\s+(\w+)\s*\([^)]*\bBase\b[^)]*\)""",
    RegexOption.MULTILINE
)
// Tortoise ORM: class User(Model):
private val TORTOISE_MODEL_PATTERN = Regex(
    """^class\s+(\w+)\s*\([^)]*\bModel\b[^)]*\)""",
    RegexOption.MULTILINE
)
// SQLAlchemy column: name = Column(String, ...)
private val SA_COLUMN_PATTERN = Regex(
    """^\s{4}(\w+)\s*=\s*(?:Column|mapped_column|relationship)\s*\((.+?)(?:\)|$)""",
    RegexOption.MULTILINE
)
// Tortoise field: name = fields.CharField(...)
private val TORTOISE_FIELD_PATTERN = Regex(
    """^\s{4}(\w+)\s*=\s*fields\.(\w+)\s*\(""",
    RegexOption.MULTILINE
)
// SQLAlchemy tablename
private val TABLENAME_PATTERN = Regex(
    """__tablename__\s*=\s*["'](\w+)["']"""
)

internal suspend fun executeDatabase(params: JsonObject, project: Project): ToolResult {
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
            val pyFiles = baseDir.walkTopDown()
                .filter { it.isFile && it.extension == "py" }
                .toList()

            if (pyFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No Python files found in project.",
                    "No Python files found",
                    5
                )
            }

            val models = mutableListOf<DbModelEntry>()
            for (pyFile in pyFiles) {
                parseDbModels(pyFile, basePath, models)
            }

            val filtered = if (modelFilter != null) {
                models.filter { it.name.contains(modelFilter, ignoreCase = true) }
            } else {
                models
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (modelFilter != null) " matching '$modelFilter'" else ""
                return@withContext ToolResult(
                    "No database models found$filterDesc.",
                    "No database models found",
                    5
                )
            }

            val content = buildString {
                appendLine("Database models (${filtered.size} total):")
                appendLine()
                val byFile = filtered.groupBy { it.file }
                for ((file, fileModels) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for (model in fileModels.sortedBy { it.name }) {
                        appendLine("  ${model.name} [${model.orm}]")
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
                summary = "${filtered.size} database model(s) across ${filtered.map { it.file }.distinct().size} file(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading database models: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun parseDbModels(pyFile: File, basePath: String, results: MutableList<DbModelEntry>) {
    val content = pyFile.readText()
    val relPath = pyFile.absolutePath.removePrefix(basePath).trimStart(File.separatorChar)

    // SQLAlchemy models
    for (match in SQLALCHEMY_MODEL_PATTERN.findAll(content)) {
        val className = match.groupValues[1]
        if (className == "Meta" || className == "Config") continue
        val classStart = match.range.first
        val classEnd = findDbClassEnd(content, classStart)
        val classBody = content.substring(classStart, classEnd)
        val fields = SA_COLUMN_PATTERN.findAll(classBody)
            .filter { fm -> !fm.groupValues[1].startsWith("_") }
            .map { fm -> "${fm.groupValues[1]}: ${fm.groupValues[2].trim()}" }
            .toList()
        results.add(DbModelEntry(relPath, className, "SQLAlchemy", fields))
    }

    // Tortoise models
    for (match in TORTOISE_MODEL_PATTERN.findAll(content)) {
        val className = match.groupValues[1]
        if (className == "Meta" || className == "Config") continue
        // Avoid matching SQLAlchemy Base subclass again
        val matchText = content.substring(match.range.first, minOf(match.range.last + 50, content.length))
        if (matchText.contains("Base")) continue
        val classStart = match.range.first
        val classEnd = findDbClassEnd(content, classStart)
        val classBody = content.substring(classStart, classEnd)
        val fields = TORTOISE_FIELD_PATTERN.findAll(classBody)
            .filter { fm -> !fm.groupValues[1].startsWith("_") }
            .map { fm -> "${fm.groupValues[1]}: fields.${fm.groupValues[2]}" }
            .toList()
        results.add(DbModelEntry(relPath, className, "Tortoise", fields))
    }
}

private fun findDbClassEnd(content: String, classStart: Int): Int {
    val nextClass = Regex("""^class\s+\w+""", RegexOption.MULTILINE)
        .find(content, classStart + 1)
    return nextClass?.range?.first ?: content.length
}
