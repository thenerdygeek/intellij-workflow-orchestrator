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

            val manager = getMavenManager(project)
                ?: return ToolResult("Maven not configured. This tool requires a Maven project.", "No Maven", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

            val mavenProjects = getMavenProjects(manager)
            if (mavenProjects.isEmpty()) {
                return ToolResult("No Maven projects found.", "No Maven projects", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }

            val targetProject = findMavenProject(mavenProjects, manager, moduleFilter)
                ?: return ToolResult(
                    "Module '${moduleFilter}' not found. Available: ${getProjectNames(mavenProjects)}",
                    "Module not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )

            val plugins = getPlugins(targetProject)

            if (plugins.isEmpty()) {
                return ToolResult("No build plugins declared.", "No plugins", 5)
            }

            val content = buildString {
                val projectName = getDisplayName(targetProject)
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

    private fun getMavenManager(project: Project): Any? {
        return try {
            val clazz = Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager")
            val getInstance = clazz.getMethod("getInstance", Project::class.java)
            val manager = getInstance.invoke(null, project)
            val isMaven = clazz.getMethod("isMavenizedProject").invoke(manager) as Boolean
            if (isMaven) manager else null
        } catch (_: Exception) { null }
    }

    private fun getMavenProjects(manager: Any): List<Any> {
        return try {
            @Suppress("UNCHECKED_CAST")
            manager.javaClass.getMethod("getProjects").invoke(manager) as List<Any>
        } catch (_: Exception) { emptyList() }
    }

    private fun findMavenProject(projects: List<Any>, manager: Any, moduleFilter: String?): Any? {
        if (moduleFilter == null) return projects.firstOrNull()
        for (mavenProject in projects) {
            val moduleName = try {
                val managerClass = manager.javaClass
                val findModuleMethod = managerClass.getMethod("findModule", mavenProject.javaClass)
                val module = findModuleMethod.invoke(manager, mavenProject)
                if (module != null) module.javaClass.getMethod("getName").invoke(module) as? String else null
            } catch (_: Exception) { null }

            val displayName = getDisplayName(mavenProject)
            val artifactId = try {
                val mavenId = mavenProject.javaClass.getMethod("getMavenId").invoke(mavenProject)
                mavenId.javaClass.getMethod("getArtifactId").invoke(mavenId) as? String
            } catch (_: Exception) { null }

            if (moduleName == moduleFilter || displayName == moduleFilter || artifactId == moduleFilter) {
                return mavenProject
            }
        }
        return null
    }

    private fun getDisplayName(mavenProject: Any): String {
        return try {
            mavenProject.javaClass.getMethod("getDisplayName").invoke(mavenProject) as? String ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }

    private fun getProjectNames(projects: List<Any>): String {
        return projects.mapNotNull { getDisplayName(it).takeIf { n -> n != "unknown" } }.joinToString(", ")
    }
}
