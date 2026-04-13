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

internal suspend fun executeFixtures(params: JsonObject, project: Project): ToolResult {
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
            val fixtureFiles = PythonFileScanner.scanPythonFiles(baseDir) { file ->
                file.absolutePath.contains("/fixtures/") &&
                    file.extension in setOf("json", "yaml", "xml")
            }

            if (fixtureFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No fixture files found in fixtures/ directories.",
                    "No fixtures found",
                    5
                )
            }

            val filtered = if (filter != null) {
                fixtureFiles.filter { it.absolutePath.contains(filter, ignoreCase = true) }
            } else {
                fixtureFiles
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (filter != null) " matching '$filter'" else ""
                return@withContext ToolResult("No fixtures found$filterDesc.", "No fixtures", 5)
            }

            val content = buildString {
                appendLine("Django fixtures (${filtered.size} total):")
                appendLine()

                val byDir = filtered.groupBy { it.parentFile?.absolutePath ?: "" }
                for ((dir, files) in byDir.toSortedMap()) {
                    val relDir = dir.removePrefix(basePath).trimStart(File.separatorChar)
                    appendLine("[$relDir]")
                    for (fixture in files.sortedBy { it.name }) {
                        val sizePretty = when {
                            fixture.length() < 1024 -> "${fixture.length()} B"
                            fixture.length() < 1024 * 1024 -> "${fixture.length() / 1024} KB"
                            else -> "${fixture.length() / (1024 * 1024)} MB"
                        }
                        appendLine("  ${fixture.name} ($sizePretty)")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} fixtures",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading fixtures: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}
