package com.workflow.orchestrator.agent.tools.framework.fastapi

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import com.workflow.orchestrator.agent.tools.framework.PythonFileScanner
import java.io.File

private data class EventEntry(
    val file: String,
    val eventType: String,
    val handler: String,
    val lineNumber: Int
)

private val ON_EVENT_PATTERN = Regex(
    """@(\w+)\.on_event\s*\(\s*["'](startup|shutdown)["']\s*\)"""
)
private val LIFESPAN_PATTERN = Regex(
    """@asynccontextmanager\s*\n\s*(?:async\s+)?def\s+(\w+)\s*\("""
)
private val LIFESPAN_APP_PATTERN = Regex(
    """FastAPI\s*\([^)]*lifespan\s*=\s*(\w+)"""
)
private val HANDLER_DEF_PATTERN = Regex(
    """(?:async\s+)?def\s+(\w+)\s*\("""
)

internal suspend fun executeEvents(params: JsonObject, project: Project): ToolResult {
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

            val events = mutableListOf<EventEntry>()
            for (pyFile in pyFiles) {
                parseEvents(pyFile, basePath, events)
            }

            if (events.isEmpty()) {
                return@withContext ToolResult(
                    "No FastAPI event handlers found in project.",
                    "No events found",
                    5
                )
            }

            val content = buildString {
                appendLine("FastAPI event handlers (${events.size} found):")
                appendLine()
                val byFile = events.groupBy { it.file }
                for ((file, fileEvents) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for (event in fileEvents.sortedBy { it.lineNumber }) {
                        appendLine("  [${event.eventType}] ${event.handler}()")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${events.size} event handler(s) across ${events.map { it.file }.distinct().size} file(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading events: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun parseEvents(pyFile: File, basePath: String, results: MutableList<EventEntry>) {
    val content = pyFile.readText()
    val relPath = PythonFileScanner.relPath(pyFile, basePath)
    val lines = content.lines()

    // Parse @app.on_event("startup"/"shutdown") decorators
    for ((index, line) in lines.withIndex()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#")) continue

        val eventMatch = ON_EVENT_PATTERN.find(trimmed)
        if (eventMatch != null) {
            val eventType = eventMatch.groupValues[2]
            val handler = findNextHandlerDef(lines, index + 1)
            results.add(EventEntry(relPath, eventType, handler, index + 1))
        }
    }

    // Parse lifespan context manager pattern
    for (match in LIFESPAN_PATTERN.findAll(content)) {
        val funcName = match.groupValues[1]
        val lineNumber = content.substring(0, match.range.first).count { it == '\n' } + 1
        results.add(EventEntry(relPath, "lifespan", funcName, lineNumber))
    }

    // Parse FastAPI(lifespan=xxx) reference
    for (match in LIFESPAN_APP_PATTERN.findAll(content)) {
        val lifespanFunc = match.groupValues[1]
        val lineNumber = content.substring(0, match.range.first).count { it == '\n' } + 1
        // Only add if we didn't already find this as a lifespan decorator
        if (results.none { it.handler == lifespanFunc && it.eventType == "lifespan" }) {
            results.add(EventEntry(relPath, "lifespan", lifespanFunc, lineNumber))
        }
    }
}

private fun findNextHandlerDef(lines: List<String>, startIndex: Int): String {
    for (i in startIndex until minOf(startIndex + 5, lines.size)) {
        val line = lines[i].trim()
        if (line.startsWith("#") || line.startsWith("@") || line.isBlank()) continue
        val match = HANDLER_DEF_PATTERN.find(line)
        if (match != null) return match.groupValues[1]
    }
    return "(unknown)"
}
