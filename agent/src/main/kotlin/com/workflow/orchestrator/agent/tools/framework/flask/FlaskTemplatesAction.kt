package com.workflow.orchestrator.agent.tools.framework.flask

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

internal suspend fun executeTemplates(params: JsonObject, project: Project): ToolResult {
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
            val templateFiles = baseDir.walkTopDown()
                .filter { file ->
                    file.isFile &&
                        (file.extension == "html" || file.extension == "jinja2" || file.extension == "j2") &&
                        file.absolutePath.contains("/templates/")
                }
                .toList()

            if (templateFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No template files found in templates/ directories.",
                    "No templates found",
                    5
                )
            }

            val filtered = if (filter != null) {
                templateFiles.filter { it.absolutePath.contains(filter, ignoreCase = true) }
            } else {
                templateFiles
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (filter != null) " matching '$filter'" else ""
                return@withContext ToolResult("No templates found$filterDesc.", "No templates", 5)
            }

            val content = buildString {
                appendLine("Flask/Jinja2 templates (${filtered.size} total):")
                appendLine()

                val byDir = filtered.groupBy { it.parentFile?.absolutePath ?: "" }
                for ((dir, files) in byDir.toSortedMap()) {
                    val relDir = dir.removePrefix(basePath).trimStart(File.separatorChar)
                    appendLine("[$relDir]")
                    for (tmpl in files.sortedBy { it.name }) {
                        appendLine("  ${tmpl.name}")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} templates",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading templates: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}
