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

private data class FormEntry(
    val file: String,
    val name: String,
    val kind: String,  // "ModelForm", "Form"
    val model: String?
)

private val FORM_CLASS_PATTERN = Regex(
    """^class\s+(\w+)\s*\([^)]*(\w*Form)[^)]*\)""",
    RegexOption.MULTILINE
)
private val FORM_MODEL_PATTERN = Regex(
    """class\s+Meta\s*:[^c]*model\s*=\s*(\w+)""",
    setOf(RegexOption.DOT_MATCHES_ALL)
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
            val formFiles = PythonFileScanner.scanPythonFiles(baseDir) {
                it.name == "forms.py" || it.name == "form.py"
            }

            if (formFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No forms.py files found in project.",
                    "No form files found",
                    5
                )
            }

            val allForms = mutableListOf<FormEntry>()

            for (formFile in formFiles) {
                val content = formFile.readText()
                val relPath = PythonFileScanner.relPath(formFile, basePath)

                for (match in FORM_CLASS_PATTERN.findAll(content)) {
                    val name = match.groupValues[1]
                    val kind = match.groupValues[2]
                    if (kind.isBlank()) continue
                    val classStart = match.range.first
                    val classEnd = PythonFileScanner.findClassEnd(content, classStart)
                    val classBody = content.substring(classStart, classEnd)
                    val model = FORM_MODEL_PATTERN.find(classBody)?.groupValues?.get(1)
                    allForms.add(FormEntry(relPath, name, kind, model))
                }
            }

            val filtered = if (filter != null) {
                allForms.filter { f ->
                    f.name.contains(filter, ignoreCase = true) ||
                        f.model?.contains(filter, ignoreCase = true) == true ||
                        f.file.contains(filter, ignoreCase = true)
                }
            } else {
                allForms
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (filter != null) " matching '$filter'" else ""
                return@withContext ToolResult("No forms found$filterDesc.", "No forms", 5)
            }

            val content = buildString {
                appendLine("Django forms (${filtered.size} total):")
                appendLine()
                val byFile = filtered.groupBy { it.file }
                for ((file, forms) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for (f in forms.sortedBy { it.name }) {
                        val modelStr = if (f.model != null) " (model=${f.model})" else ""
                        appendLine("  ${f.name} extends ${f.kind}$modelStr")
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

