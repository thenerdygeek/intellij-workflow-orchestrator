package com.workflow.orchestrator.agent.tools.framework.django

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import com.workflow.orchestrator.agent.tools.framework.PythonFileScanner
import java.io.File

private val MIDDLEWARE_BLOCK_PATTERN = Regex(
    """MIDDLEWARE\s*=\s*\[([^\]]*)\]""",
    setOf(RegexOption.DOT_MATCHES_ALL)
)
private val MIDDLEWARE_ENTRY_PATTERN = Regex("""["']([^"']+)["']""")

internal suspend fun executeMiddleware(params: JsonObject, project: Project): ToolResult {
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
            val settingsFiles = PythonFileScanner.scanPythonFiles(baseDir) {
                it.name.startsWith("settings") && it.extension == "py"
            }

            if (settingsFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No settings*.py files found in project.",
                    "No settings files found",
                    5
                )
            }

            val allMiddleware = mutableListOf<Pair<String, List<String>>>()

            for (settingsFile in settingsFiles) {
                val content = settingsFile.readText()
                val relPath = settingsFile.absolutePath.removePrefix(basePath).trimStart(File.separatorChar)
                val middlewareBlock = MIDDLEWARE_BLOCK_PATTERN.find(content) ?: continue
                val entries = MIDDLEWARE_ENTRY_PATTERN.findAll(middlewareBlock.groupValues[1])
                    .map { it.groupValues[1] }
                    .toList()
                if (entries.isNotEmpty()) {
                    allMiddleware.add(relPath to entries)
                }
            }

            if (allMiddleware.isEmpty()) {
                return@withContext ToolResult(
                    "No MIDDLEWARE setting found in settings files.",
                    "No middleware found",
                    5
                )
            }

            val content = buildString {
                appendLine("Django middleware stack:")
                appendLine()
                for ((file, middlewares) in allMiddleware) {
                    appendLine("[$file]")
                    middlewares.forEachIndexed { i, mw ->
                        appendLine("  ${i + 1}. $mw")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "Middleware from ${allMiddleware.size} settings file(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading middleware: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}
