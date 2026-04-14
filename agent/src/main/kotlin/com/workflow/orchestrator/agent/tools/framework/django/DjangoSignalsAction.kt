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

private data class SignalEntry(
    val file: String,
    val signal: String,
    val handler: String
)

private val RECEIVER_DECORATOR_PATTERN = Regex(
    """@receiver\s*\(\s*([\w.]+)(?:\s*,\s*sender\s*=\s*(\w+))?\s*\)\s*\ndef\s+(\w+)""",
    RegexOption.MULTILINE
)
private val CONNECT_PATTERN = Regex(
    """([\w.]+)\.connect\s*\(\s*(\w+)"""
)

internal suspend fun executeSignals(params: JsonObject, project: Project): ToolResult {
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
            val signalFiles = PythonFileScanner.scanPythonFiles(baseDir) {
                it.extension == "py" &&
                    (it.name == "signals.py" || it.name == "receivers.py" || it.name == "apps.py")
            }

            if (signalFiles.isEmpty()) {
                return@withContext ToolResult(
                    "No signal files found (signals.py, receivers.py, apps.py).",
                    "No signal files found",
                    5
                )
            }

            val allSignals = mutableListOf<SignalEntry>()

            for (signalFile in signalFiles) {
                val content = signalFile.readText()
                val relPath = PythonFileScanner.relPath(signalFile, basePath)

                RECEIVER_DECORATOR_PATTERN.findAll(content).forEach { match ->
                    val signal = match.groupValues[1]
                    val handler = match.groupValues[3]
                    allSignals.add(SignalEntry(relPath, signal, handler))
                }

                CONNECT_PATTERN.findAll(content).forEach { match ->
                    val signal = match.groupValues[1]
                    val handler = match.groupValues[2]
                    if (allSignals.none { it.file == relPath && it.handler == handler }) {
                        allSignals.add(SignalEntry(relPath, signal, handler))
                    }
                }
            }

            val filtered = if (filter != null) {
                allSignals.filter { s ->
                    s.signal.contains(filter, ignoreCase = true) ||
                        s.handler.contains(filter, ignoreCase = true) ||
                        s.file.contains(filter, ignoreCase = true)
                }
            } else {
                allSignals
            }

            if (filtered.isEmpty()) {
                val filterDesc = if (filter != null) " matching '$filter'" else ""
                return@withContext ToolResult("No signal handlers found$filterDesc.", "No signals", 5)
            }

            val content = buildString {
                appendLine("Django signal handlers (${filtered.size} total):")
                appendLine()
                val byFile = filtered.groupBy { it.file }
                for ((file, signals) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for (sig in signals.sortedBy { it.handler }) {
                        appendLine("  ${sig.signal} -> ${sig.handler}()")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${filtered.size} signal handlers",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading signals: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}
