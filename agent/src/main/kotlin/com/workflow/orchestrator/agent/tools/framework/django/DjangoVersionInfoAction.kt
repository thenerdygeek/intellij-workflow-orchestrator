package com.workflow.orchestrator.agent.tools.framework.django

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import com.workflow.orchestrator.agent.tools.framework.PythonFileScanner
import java.io.File

private val REQUIREMENTS_DJANGO_PATTERN = Regex(
    """(?i)^django(?:\[[\w,]+\])?\s*(?:==|>=|<=|~=|!=|>|<)\s*([\d.]+)""",
    RegexOption.MULTILINE
)
private val PYPROJECT_DJANGO_PATTERN = Regex(
    """(?i)"django(?:\[[\w,]+\])?\s*(?:==|>=|<=|~=|!=|>|<)\s*([\d.]+)""""
)
private val INSTALLED_APPS_PATTERN = Regex(
    """INSTALLED_APPS\s*=\s*\[([^\]]*)\]""",
    setOf(RegexOption.DOT_MATCHES_ALL)
)
private val APP_ENTRY_PATTERN = Regex("""["']([^"'.]+)["']""")

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

            val djangoVersion = findDjangoVersion(baseDir)

            val settingsFiles = PythonFileScanner.scanPythonFiles(baseDir) {
                it.name.startsWith("settings") && it.extension == "py"
            }

            val installedApps = mutableListOf<String>()
            for (settingsFile in settingsFiles) {
                val content = settingsFile.readText()
                val appsBlock = INSTALLED_APPS_PATTERN.find(content) ?: continue
                APP_ENTRY_PATTERN.findAll(appsBlock.groupValues[1]).forEach { match ->
                    val app = match.groupValues[1]
                    if (!app.startsWith("django.") && !app.startsWith("rest_framework")) {
                        installedApps.add(app)
                    }
                }
            }

            val content = buildString {
                if (djangoVersion != null) {
                    appendLine("Django version: $djangoVersion")
                } else {
                    appendLine("Django version: not found in requirements files")
                }
                appendLine()

                if (installedApps.isNotEmpty()) {
                    appendLine("Project apps (${installedApps.size}):")
                    for (app in installedApps.distinct().sorted()) {
                        appendLine("  $app")
                    }
                } else {
                    appendLine("No project apps found in INSTALLED_APPS.")
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = if (djangoVersion != null) "Django $djangoVersion, ${installedApps.distinct().size} apps"
                else "${installedApps.distinct().size} project apps",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading version info: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun findDjangoVersion(baseDir: File): String? {
    PythonFileScanner.scanPythonFiles(baseDir) {
        it.name.startsWith("requirements") && it.extension == "txt"
    }.forEach { reqFile ->
            REQUIREMENTS_DJANGO_PATTERN.find(reqFile.readText())?.let {
                return it.groupValues[1]
            }
        }

    baseDir.resolve("pyproject.toml").takeIf { it.exists() }?.let { pyproject ->
        PYPROJECT_DJANGO_PATTERN.find(pyproject.readText())?.let {
            return it.groupValues[1]
        }
    }

    return null
}
