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

private data class RouteEntry(
    val file: String,
    val decorator: String,
    val path: String,
    val methods: String?,
    val function: String?
)

private val ROUTE_DECORATOR_PATTERN = Regex(
    """@(\w+)\.(route|get|post|put|delete|patch)\s*\(\s*["']([^"']*)["'](?:\s*,\s*methods\s*=\s*\[([^\]]*)\])?""",
    RegexOption.MULTILINE
)
private val FUNCTION_AFTER_DECORATOR = Regex(
    """^def\s+(\w+)\s*\(""",
    RegexOption.MULTILINE
)

// Matches: bp = Blueprint('name', __name__, url_prefix='/api')
// Also handles Blueprint("name", __name__, url_prefix="/api") and variations with other args
private val BLUEPRINT_PATTERN = Regex(
    """(\w+)\s*=\s*Blueprint\s*\([^)]*url_prefix\s*=\s*["']([^"']*)["']"""
)

// Matches: app.register_blueprint(bp, url_prefix='/api') or with keyword url_prefix=
private val REGISTER_BLUEPRINT_PATTERN = Regex(
    """(\w+)\.register_blueprint\s*\(\s*([\w.]+)(?:\s*,\s*[^)]*url_prefix\s*=\s*["']([^"']*)["'])?"""
)

internal suspend fun executeRoutes(params: JsonObject, project: Project): ToolResult {
    val filter = params["blueprint"]?.jsonPrimitive?.content
        ?: params["filter"]?.jsonPrimitive?.content
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

            val allRoutes = mutableListOf<RouteEntry>()
            for (pyFile in pyFiles) {
                parseRoutes(pyFile, basePath, allRoutes)
            }

            val filtered = if (filter != null) {
                allRoutes.filter { r ->
                    r.path.contains(filter, ignoreCase = true) ||
                        r.decorator.contains(filter, ignoreCase = true) ||
                        r.function?.contains(filter, ignoreCase = true) == true
                }
            } else {
                allRoutes
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (filter != null) " matching '$filter'" else ""
                return@withContext ToolResult("No routes found$filterDesc.", "No routes", 5)
            }

            val content = buildString {
                appendLine("Flask routes (${filtered.size} total from ${pyFiles.size} file(s)):")
                appendLine()
                val byFile = filtered.groupBy { it.file }
                for ((file, routes) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for (route in routes) {
                        val methods = if (route.methods != null) " [${route.methods}]" else
                            if (route.decorator.contains(".")) " [${route.decorator.substringAfterLast('.').uppercase()}]" else ""
                        val funcStr = if (route.function != null) " -> ${route.function}" else ""
                        appendLine("  @${route.decorator}('${route.path}')$methods$funcStr")
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

    // Build blueprint variable -> url_prefix map
    val blueprintPrefixes = mutableMapOf<String, String>()
    for (match in BLUEPRINT_PATTERN.findAll(content)) {
        val varName = match.groupValues[1]
        val prefix = match.groupValues[2]
        blueprintPrefixes[varName] = prefix
    }

    // Override with register_blueprint(bp, url_prefix=...) if present
    for (match in REGISTER_BLUEPRINT_PATTERN.findAll(content)) {
        val bpRef = match.groupValues[2]
        val overridePrefix = match.groupValues[3]
        if (overridePrefix.isNotEmpty()) {
            val localName = bpRef.substringAfterLast('.')
            blueprintPrefixes[localName] = overridePrefix
            if (localName != bpRef) {
                blueprintPrefixes[bpRef] = overridePrefix
            }
        }
    }

    for (match in ROUTE_DECORATOR_PATTERN.findAll(content)) {
        val obj = match.groupValues[1]
        val method = match.groupValues[2]
        val routePath = match.groupValues[3]
        val methods = match.groupValues[4].takeIf { it.isNotBlank() }
            ?.replace("'", "")?.replace("\"", "")?.trim()

        // Compose full path: blueprint prefix + route path
        val prefix = blueprintPrefixes[obj] ?: ""
        val fullPath = composePath(prefix, routePath)

        // Find the function name after this decorator
        val afterDecorator = content.substring(match.range.last)
        val funcMatch = FUNCTION_AFTER_DECORATOR.find(afterDecorator)
        val funcName = funcMatch?.groupValues?.get(1)

        results.add(RouteEntry(relPath, "$obj.$method", fullPath, methods, funcName))
    }
}

/**
 * Composes a prefix and route path, handling slash deduplication.
 * e.g., composePath("/api", "/users") -> "/api/users"
 *       composePath("/api/", "/users") -> "/api/users"
 *       composePath("", "/users") -> "/users"
 */
private fun composePath(prefix: String, routePath: String): String {
    if (prefix.isEmpty()) return routePath
    val normalizedPrefix = prefix.trimEnd('/')
    val normalizedRoute = if (routePath.startsWith("/")) routePath else "/$routePath"
    return "$normalizedPrefix$normalizedRoute"
}
