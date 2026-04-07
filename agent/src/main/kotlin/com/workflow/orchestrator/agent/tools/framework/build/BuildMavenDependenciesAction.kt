package com.workflow.orchestrator.agent.tools.framework.build

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.framework.MavenUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun executeMavenDependencies(params: JsonObject, project: Project): ToolResult {
    return try {
        val moduleFilter = params["module"]?.jsonPrimitive?.content
        val scopeFilter = params["scope"]?.jsonPrimitive?.content?.lowercase()
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

        val dependencies = MavenUtils.getDependencies(targetProject)

        val filtered = dependencies.filter { dep ->
            val matchesScope = scopeFilter == null || dep.scope.lowercase() == scopeFilter
            val matchesSearch = searchFilter == null ||
                dep.groupId.lowercase().contains(searchFilter) ||
                dep.artifactId.lowercase().contains(searchFilter)
            matchesScope && matchesSearch
        }

        if (filtered.isEmpty()) {
            val filterDesc = buildString {
                if (scopeFilter != null) append(" scope=$scopeFilter")
                if (searchFilter != null) append(" search=$searchFilter")
            }
            return ToolResult("No dependencies found matching:$filterDesc", "No matches", 5)
        }

        val grouped = filtered.groupBy { it.scope.ifBlank { "compile" } }
            .toSortedMap(compareBy { mavenScopeOrder(it) })

        val content = buildString {
            val projectName = MavenUtils.getDisplayName(targetProject)
            appendLine("Dependencies for $projectName (${filtered.size} total):")
            appendLine()
            for ((scope, deps) in grouped) {
                appendLine("$scope (${deps.size}):")
                for (dep in deps.sortedBy { "${it.groupId}:${it.artifactId}" }) {
                    val version = if (dep.version.isNotBlank()) ":${dep.version}" else ""
                    appendLine("  ${dep.groupId}:${dep.artifactId}$version")
                }
                appendLine()
            }
        }

        ToolResult(
            content = content.trimEnd(),
            summary = "${filtered.size} dependencies",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    } catch (e: Exception) {
        ToolResult("Error listing dependencies: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun mavenScopeOrder(scope: String): Int = when (scope.lowercase()) {
    "compile" -> 0; "provided" -> 1; "runtime" -> 2; "test" -> 3; "system" -> 4; else -> 5
}
