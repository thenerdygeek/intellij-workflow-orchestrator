package com.workflow.orchestrator.agent.tools.framework.build

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.framework.MavenUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal suspend fun executeMavenProperties(params: JsonObject, project: Project): ToolResult = readAction {
    try {
        val moduleFilter = params["module"]?.jsonPrimitive?.content
        val searchFilter = params["search"]?.jsonPrimitive?.content?.lowercase()

        val manager = MavenUtils.getMavenManager(project)
            ?: return@readAction MavenUtils.noMavenError()

        val mavenProjects = MavenUtils.getMavenProjects(manager)
        if (mavenProjects.isEmpty()) {
            return@readAction ToolResult("No Maven projects found.", "No Maven projects", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val targetProject = MavenUtils.findMavenProject(mavenProjects, manager, moduleFilter)
            ?: return@readAction ToolResult(
                "Module '${moduleFilter}' not found. Available: ${MavenUtils.getProjectNames(mavenProjects)}",
                "Module not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )

        val properties = MavenUtils.getProperties(targetProject)

        val filtered = if (searchFilter != null) {
            properties.filter { (key, _) -> key.lowercase().contains(searchFilter) }
        } else {
            properties
        }

        if (filtered.isEmpty()) {
            return@readAction ToolResult(
                if (searchFilter != null) "No properties matching '$searchFilter'." else "No properties found.",
                "No properties", 5
            )
        }

        val content = buildString {
            val projectName = MavenUtils.getDisplayName(targetProject)
            appendLine("Maven properties for $projectName (${filtered.size}):")
            appendLine()
            for ((key, value) in filtered.toSortedMap()) {
                appendLine("$key = $value")
            }
        }

        ToolResult(
            content = content.trimEnd(),
            summary = "${filtered.size} properties",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    } catch (e: Exception) {
        ToolResult("Error reading properties: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}
