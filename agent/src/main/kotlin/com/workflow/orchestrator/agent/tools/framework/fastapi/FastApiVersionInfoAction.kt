package com.workflow.orchestrator.agent.tools.framework.fastapi

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import com.workflow.orchestrator.agent.tools.framework.PythonFileScanner
import java.io.File

private val TARGET_PACKAGES = listOf(
    "fastapi", "uvicorn", "pydantic", "sqlalchemy", "tortoise-orm",
    "alembic", "httpx", "starlette", "python-multipart", "python-jose",
    "passlib", "databases"
)

private fun buildRequirementsPattern(): Regex = Regex(
    """(?i)^(${TARGET_PACKAGES.joinToString("|") { Regex.escape(it) }})(?:\[[\w,]+\])?\s*(?:==|>=|<=|~=|!=|>|<)\s*([\d.]+)""",
    RegexOption.MULTILINE
)

private fun buildPyprojectPattern(): Regex = Regex(
    """(?i)"(${TARGET_PACKAGES.joinToString("|") { Regex.escape(it) }})(?:\[[\w,]+\])?\s*(?:==|>=|<=|~=|!=|>|<)\s*([\d.]+)""""
)

internal suspend fun executeVersionInfo(params: JsonObject, project: Project): ToolResult {
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
            val versions = mutableMapOf<String, String>()

            val reqPattern = buildRequirementsPattern()
            PythonFileScanner.scanPythonFiles(baseDir) {
                it.name.startsWith("requirements") && it.extension == "txt"
            }.forEach { reqFile ->
                    for (match in reqPattern.findAll(reqFile.readText())) {
                        val pkg = match.groupValues[1].lowercase()
                        val ver = match.groupValues[2]
                        versions.putIfAbsent(pkg, ver)
                    }
                }

            val pyprojectPattern = buildPyprojectPattern()
            baseDir.resolve("pyproject.toml").takeIf { it.exists() }?.let { pyproject ->
                for (match in pyprojectPattern.findAll(pyproject.readText())) {
                    val pkg = match.groupValues[1].lowercase()
                    val ver = match.groupValues[2]
                    versions.putIfAbsent(pkg, ver)
                }
            }

            baseDir.resolve("setup.cfg").takeIf { it.exists() }?.let { setupCfg ->
                for (match in reqPattern.findAll(setupCfg.readText())) {
                    val pkg = match.groupValues[1].lowercase()
                    val ver = match.groupValues[2]
                    versions.putIfAbsent(pkg, ver)
                }
            }

            if (versions.isEmpty()) {
                return@withContext ToolResult(
                    "No FastAPI-related package versions found in dependency files.",
                    "No versions found",
                    5
                )
            }

            val content = buildString {
                appendLine("FastAPI project dependency versions:")
                appendLine()
                // Show fastapi first if present, then alphabetical
                val sortedKeys = versions.keys.sortedWith(compareBy<String> { it != "fastapi" }.thenBy { it })
                for (pkg in sortedKeys) {
                    appendLine("  $pkg==${versions[pkg]}")
                }
            }

            val fastapiVer = versions["fastapi"]
            val summaryPrefix = if (fastapiVer != null) "FastAPI $fastapiVer" else "FastAPI version unknown"

            ToolResult(
                content = content.trimEnd(),
                summary = "$summaryPrefix, ${versions.size} package(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading version info: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}
