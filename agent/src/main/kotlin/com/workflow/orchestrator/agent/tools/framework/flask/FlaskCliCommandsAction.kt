package com.workflow.orchestrator.agent.tools.framework.flask

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private data class CliCommandEntry(
    val file: String,
    val decorator: String,
    val name: String?,
    val function: String?
)

private val CLI_COMMAND_PATTERN = Regex(
    """@(\w+)\.cli\.command\s*\(\s*(?:["'](\w+)["'])?\s*\)""",
    RegexOption.MULTILINE
)
private val CLICK_COMMAND_PATTERN = Regex(
    """@click\.command\s*\(\s*(?:["'](\w+)["'])?\s*\)""",
    RegexOption.MULTILINE
)
private val CLICK_GROUP_PATTERN = Regex(
    """@click\.group\s*\(\s*(?:["'](\w+)["'])?\s*\)""",
    RegexOption.MULTILINE
)
private val FUNCTION_DEF_PATTERN = Regex(
    """^def\s+(\w+)\s*\(""",
    RegexOption.MULTILINE
)

internal suspend fun executeCliCommands(params: JsonObject, project: Project): ToolResult {
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
            val pyFiles = baseDir.walkTopDown()
                .filter { it.isFile && it.extension == "py" }
                .toList()

            if (pyFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No Python files found in project.",
                    "No Python files found",
                    5
                )
            }

            val allCommands = mutableListOf<CliCommandEntry>()
            for (pyFile in pyFiles) {
                parseCliCommands(pyFile, basePath, allCommands)
            }

            val filtered = if (filter != null) {
                allCommands.filter { c ->
                    c.name?.contains(filter, ignoreCase = true) == true ||
                        c.function?.contains(filter, ignoreCase = true) == true ||
                        c.decorator.contains(filter, ignoreCase = true)
                }
            } else {
                allCommands
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (filter != null) " matching '$filter'" else ""
                return@withContext ToolResult(
                    "No CLI commands found$filterDesc.",
                    "No CLI commands",
                    5
                )
            }

            val content = buildString {
                appendLine("Flask/Click CLI commands (${filtered.size} total):")
                appendLine()
                val byFile = filtered.groupBy { it.file }
                for ((file, commands) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for (cmd in commands) {
                        val nameStr = cmd.name ?: cmd.function ?: "unnamed"
                        val funcStr = if (cmd.function != null) " -> ${cmd.function}()" else ""
                        appendLine("  @${cmd.decorator} '$nameStr'$funcStr")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} CLI commands",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading CLI commands: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun parseCliCommands(pyFile: File, basePath: String, results: MutableList<CliCommandEntry>) {
    val content = pyFile.readText()
    val relPath = pyFile.absolutePath.removePrefix(basePath).trimStart(File.separatorChar)

    for (match in CLI_COMMAND_PATTERN.findAll(content)) {
        val obj = match.groupValues[1]
        val name = match.groupValues[2].takeIf { it.isNotBlank() }
        val afterDecorator = content.substring(match.range.last)
        val funcMatch = FUNCTION_DEF_PATTERN.find(afterDecorator)
        val funcName = funcMatch?.groupValues?.get(1)
        results.add(CliCommandEntry(relPath, "$obj.cli.command", name ?: funcName, funcName))
    }

    for (match in CLICK_COMMAND_PATTERN.findAll(content)) {
        val name = match.groupValues[1].takeIf { it.isNotBlank() }
        val afterDecorator = content.substring(match.range.last)
        val funcMatch = FUNCTION_DEF_PATTERN.find(afterDecorator)
        val funcName = funcMatch?.groupValues?.get(1)
        results.add(CliCommandEntry(relPath, "click.command", name ?: funcName, funcName))
    }

    for (match in CLICK_GROUP_PATTERN.findAll(content)) {
        val name = match.groupValues[1].takeIf { it.isNotBlank() }
        val afterDecorator = content.substring(match.range.last)
        val funcMatch = FUNCTION_DEF_PATTERN.find(afterDecorator)
        val funcName = funcMatch?.groupValues?.get(1)
        results.add(CliCommandEntry(relPath, "click.group", name ?: funcName, funcName))
    }
}
