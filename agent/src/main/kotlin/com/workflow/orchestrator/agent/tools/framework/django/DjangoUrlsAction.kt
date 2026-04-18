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

private data class UrlPatternEntry(
    val urlFile: String,
    val pattern: String,
    val view: String,
    val name: String?
)

// INCLUDE_PATTERN must be matched before PATH_PATTERN because both match `path(` lines;
// without priority, include() calls are captured by PATH_PATTERN with malformed view strings.
private val INCLUDE_PATTERN = Regex(
    """(?:path|re_path|url)\s*\(\s*["']([^"']*)["']\s*,\s*include\s*\(([^)]+)\)"""
)
private val PATH_PATTERN = Regex(
    """(?:path|re_path|url)\s*\(\s*["']([^"']*)["']\s*,\s*([\w.]+)(?:\s*,\s*name\s*=\s*["']([^"']*)["'])?"""
)

internal suspend fun executeUrls(params: JsonObject, project: Project): ToolResult {
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
            val urlFiles = PythonFileScanner.scanPythonFiles(baseDir) {
                it.name == "urls.py"
            }

            if (urlFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No urls.py files found in project.",
                    "No URL files found",
                    5
                )
            }

            val patterns = mutableListOf<UrlPatternEntry>()

            for (urlFile in urlFiles) {
                parseUrlPatterns(urlFile, basePath, patterns)
            }

            val filtered = if (filter != null) {
                patterns.filter { entry ->
                    entry.pattern.contains(filter, ignoreCase = true) ||
                        entry.view.contains(filter, ignoreCase = true) ||
                        entry.name?.contains(filter, ignoreCase = true) == true
                }
            } else {
                patterns
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (filter != null) " matching '$filter'" else ""
                return@withContext ToolResult(
                    "No URL patterns found$filterDesc.",
                    "No URL patterns found",
                    5
                )
            }

            val content = buildString {
                appendLine("Django URL patterns (${filtered.size} total from ${urlFiles.size} urls.py file(s)):")
                appendLine()

                val byFile = filtered.groupBy { it.urlFile }
                for ((file, filePatterns) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for (entry in filePatterns) {
                        val nameStr = if (entry.name != null) " [name='${entry.name}']" else ""
                        appendLine("  ${entry.pattern.ifBlank { "(root)" }} -> ${entry.view}$nameStr")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} URL patterns across ${urlFiles.size} file(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult(
            "Error reading URL patterns: ${e.message}",
            "Error",
            ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }
}

private fun parseUrlPatterns(urlFile: File, basePath: String, results: MutableList<UrlPatternEntry>) {
    val content = urlFile.readText()
    val relPath = PythonFileScanner.relPath(urlFile, basePath)

    for (line in content.lines()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#")) continue

        // INCLUDE_PATTERN checked first — include() lines must not fall through to PATH_PATTERN
        val includeMatch = INCLUDE_PATTERN.find(trimmed)
        if (includeMatch != null) {
            val urlPrefix = includeMatch.groupValues[1]
            val includeArg = includeMatch.groupValues[2].trim().trim('"', '\'')
            results.add(UrlPatternEntry(relPath, urlPrefix, "include($includeArg)", null))
            continue
        }

        val pathMatch = PATH_PATTERN.find(trimmed) ?: continue
        val urlPattern = pathMatch.groupValues[1]
        val view = pathMatch.groupValues[2]
        val name = pathMatch.groupValues[3].ifBlank { null }
        results.add(UrlPatternEntry(relPath, urlPattern, view, name))
    }
}
