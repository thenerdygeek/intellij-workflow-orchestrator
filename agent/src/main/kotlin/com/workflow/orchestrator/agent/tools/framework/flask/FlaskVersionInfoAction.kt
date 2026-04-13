package com.workflow.orchestrator.agent.tools.framework.flask

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import com.workflow.orchestrator.agent.tools.framework.PythonFileScanner
import java.io.File

private data class DepVersion(val name: String, val version: String)

private val REQUIREMENTS_PATTERN = Regex(
    """(?i)^(flask|werkzeug|jinja2|flask-sqlalchemy|flask-migrate|flask-wtf|flask-login|flask-cors|flask-restful|flask-marshmallow|celery|gunicorn|sqlalchemy)(?:\[[\w,]+\])?\s*(?:==|>=|<=|~=|!=|>|<)\s*([\d.]+)""",
    RegexOption.MULTILINE
)
private val PYPROJECT_DEP_PATTERN = Regex(
    """(?i)"(flask|werkzeug|jinja2|flask-sqlalchemy|flask-migrate|flask-wtf|flask-login|flask-cors|flask-restful|flask-marshmallow|celery|gunicorn|sqlalchemy)(?:\[[\w,]+\])?\s*(?:==|>=|<=|~=|!=|>|<)\s*([\d.]+)""""
)
private val PIPFILE_PATTERN = Regex(
    """(?i)(flask|werkzeug|jinja2|flask-sqlalchemy|flask-migrate|flask-wtf|flask-login|flask-cors|flask-restful|flask-marshmallow|celery|gunicorn|sqlalchemy)\s*=\s*["']==([\d.]+)["']""",
    RegexOption.MULTILINE
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
            val versions = mutableListOf<DepVersion>()

            // Check requirements*.txt
            PythonFileScanner.scanPythonFiles(baseDir) {
                it.name.startsWith("requirements") && it.extension == "txt"
            }.forEach { reqFile ->
                    for (match in REQUIREMENTS_PATTERN.findAll(reqFile.readText())) {
                        versions.add(DepVersion(match.groupValues[1].lowercase(), match.groupValues[2]))
                    }
                }

            // Check pyproject.toml
            baseDir.resolve("pyproject.toml").takeIf { it.exists() }?.let { pyproject ->
                for (match in PYPROJECT_DEP_PATTERN.findAll(pyproject.readText())) {
                    versions.add(DepVersion(match.groupValues[1].lowercase(), match.groupValues[2]))
                }
            }

            // Check Pipfile
            baseDir.resolve("Pipfile").takeIf { it.exists() }?.let { pipfile ->
                for (match in PIPFILE_PATTERN.findAll(pipfile.readText())) {
                    versions.add(DepVersion(match.groupValues[1].lowercase(), match.groupValues[2]))
                }
            }

            // Check setup.py / setup.cfg
            baseDir.resolve("setup.py").takeIf { it.exists() }?.let { setup ->
                for (match in REQUIREMENTS_PATTERN.findAll(setup.readText())) {
                    versions.add(DepVersion(match.groupValues[1].lowercase(), match.groupValues[2]))
                }
            }

            if (versions.isEmpty()) {
                return@withContext ToolResult(
                    "No Flask/Werkzeug/Jinja2 versions found in dependency files.",
                    "No version info found",
                    5
                )
            }

            val deduped = versions.distinctBy { it.name }.sortedBy { it.name }

            val content = buildString {
                appendLine("Flask ecosystem versions:")
                appendLine()
                val core = deduped.filter { it.name in setOf("flask", "werkzeug", "jinja2") }
                val extensions = deduped.filter { it.name !in setOf("flask", "werkzeug", "jinja2") }

                if (core.isNotEmpty()) {
                    appendLine("Core:")
                    for (dep in core) {
                        appendLine("  ${dep.name} == ${dep.version}")
                    }
                    appendLine()
                }

                if (extensions.isNotEmpty()) {
                    appendLine("Extensions & libraries:")
                    for (dep in extensions) {
                        appendLine("  ${dep.name} == ${dep.version}")
                    }
                }
            }

            val flaskVersion = deduped.find { it.name == "flask" }?.version
            ToolResult(
                content = content.trimEnd(),
                summary = if (flaskVersion != null) "Flask $flaskVersion, ${deduped.size} packages"
                else "${deduped.size} Flask ecosystem packages",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }
    } catch (e: Exception) {
        ToolResult("Error reading version info: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}
