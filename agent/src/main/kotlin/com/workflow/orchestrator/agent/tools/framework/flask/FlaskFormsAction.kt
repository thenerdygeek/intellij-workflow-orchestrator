package com.workflow.orchestrator.agent.tools.framework.flask

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private data class FlaskFormEntry(
    val file: String,
    val name: String,
    val baseClass: String,
    val fields: List<String>
)

private val FORM_CLASS_PATTERN = Regex(
    """^class\s+(\w+)\s*\([^)]*(?:FlaskForm|Form)[^)]*\)""",
    RegexOption.MULTILINE
)
private val FIELD_PATTERN = Regex(
    """^\s{4}(\w+)\s*=\s*(\w+Field|SubmitField|SelectField|BooleanField|TextAreaField|PasswordField|HiddenField|RadioField|FileField|MultipleFileField|DecimalField|IntegerField|FloatField|DateField|DateTimeField|DateTimeLocalField|SelectMultipleField|FieldList|FormField)\s*\(""",
    RegexOption.MULTILINE
)

internal suspend fun executeForms(params: JsonObject, project: Project): ToolResult {
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
            val formFiles = baseDir.walkTopDown()
                .filter { it.isFile && (it.name == "forms.py" || it.name == "form.py") }
                .toList()

            if (formFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No forms.py files found in project.",
                    "No form files found",
                    5
                )
            }

            val allForms = mutableListOf<FlaskFormEntry>()

            for (formFile in formFiles) {
                val content = formFile.readText()
                val relPath = formFile.absolutePath.removePrefix(basePath).trimStart(File.separatorChar)

                for (match in FORM_CLASS_PATTERN.findAll(content)) {
                    val name = match.groupValues[1]
                    val baseClass = if (match.value.contains("FlaskForm")) "FlaskForm" else "Form"
                    val classStart = match.range.first
                    val classEnd = findFormClassEnd(content, classStart)
                    val classBody = content.substring(classStart, classEnd)

                    val fields = FIELD_PATTERN.findAll(classBody).map { fm ->
                        "${fm.groupValues[1]}: ${fm.groupValues[2]}"
                    }.toList()

                    allForms.add(FlaskFormEntry(relPath, name, baseClass, fields))
                }
            }

            val filtered = if (filter != null) {
                allForms.filter { f ->
                    f.name.contains(filter, ignoreCase = true) ||
                        f.file.contains(filter, ignoreCase = true)
                }
            } else {
                allForms
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (filter != null) " matching '$filter'" else ""
                return@withContext ToolResult("No Flask-WTF forms found$filterDesc.", "No forms", 5)
            }

            val content = buildString {
                appendLine("Flask-WTF forms (${filtered.size} total):")
                appendLine()
                val byFile = filtered.groupBy { it.file }
                for ((file, forms) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for (form in forms.sortedBy { it.name }) {
                        appendLine("  ${form.name}(${form.baseClass})")
                        for (field in form.fields.take(10)) {
                            appendLine("    - $field")
                        }
                        if (form.fields.size > 10) appendLine("    ... (${form.fields.size - 10} more)")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} forms",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading forms: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun findFormClassEnd(content: String, classStart: Int): Int {
    val nextClass = Regex("""^class\s+\w+""", RegexOption.MULTILINE)
        .find(content, classStart + 1)
    return nextClass?.range?.first ?: content.length
}
