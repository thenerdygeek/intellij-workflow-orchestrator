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

private data class DependencyEntry(
    val file: String,
    val function: String,
    val dependsOn: String,
    val lineNumber: Int
)

private val DEPENDS_PATTERN = Regex(
    """Depends\s*\(\s*(\w+)""",
    RegexOption.MULTILINE
)
private val FUNC_DEF_PATTERN = Regex(
    """(?:async\s+)?def\s+(\w+)\s*\("""
)

internal suspend fun executeDependencies(params: JsonObject, project: Project): ToolResult {
    val classNameFilter = params["class_name"]?.jsonPrimitive?.content
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

            val dependencies = mutableListOf<DependencyEntry>()
            for (pyFile in pyFiles) {
                parseDependencies(pyFile, basePath, dependencies)
            }

            val filtered = if (classNameFilter != null) {
                dependencies.filter { dep ->
                    dep.dependsOn.contains(classNameFilter, ignoreCase = true) ||
                        dep.function.contains(classNameFilter, ignoreCase = true)
                }
            } else {
                dependencies
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (classNameFilter != null) " matching '$classNameFilter'" else ""
                return@withContext ToolResult(
                    "No Depends() usages found$filterDesc.",
                    "No dependencies found",
                    5
                )
            }

            val content = buildString {
                appendLine("FastAPI dependencies (${filtered.size} Depends() usages):")
                appendLine()
                val byFile = filtered.groupBy { it.file }
                for ((file, deps) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for (dep in deps.sortedBy { it.lineNumber }) {
                        appendLine("  ${dep.function}() -> Depends(${dep.dependsOn})")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} dependency injections across ${filtered.map { it.file }.distinct().size} file(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading dependencies: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun parseDependencies(pyFile: File, basePath: String, results: MutableList<DependencyEntry>) {
    val content = pyFile.readText()
    val relPath = PythonFileScanner.relPath(pyFile, basePath)
    val lines = content.lines()

    var currentFunction = "(module-level)"
    for ((index, line) in lines.withIndex()) {
        val funcMatch = FUNC_DEF_PATTERN.find(line.trim())
        if (funcMatch != null) {
            currentFunction = funcMatch.groupValues[1]
        }

        for (match in DEPENDS_PATTERN.findAll(line)) {
            val dependsOn = match.groupValues[1]
            results.add(DependencyEntry(relPath, currentFunction, dependsOn, index + 1))
        }
    }
}
