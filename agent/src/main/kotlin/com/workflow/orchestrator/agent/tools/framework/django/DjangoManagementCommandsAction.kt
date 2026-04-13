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

private data class ManagementCommandEntry(
    val app: String,
    val name: String,
    val docstring: String?
)

// Matches triple-quoted docstrings in help property:
//   help = """..."""  (double-quoted, group 1)
//   help = '''...'''  (single-quoted, group 2)
// Note: the pattern uses escaped quotes to avoid Kotlin raw-string terminator collision.
private val DOCSTRING_PATTERN = Regex(
    "help\\s*=\\s*(?:\"\"\"([^\"]*)\"\"\"|'''([^']*?)''')",
    setOf(RegexOption.DOT_MATCHES_ALL)
)

// Matches single-line help string:
//   help = "..."  or  help = '...'
private val HELP_PATTERN = Regex("""help\s*=\s*["']([^"']*)["']""")

// Command class definition
private val COMMAND_CLASS_PATTERN = Regex("""^class\s+Command\s*\(""", RegexOption.MULTILINE)

internal suspend fun executeManagementCommands(params: JsonObject, project: Project): ToolResult {
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

            // Management commands live under <app>/management/commands/<name>.py
            val commandFiles = PythonFileScanner.scanPythonFiles(baseDir) { file ->
                file.extension == "py" &&
                    file.parentFile?.name == "commands" &&
                    file.parentFile?.parentFile?.name == "management" &&
                    !file.name.startsWith("_")
            }

            if (commandFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No management commands found in project.",
                    "No management commands found",
                    5
                )
            }

            val commands = mutableListOf<ManagementCommandEntry>()

            for (commandFile in commandFiles) {
                val entry = parseManagementCommand(commandFile, basePath) ?: continue
                commands.add(entry)
            }

            val filtered = if (filter != null) {
                commands.filter { cmd ->
                    cmd.name.contains(filter, ignoreCase = true) ||
                        cmd.app.contains(filter, ignoreCase = true) ||
                        cmd.docstring?.contains(filter, ignoreCase = true) == true
                }
            } else {
                commands
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (filter != null) " matching '$filter'" else ""
                return@withContext ToolResult(
                    "No management commands found$filterDesc.",
                    "No management commands found",
                    5
                )
            }

            val content = buildString {
                appendLine("Django management commands (${filtered.size} total):")
                appendLine()

                val byApp = filtered.groupBy { it.app }
                for ((app, appCmds) in byApp.toSortedMap()) {
                    appendLine("[$app]")
                    for (cmd in appCmds.sortedBy { it.name }) {
                        val docStr = if (cmd.docstring != null) " — ${cmd.docstring}" else ""
                        appendLine("  python manage.py ${cmd.name}$docStr")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} management commands across ${
                    filtered.map { it.app }.distinct().size
                } app(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult(
            "Error reading management commands: ${e.message}",
            "Error",
            ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }
}

private fun parseManagementCommand(commandFile: File, basePath: String): ManagementCommandEntry? {
    val content = commandFile.readText()
    if (!COMMAND_CLASS_PATTERN.containsMatchIn(content)) return null

    val commandName = commandFile.nameWithoutExtension

    // Derive app name: the directory 3 levels up from the .py file
    // Structure: <app>/management/commands/<name>.py
    val appDir = commandFile.parentFile?.parentFile?.parentFile
    val appName = appDir?.name ?: "unknown"

    // Extract help text — prefer triple-quoted docstring, fall back to single-line help
    val docstring = DOCSTRING_PATTERN.find(content)?.let { match ->
        // group 1 = double-quoted content, group 2 = single-quoted content
        match.groupValues[1].ifEmpty { match.groupValues[2] }
            .trim()
            .lines()
            .firstOrNull { it.isNotBlank() }
    } ?: HELP_PATTERN.find(content)?.groupValues?.get(1)?.trim()

    return ManagementCommandEntry(appName, commandName, docstring?.takeIf { it.isNotBlank() })
}
