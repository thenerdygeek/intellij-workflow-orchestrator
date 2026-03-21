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

class MavenDependenciesTool : AgentTool {
    override val name = "maven_dependencies"
    override val description = "List Maven dependencies with groupId:artifactId:version and scope. Filter by scope or search by name."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "module" to ParameterProperty(type = "string", description = "Optional: module name to inspect. If omitted, uses the root/first Maven project."),
            "scope" to ParameterProperty(type = "string", description = "Optional: filter by scope (compile, test, runtime, provided)."),
            "search" to ParameterProperty(type = "string", description = "Optional: filter dependencies by groupId or artifactId substring.")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        return try {
            val moduleFilter = params["module"]?.jsonPrimitive?.content
            val scopeFilter = params["scope"]?.jsonPrimitive?.content?.lowercase()
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

            val dependencies = getDependencies(targetProject)

            // Apply filters
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

            // Group by scope
            val grouped = filtered.groupBy { it.scope.ifBlank { "compile" } }
                .toSortedMap(compareBy { scopeOrder(it) })

            val content = buildString {
                val projectName = getDisplayName(targetProject)
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

    private fun scopeOrder(scope: String): Int = when (scope.lowercase()) {
        "compile" -> 0
        "provided" -> 1
        "runtime" -> 2
        "test" -> 3
        "system" -> 4
        else -> 5
    }

    private data class DependencyInfo(
        val groupId: String,
        val artifactId: String,
        val version: String,
        val scope: String
    )

    private fun getDependencies(mavenProject: Any): List<DependencyInfo> {
        return try {
            @Suppress("UNCHECKED_CAST")
            val deps = mavenProject.javaClass.getMethod("getDependencies").invoke(mavenProject) as List<Any>
            deps.mapNotNull { dep ->
                try {
                    val groupId = dep.javaClass.getMethod("getGroupId").invoke(dep) as? String ?: return@mapNotNull null
                    val artifactId = dep.javaClass.getMethod("getArtifactId").invoke(dep) as? String ?: return@mapNotNull null
                    val version = dep.javaClass.getMethod("getVersion").invoke(dep) as? String ?: ""
                    val scope = dep.javaClass.getMethod("getScope").invoke(dep) as? String ?: "compile"
                    DependencyInfo(groupId, artifactId, version, scope)
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
        val managerClass = manager.javaClass
        for (mavenProject in projects) {
            val moduleName = try {
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
