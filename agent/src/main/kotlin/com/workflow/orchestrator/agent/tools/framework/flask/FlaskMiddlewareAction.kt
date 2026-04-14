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

private data class MiddlewareEntry(
    val file: String,
    val decorator: String,
    val function: String?
)

private val MIDDLEWARE_DECORATOR_PATTERN = Regex(
    """@(\w+)\.(before_request|after_request|before_first_request|teardown_request|teardown_appcontext|errorhandler)\s*(?:\(\s*(\d+)\s*\))?""",
    RegexOption.MULTILINE
)
private val FUNCTION_DEF_PATTERN = Regex(
    """^def\s+(\w+)\s*\(""",
    RegexOption.MULTILINE
)

internal suspend fun executeMiddleware(params: JsonObject, project: Project): ToolResult {
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
            val pyFiles = PythonFileScanner.scanAllPyFiles(baseDir)

            if (pyFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No Python files found in project.",
                    "No Python files found",
                    5
                )
            }

            val allMiddleware = mutableListOf<MiddlewareEntry>()
            for (pyFile in pyFiles) {
                parseMiddleware(pyFile, basePath, allMiddleware)
            }

            val filtered = if (filter != null) {
                allMiddleware.filter { m ->
                    m.decorator.contains(filter, ignoreCase = true) ||
                        m.function?.contains(filter, ignoreCase = true) == true
                }
            } else {
                allMiddleware
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (filter != null) " matching '$filter'" else ""
                return@withContext ToolResult(
                    "No before_request/after_request/errorhandler hooks found$filterDesc.",
                    "No middleware hooks",
                    5
                )
            }

            val content = buildString {
                appendLine("Flask middleware/hooks (${filtered.size} total):")
                appendLine()
                val byFile = filtered.groupBy { it.file }
                for ((file, hooks) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for (hook in hooks) {
                        val funcStr = if (hook.function != null) " -> ${hook.function}()" else ""
                        appendLine("  @${hook.decorator}$funcStr")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} middleware hooks across ${filtered.map { it.file }.distinct().size} file(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading middleware: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun parseMiddleware(pyFile: File, basePath: String, results: MutableList<MiddlewareEntry>) {
    val content = pyFile.readText()
    val relPath = PythonFileScanner.relPath(pyFile, basePath)

    for (match in MIDDLEWARE_DECORATOR_PATTERN.findAll(content)) {
        val obj = match.groupValues[1]
        val hookType = match.groupValues[2]
        val errorCode = match.groupValues[3].takeIf { it.isNotBlank() }
        val decoratorStr = if (errorCode != null) "$obj.$hookType($errorCode)" else "$obj.$hookType"

        // Find the function defined after this decorator
        val afterDecorator = content.substring(match.range.last)
        val funcMatch = FUNCTION_DEF_PATTERN.find(afterDecorator)
        val funcName = funcMatch?.groupValues?.get(1)

        results.add(MiddlewareEntry(relPath, decoratorStr, funcName))
    }
}
