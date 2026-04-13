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

private data class RouteEntry(
    val file: String,
    val method: String,
    val path: String,
    val handler: String,
    val lineNumber: Int
)

private val ROUTE_DECORATOR_PATTERN = Regex(
    """@(\w+)\.(get|post|put|delete|patch|options|head)\s*\(\s*["']([^"']*)["']""",
    RegexOption.MULTILINE
)
private val HANDLER_PATTERN = Regex(
    """(?:async\s+)?def\s+(\w+)\s*\("""
)

// Matches: router = APIRouter(prefix="/api/v1") or APIRouter(prefix='/api/v1')
private val API_ROUTER_PATTERN = Regex(
    """(\w+)\s*=\s*APIRouter\s*\([^)]*prefix\s*=\s*["']([^"']*)["']"""
)

// Matches: app.include_router(router, prefix="/api/v1") or app.include_router(mod.router, prefix="/api/v1")
private val INCLUDE_ROUTER_PATTERN = Regex(
    """(\w+)\.include_router\s*\(\s*([\w.]+)(?:\s*,\s*[^)]*prefix\s*=\s*["']([^"']*)["'])?"""
)

internal suspend fun executeRoutes(params: JsonObject, project: Project): ToolResult {
    val pathFilter = params["path"]?.jsonPrimitive?.content
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

            val routes = mutableListOf<RouteEntry>()
            for (pyFile in pyFiles) {
                parseRoutes(pyFile, basePath, routes)
            }

            val filtered = if (pathFilter != null) {
                routes.filter { it.path.contains(pathFilter, ignoreCase = true) }
            } else {
                routes
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (pathFilter != null) " matching '$pathFilter'" else ""
                return@withContext ToolResult(
                    "No FastAPI routes found$filterDesc.",
                    "No routes found",
                    5
                )
            }

            val content = buildString {
                appendLine("FastAPI routes (${filtered.size} total from ${pyFiles.size} file(s)):")
                appendLine()
                val byFile = filtered.groupBy { it.file }
                for ((file, fileRoutes) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for (route in fileRoutes.sortedBy { it.lineNumber }) {
                        appendLine("  ${route.method.uppercase()} ${route.path} -> ${route.handler}")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} routes across ${filtered.map { it.file }.distinct().size} file(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading routes: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun parseRoutes(pyFile: File, basePath: String, results: MutableList<RouteEntry>) {
    val content = pyFile.readText()
    val relPath = pyFile.absolutePath.removePrefix(basePath).trimStart(File.separatorChar)
    val lines = content.lines()

    // Build router variable -> prefix map from APIRouter(prefix=...) declarations
    val routerPrefixes = mutableMapOf<String, String>()
    for (match in API_ROUTER_PATTERN.findAll(content)) {
        val varName = match.groupValues[1]
        val prefix = match.groupValues[2]
        routerPrefixes[varName] = prefix
    }

    // Override with include_router(router, prefix=...) if present
    for (match in INCLUDE_ROUTER_PATTERN.findAll(content)) {
        val routerRef = match.groupValues[2]
        val overridePrefix = match.groupValues[3]
        if (overridePrefix.isNotEmpty()) {
            // For dotted refs like "users.router", apply to the last segment ("router")
            // which is the variable name used in route decorators within this file
            val localName = routerRef.substringAfterLast('.')
            routerPrefixes[localName] = overridePrefix
            // Also store the full dotted name in case it matches
            if (localName != routerRef) {
                routerPrefixes[routerRef] = overridePrefix
            }
        }
    }

    for ((index, line) in lines.withIndex()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#")) continue

        val match = ROUTE_DECORATOR_PATTERN.find(trimmed) ?: continue
        val routerVar = match.groupValues[1]
        val method = match.groupValues[2]
        val routePath = match.groupValues[3]

        // Compose full path: router prefix + route path
        val prefix = routerPrefixes[routerVar] ?: ""
        val fullPath = composePath(prefix, routePath)

        // Look for the handler function on the next non-empty, non-decorator lines
        val handler = findNextHandler(lines, index + 1)

        results.add(RouteEntry(relPath, method, fullPath, handler, index + 1))
    }
}

private fun findNextHandler(lines: List<String>, startIndex: Int): String {
    for (i in startIndex until minOf(startIndex + 5, lines.size)) {
        val line = lines[i].trim()
        if (line.startsWith("#") || line.startsWith("@") || line.isBlank()) continue
        val handlerMatch = HANDLER_PATTERN.find(line)
        if (handlerMatch != null) return handlerMatch.groupValues[1]
    }
    return "(unknown)"
}

/**
 * Composes a prefix and route path, handling slash deduplication.
 * e.g., composePath("/api/v1", "/users") -> "/api/v1/users"
 *       composePath("/api/v1/", "/users") -> "/api/v1/users"
 *       composePath("", "/users") -> "/users"
 */
private fun composePath(prefix: String, routePath: String): String {
    if (prefix.isEmpty()) return routePath
    val normalizedPrefix = prefix.trimEnd('/')
    val normalizedRoute = if (routePath.startsWith("/")) routePath else "/$routePath"
    return "$normalizedPrefix$normalizedRoute"
}
