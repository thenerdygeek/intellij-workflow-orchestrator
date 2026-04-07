package com.workflow.orchestrator.agent.tools.framework.build

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.framework.MavenUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun executeMavenProperties(params: JsonObject, project: Project): ToolResult {
    return try {
        val moduleFilter = params["module"]?.jsonPrimitive?.content
        val searchFilter = params["search"]?.jsonPrimitive?.content?.lowercase()

        val manager = MavenUtils.getMavenManager(project)
            ?: return MavenUtils.noMavenError()

        val mavenProjects = MavenUtils.getMavenProjects(manager)
        if (mavenProjects.isEmpty()) {
            return ToolResult("No Maven projects found.", "No Maven projects", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val targetProject = MavenUtils.findMavenProject(mavenProjects, manager, moduleFilter)
            ?: return ToolResult(
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
            return ToolResult(
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
