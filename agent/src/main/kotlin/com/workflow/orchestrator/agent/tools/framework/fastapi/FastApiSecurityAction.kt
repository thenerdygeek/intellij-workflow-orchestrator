package com.workflow.orchestrator.agent.tools.framework.fastapi

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import com.workflow.orchestrator.agent.tools.framework.PythonFileScanner
import java.io.File

private data class SecurityEntry(
    val file: String,
    val scheme: String,
    val variableName: String,
    val detail: String,
    val lineNumber: Int
)

private val SECURITY_SCHEMES = listOf(
    "OAuth2PasswordBearer",
    "OAuth2PasswordRequestForm",
    "OAuth2AuthorizationCodeBearer",
    "APIKeyHeader",
    "APIKeyQuery",
    "APIKeyCookie",
    "HTTPBearer",
    "HTTPBasic",
    "HTTPBasicCredentials",
    "HTTPAuthorizationCredentials",
    "SecurityScopes"
)

private val SECURITY_PATTERN = Regex(
    """(\w+)\s*=\s*(${SECURITY_SCHEMES.joinToString("|")})\s*\(([^)]*)\)"""
)

internal suspend fun executeSecurity(params: JsonObject, project: Project): ToolResult {
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

            val securityEntries = mutableListOf<SecurityEntry>()
            for (pyFile in pyFiles) {
                parseSecurity(pyFile, basePath, securityEntries)
            }

            if (securityEntries.isEmpty()) {
                return@withContext ToolResult(
                    "No FastAPI security schemes found in project.",
                    "No security schemes found",
                    5
                )
            }

            val content = buildString {
                appendLine("FastAPI security schemes (${securityEntries.size} found):")
                appendLine()
                val byFile = securityEntries.groupBy { it.file }
                for ((file, entries) in byFile.toSortedMap()) {
                    appendLine("[$file]")
                    for (entry in entries.sortedBy { it.lineNumber }) {
                        val detailStr = if (entry.detail.isNotBlank()) " (${entry.detail})" else ""
                        appendLine("  ${entry.variableName} = ${entry.scheme}$detailStr")
                    }
                    appendLine()
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${securityEntries.size} security scheme(s) across ${securityEntries.map { it.file }.distinct().size} file(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading security: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun parseSecurity(pyFile: File, basePath: String, results: MutableList<SecurityEntry>) {
    val content = pyFile.readText()
    val relPath = PythonFileScanner.relPath(pyFile, basePath)
    val lines = content.lines()

    for ((index, line) in lines.withIndex()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#")) continue

        val match = SECURITY_PATTERN.find(trimmed) ?: continue
        val variableName = match.groupValues[1]
        val scheme = match.groupValues[2]
        val detail = match.groupValues[3].trim()
        results.add(SecurityEntry(relPath, scheme, variableName, detail, index + 1))
    }
}
