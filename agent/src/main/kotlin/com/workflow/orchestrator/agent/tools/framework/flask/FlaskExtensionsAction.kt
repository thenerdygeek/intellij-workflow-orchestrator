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

private data class ExtensionEntry(
    val file: String,
    val varName: String,
    val extensionType: String,
    val initStyle: String  // "constructor" or "init_app"
)

// Matches: db = SQLAlchemy(app) or db = SQLAlchemy()
private val EXTENSION_CONSTRUCTOR_PATTERN = Regex(
    """(\w+)\s*=\s*(\w+)\s*\(\s*(\w+)?\s*\)""",
    RegexOption.MULTILINE
)

// Matches: db.init_app(app)
private val INIT_APP_PATTERN = Regex(
    """(\w+)\.init_app\s*\(\s*(\w+)\s*\)""",
    RegexOption.MULTILINE
)

// Known Flask extensions to filter noise
private val KNOWN_EXTENSIONS = setOf(
    "SQLAlchemy", "Migrate", "Mail", "Cors", "CORS",
    "Bcrypt", "LoginManager", "Marshmallow", "Babel",
    "Limiter", "Cache", "SocketIO", "Celery",
    "Admin", "RESTful", "Api", "Swagger",
    "JWT", "JWTManager", "CSRFProtect", "Bootstrap",
    "Talisman", "Compress", "DebugToolbarExtension",
    "Session", "Caching", "APScheduler", "Moment"
)

internal suspend fun executeExtensions(params: JsonObject, project: Project): ToolResult {
    val filter = params["extension"]?.jsonPrimitive?.content
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

            val extensions = mutableListOf<ExtensionEntry>()
            val initAppCalls = mutableListOf<ExtensionEntry>()

            for (pyFile in pyFiles) {
                val content = pyFile.readText()
                val relPath = PythonFileScanner.relPath(pyFile, basePath)

                for (match in EXTENSION_CONSTRUCTOR_PATTERN.findAll(content)) {
                    val varName = match.groupValues[1]
                    val typeName = match.groupValues[2]
                    if (typeName in KNOWN_EXTENSIONS) {
                        extensions.add(
                            ExtensionEntry(relPath, varName, typeName, "constructor")
                        )
                    }
                }

                for (match in INIT_APP_PATTERN.findAll(content)) {
                    val varName = match.groupValues[1]
                    initAppCalls.add(
                        ExtensionEntry(relPath, varName, "init_app", "init_app")
                    )
                }
            }

            val allEntries = extensions + initAppCalls
            val filtered = if (filter != null) {
                allEntries.filter { e ->
                    e.extensionType.contains(filter, ignoreCase = true) ||
                        e.varName.contains(filter, ignoreCase = true)
                }
            } else {
                allEntries
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (filter != null) " matching '$filter'" else ""
                return@withContext ToolResult("No extensions found$filterDesc.", "No extensions", 5)
            }

            val filteredExtensions = filtered.filter { it.initStyle == "constructor" }
            val filteredInitAppCalls = filtered.filter { it.initStyle == "init_app" }

            val content = buildString {
                appendLine("Flask extensions (${filteredExtensions.size} definitions, ${filteredInitAppCalls.size} init_app calls):")
                appendLine()

                if (filteredExtensions.isNotEmpty()) {
                    appendLine("Extension instances:")
                    val byFile = filteredExtensions.groupBy { it.file }
                    for ((file, exts) in byFile.toSortedMap()) {
                        appendLine("  [$file]")
                        for (ext in exts.sortedBy { it.varName }) {
                            appendLine("    ${ext.varName} = ${ext.extensionType}()")
                        }
                    }
                    appendLine()
                }

                if (filteredInitAppCalls.isNotEmpty()) {
                    appendLine("init_app() calls:")
                    val byFile = filteredInitAppCalls.groupBy { it.file }
                    for ((file, calls) in byFile.toSortedMap()) {
                        appendLine("  [$file]")
                        for (call in calls.sortedBy { it.varName }) {
                            appendLine("    ${call.varName}.init_app(app)")
                        }
                    }
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filteredExtensions.size} extensions, ${filteredInitAppCalls.size} init_app calls",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading extensions: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}
