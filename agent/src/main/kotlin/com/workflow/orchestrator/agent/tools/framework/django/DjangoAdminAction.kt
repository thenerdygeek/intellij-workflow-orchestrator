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

private val ADMIN_REGISTER_PATTERN = Regex(
    """admin\.site\.register\s*\(\s*(\w+)(?:\s*,\s*(\w+))?\s*\)"""
)
private val ADMIN_DECORATOR_PATTERN = Regex(
    """@admin\.register\s*\(\s*(\w+)\s*\)\s*\nclass\s+(\w+)""",
    RegexOption.MULTILINE
)
private val ADMIN_CLASS_PATTERN = Regex(
    """^class\s+(\w+Admin)\s*\([^)]*ModelAdmin[^)]*\)""",
    RegexOption.MULTILINE
)

internal suspend fun executeAdmin(params: JsonObject, project: Project): ToolResult {
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
            val adminFiles = PythonFileScanner.scanPythonFiles(baseDir) {
                it.name == "admin.py"
            }

            if (adminFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No admin.py files found in project.",
                    "No admin files found",
                    5
                )
            }

            val registrations = mutableListOf<Triple<String, String, String?>>() // (file, model, adminClass)

            for (adminFile in adminFiles) {
                val content = adminFile.readText()
                val relPath = adminFile.absolutePath.removePrefix(basePath).trimStart(File.separatorChar)

                ADMIN_REGISTER_PATTERN.findAll(content).forEach { match ->
                    registrations.add(Triple(relPath, match.groupValues[1], match.groupValues[2].ifBlank { null }))
                }
                ADMIN_DECORATOR_PATTERN.findAll(content).forEach { match ->
                    val model = match.groupValues[1]
                    val adminCls = match.groupValues[2]
                    if (registrations.none { it.first == relPath && it.second == model }) {
                        registrations.add(Triple(relPath, model, adminCls))
                    }
                }
            }

            val filtered = if (filter != null) {
                registrations.filter { (file, model, adminCls) ->
                    model.contains(filter, ignoreCase = true) ||
                        file.contains(filter, ignoreCase = true) ||
                        adminCls?.contains(filter, ignoreCase = true) == true
                }
            } else {
                registrations
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (filter != null) " matching '$filter'" else ""
                return@withContext ToolResult("No admin registrations found$filterDesc.", "No admin registrations", 5)
            }

            val content = buildString {
                appendLine("Django admin registrations (${filtered.size} total):")
                appendLine()
                val byFile = filtered.groupBy { it.first }
                for ((file, regs) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for ((_, model, adminCls) in regs.sortedBy { it.second }) {
                        val adminStr = if (adminCls != null) " (via $adminCls)" else ""
                        appendLine("  $model$adminStr")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} admin registrations",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading admin: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}
