package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class MavenPluginsTool : AgentTool {
    override val name = "maven_plugins"
    override val description = "List Maven build plugins with groupId:artifactId:version."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "module" to ParameterProperty(type = "string", description = "Optional: module name to inspect. If omitted, uses the root/first Maven project.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        return try {
            val moduleFilter = params["module"]?.jsonPrimitive?.content

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

            val plugins = getPlugins(targetProject)

            if (plugins.isEmpty()) {
                return ToolResult("No build plugins declared.", "No plugins", 5)
            }

            val content = buildString {
                val projectName = MavenUtils.getDisplayName(targetProject)
                appendLine("Build Plugins for $projectName (${plugins.size}):")
                appendLine()
                for (plugin in plugins.sortedBy { "${it.groupId}:${it.artifactId}" }) {
                    val version = if (plugin.version.isNotBlank()) ":${plugin.version}" else ""
                    appendLine("  ${plugin.groupId}:${plugin.artifactId}$version")
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${plugins.size} plugins",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult("Error listing plugins: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private data class PluginInfo(
        val groupId: String,
        val artifactId: String,
        val version: String
    )

    private fun getPlugins(mavenProject: Any): List<PluginInfo> {
        return try {
            @Suppress("UNCHECKED_CAST")
            val plugins = mavenProject.javaClass.getMethod("getDeclaredPlugins").invoke(mavenProject) as List<Any>
            plugins.mapNotNull { plugin ->
                try {
                    val groupId = plugin.javaClass.getMethod("getGroupId").invoke(plugin) as? String ?: return@mapNotNull null
                    val artifactId = plugin.javaClass.getMethod("getArtifactId").invoke(plugin) as? String ?: return@mapNotNull null
                    val version = plugin.javaClass.getMethod("getVersion").invoke(plugin) as? String ?: ""
                    PluginInfo(groupId, artifactId, version)
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }
}
