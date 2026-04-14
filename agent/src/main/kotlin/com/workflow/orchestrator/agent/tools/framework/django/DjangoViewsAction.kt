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

private data class ViewEntry(
    val file: String,
    val name: String,
    val kind: String  // "function", "class-based", "viewset"
)

private val CBV_PATTERN = Regex(
    """^class\s+(\w+)\s*\([^)]*(?:View|APIView|ViewSet|GenericView|Mixin)[^)]*\)""",
    RegexOption.MULTILINE
)
private val FBV_PATTERN = Regex(
    """^(?:@\w+\s*\n)*def\s+(\w+)\s*\(\s*request""",
    RegexOption.MULTILINE
)

internal suspend fun executeViews(params: JsonObject, project: Project): ToolResult {
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
            val viewFiles = PythonFileScanner.scanPythonFiles(baseDir) {
                it.name == "views.py" || it.name == "viewsets.py"
            }

            if (viewFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No views.py or viewsets.py files found in project.",
                    "No view files found",
                    5
                )
            }

            val allViews = mutableListOf<ViewEntry>()
            for (viewFile in viewFiles) {
                parseViews(viewFile, basePath, allViews)
            }

            val filtered = if (filter != null) {
                allViews.filter { v ->
                    v.name.contains(filter, ignoreCase = true) ||
                        v.file.contains(filter, ignoreCase = true)
                }
            } else {
                allViews
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (filter != null) " matching '$filter'" else ""
                return@withContext ToolResult("No views found$filterDesc.", "No views", 5)
            }

            val content = buildString {
                appendLine("Django views (${filtered.size} total from ${viewFiles.size} file(s)):")
                appendLine()
                val byFile = filtered.groupBy { it.file }
                for ((file, views) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for (view in views.sortedBy { it.name }) {
                        appendLine("  ${view.name} (${view.kind})")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} views across ${viewFiles.size} file(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading views: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun parseViews(viewFile: File, basePath: String, results: MutableList<ViewEntry>) {
    val content = viewFile.readText()
    val relPath = PythonFileScanner.relPath(viewFile, basePath)

    for (match in CBV_PATTERN.findAll(content)) {
        val name = match.groupValues[1]
        val kind = if (match.value.contains("ViewSet")) "viewset" else "class-based"
        results.add(ViewEntry(relPath, name, kind))
    }

    for (match in FBV_PATTERN.findAll(content)) {
        val name = match.groupValues[1]
        if (results.none { it.name == name && it.file == relPath }) {
            results.add(ViewEntry(relPath, name, "function"))
        }
    }
}
