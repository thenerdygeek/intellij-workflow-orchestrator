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

private data class BlueprintEntry(
    val file: String,
    val varName: String,
    val name: String,
    val importName: String?,
    val urlPrefix: String?
)

private data class RegistrationEntry(
    val file: String,
    val blueprintRef: String,
    val urlPrefix: String?
)

private val BLUEPRINT_CONSTRUCTOR_PATTERN = Regex(
    """(\w+)\s*=\s*Blueprint\s*\(\s*["'](\w+)["']\s*,?\s*([^)]+)?""",
    RegexOption.MULTILINE
)
private val URL_PREFIX_PATTERN = Regex("""url_prefix\s*=\s*["']([^"']*)["']""")
private val REGISTER_BLUEPRINT_PATTERN = Regex(
    """(\w+)\.register_blueprint\s*\(\s*(\w+)(?:\s*,\s*url_prefix\s*=\s*["']([^"']*)["'])?""",
    RegexOption.MULTILINE
)

internal suspend fun executeBlueprints(params: JsonObject, project: Project): ToolResult {
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

            val blueprints = mutableListOf<BlueprintEntry>()
            val registrations = mutableListOf<RegistrationEntry>()

            for (pyFile in pyFiles) {
                val content = pyFile.readText()
                val relPath = pyFile.absolutePath.removePrefix(basePath).trimStart(File.separatorChar)

                for (match in BLUEPRINT_CONSTRUCTOR_PATTERN.findAll(content)) {
                    val argsGroup = match.groupValues[3]
                    val importName = argsGroup.split(",").firstOrNull()
                        ?.trim()?.takeIf { it.isNotBlank() && !it.contains("=") }
                    val urlPrefix = URL_PREFIX_PATTERN.find(argsGroup)?.groupValues?.get(1)
                    blueprints.add(
                        BlueprintEntry(
                            file = relPath,
                            varName = match.groupValues[1],
                            name = match.groupValues[2],
                            importName = importName,
                            urlPrefix = urlPrefix
                        )
                    )
                }

                for (match in REGISTER_BLUEPRINT_PATTERN.findAll(content)) {
                    registrations.add(
                        RegistrationEntry(
                            file = relPath,
                            blueprintRef = match.groupValues[2],
                            urlPrefix = match.groupValues[3].takeIf { it.isNotBlank() }
                        )
                    )
                }
            }

            val filteredBp = if (filter != null) {
                blueprints.filter { bp ->
                    bp.name.contains(filter, ignoreCase = true) ||
                        bp.varName.contains(filter, ignoreCase = true)
                }
            } else {
                blueprints
            }

            if (filteredBp.isEmpty() && registrations.isEmpty()) {
                val filterDesc = if (filter != null) " matching '$filter'" else ""
                return@withContext ToolResult("No blueprints found$filterDesc.", "No blueprints", 5)
            }

            val content = buildString {
                if (filteredBp.isNotEmpty()) {
                    appendLine("Flask blueprints (${filteredBp.size} definitions):")
                    appendLine()
                    for (bp in filteredBp.sortedBy { it.name }) {
                        val prefix = if (bp.urlPrefix != null) ", url_prefix='${bp.urlPrefix}'" else ""
                        appendLine("  ${bp.varName} = Blueprint('${bp.name}'$prefix)")
                        appendLine("    File: ${bp.file}")
                    }
                    appendLine()
                }

                if (registrations.isNotEmpty()) {
                    appendLine("Blueprint registrations (${registrations.size}):")
                    appendLine()
                    for (reg in registrations) {
                        val prefix = if (reg.urlPrefix != null) ", url_prefix='${reg.urlPrefix}'" else ""
                        appendLine("  register_blueprint(${reg.blueprintRef}$prefix)")
                        appendLine("    File: ${reg.file}")
                    }
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filteredBp.size} blueprints, ${registrations.size} registrations",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading blueprints: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}
