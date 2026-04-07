package com.workflow.orchestrator.agent.tools.framework.build

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.framework.MavenUtils
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val CONFIG_LINE_LIMIT = 30

private data class ExecutionInfo(
    val executionId: String,
    val phase: String,
    val goals: List<String>
)

private data class PluginConfigInfo(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val configLines: List<String>,
    val executions: List<ExecutionInfo>
)

internal fun executeMavenEffectivePom(params: JsonObject, project: Project): ToolResult {
    return try {
        val moduleFilter = params["module"]?.jsonPrimitive?.content
        val pluginFilter = params["plugin"]?.jsonPrimitive?.content?.lowercase()

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

        val plugins = getPluginConfigs(targetProject)

        val filtered = if (pluginFilter != null) {
            plugins.filter { it.artifactId.lowercase().contains(pluginFilter) }
        } else {
            plugins
        }

        if (filtered.isEmpty()) {
            val filterDesc = if (pluginFilter != null) " matching '$pluginFilter'" else ""
            return ToolResult("No plugin configurations found$filterDesc.", "No plugins", 5)
        }

        val projectName = MavenUtils.getDisplayName(targetProject)
        val content = buildString {
            appendLine("Plugin configurations for $projectName:")
            appendLine()
            for (plugin in filtered.sortedBy { "${it.groupId}:${it.artifactId}" }) {
                val version = if (plugin.version.isNotBlank()) ":${plugin.version}" else ""
                appendLine("${plugin.groupId}:${plugin.artifactId}$version")

                if (plugin.configLines.isNotEmpty()) {
                    appendLine("  Configuration:")
                    for (line in plugin.configLines) {
                        appendLine("    $line")
                    }
                }

                if (plugin.executions.isNotEmpty()) {
                    appendLine("  Executions:")
                    for (exec in plugin.executions) {
                        val phase = if (exec.phase.isNotBlank()) " (phase: ${exec.phase})" else ""
                        val goals = if (exec.goals.isNotEmpty()) " goals: ${exec.goals.joinToString(", ")}" else ""
                        appendLine("    ${exec.executionId}$phase$goals")
                    }
                }

                appendLine()
            }
        }

        ToolResult(
            content = content.trimEnd(),
            summary = "${filtered.size} plugin configurations",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    } catch (e: Exception) {
        ToolResult("Error reading effective POM: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun getPluginConfigs(mavenProject: Any): List<PluginConfigInfo> {
    return try {
        @Suppress("UNCHECKED_CAST")
        val plugins = mavenProject.javaClass.getMethod("getDeclaredPlugins").invoke(mavenProject) as List<Any>
        plugins.mapNotNull { plugin ->
            try {
                val groupId = plugin.javaClass.getMethod("getGroupId").invoke(plugin) as? String ?: return@mapNotNull null
                val artifactId = plugin.javaClass.getMethod("getArtifactId").invoke(plugin) as? String ?: return@mapNotNull null
                val version = plugin.javaClass.getMethod("getVersion").invoke(plugin) as? String ?: ""
                val configLines = extractConfigLines(plugin)
                val executions = extractExecutions(plugin)
                PluginConfigInfo(groupId, artifactId, version, configLines, executions)
            } catch (_: Exception) { null }
        }
    } catch (_: Exception) { emptyList() }
}

private fun extractConfigLines(plugin: Any): List<String> {
    return try {
        val configElement = plugin.javaClass.getMethod("getConfigurationElement").invoke(plugin)
            ?: return emptyList()
        val rawText = configElement.javaClass.getMethod("getText").invoke(configElement) as? String
            ?: return emptyList()
        val lines = rawText.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        if (lines.size > CONFIG_LINE_LIMIT) {
            lines.take(CONFIG_LINE_LIMIT) + listOf("... (${lines.size - CONFIG_LINE_LIMIT} more lines truncated)")
        } else {
            lines
        }
    } catch (_: Exception) { emptyList() }
}

private fun extractExecutions(plugin: Any): List<ExecutionInfo> {
    return try {
        @Suppress("UNCHECKED_CAST")
        val executions = plugin.javaClass.getMethod("getExecutions").invoke(plugin) as List<Any>
        executions.mapNotNull { execution ->
            try {
                val executionId = execution.javaClass.getMethod("getExecutionId").invoke(execution) as? String ?: "default"
                val phase = execution.javaClass.getMethod("getPhase").invoke(execution) as? String ?: ""
                @Suppress("UNCHECKED_CAST")
                val goals = try {
                    execution.javaClass.getMethod("getGoals").invoke(execution) as? List<String> ?: emptyList()
                } catch (_: Exception) { emptyList() }
                ExecutionInfo(executionId, phase, goals)
            } catch (_: Exception) { null }
        }
    } catch (_: Exception) { emptyList() }
}
