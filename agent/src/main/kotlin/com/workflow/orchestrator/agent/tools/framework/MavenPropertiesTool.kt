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

class MavenPropertiesTool : AgentTool {
    override val name = "maven_properties"
    override val description = "Get resolved Maven properties from effective POM. Shows property name → value."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "module" to ParameterProperty(type = "string", description = "Optional: module name to inspect. If omitted, uses the root/first Maven project."),
            "search" to ParameterProperty(type = "string", description = "Optional: filter properties by name substring.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        return try {
            val moduleFilter = params["module"]?.jsonPrimitive?.content
            val searchFilter = params["search"]?.jsonPrimitive?.content?.lowercase()

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

            val properties = getProperties(targetProject)

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
                val projectName = getDisplayName(targetProject)
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

    private fun getProperties(mavenProject: Any): Map<String, String> {
        return try {
            val props = mavenProject.javaClass.getMethod("getProperties").invoke(mavenProject) as java.util.Properties
            props.entries.associate { (k, v) -> k.toString() to v.toString() }
        } catch (_: Exception) { emptyMap() }
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
