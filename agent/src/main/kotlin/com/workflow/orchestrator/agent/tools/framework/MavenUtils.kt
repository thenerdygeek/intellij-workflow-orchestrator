package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult

/**
 * Shared Maven reflection helpers used by all Maven-related agent tools.
 * All methods use reflection to access Maven plugin classes that may or may not be present.
 */
object MavenUtils {

    data class MavenDependencyInfo(
        val groupId: String,
        val artifactId: String,
        val version: String,
        val scope: String = "compile"
    )

    fun getMavenManager(project: Project): Any? {
        return try {
            val clazz = Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager")
            val getInstance = clazz.getMethod("getInstance", Project::class.java)
            val manager = getInstance.invoke(null, project)
            val isMaven = clazz.getMethod("isMavenizedProject").invoke(manager) as Boolean
            if (isMaven) manager else null
        } catch (_: Exception) { null }
    }

    fun getMavenProjects(manager: Any): List<Any> {
        return try {
            @Suppress("UNCHECKED_CAST")
            manager.javaClass.getMethod("getProjects").invoke(manager) as List<Any>
        } catch (_: Exception) { emptyList() }
    }

    fun findMavenProject(projects: List<Any>, manager: Any, moduleFilter: String?): Any? {
        if (moduleFilter == null) return projects.firstOrNull()
        for (mavenProject in projects) {
            val moduleName = try {
                val managerClass = manager.javaClass
                val findModuleMethod = managerClass.getMethod("findModule", mavenProject.javaClass)
                val module = findModuleMethod.invoke(manager, mavenProject)
                if (module != null) module.javaClass.getMethod("getName").invoke(module) as? String else null
            } catch (_: Exception) { null }

            val displayName = getDisplayName(mavenProject)
            val artifactId = getMavenId(mavenProject, "getArtifactId")

            if (moduleName == moduleFilter || displayName == moduleFilter || artifactId == moduleFilter) {
                return mavenProject
            }
        }
        return null
    }

    fun getDisplayName(mavenProject: Any): String {
        return try {
            mavenProject.javaClass.getMethod("getDisplayName").invoke(mavenProject) as? String ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }

    fun getProjectNames(projects: List<Any>): String {
        return projects.mapNotNull { getDisplayName(it).takeIf { n -> n != "unknown" } }.joinToString(", ")
    }

    fun getMavenId(mavenProject: Any, field: String): String? {
        return try {
            val mavenId = mavenProject.javaClass.getMethod("getMavenId").invoke(mavenProject)
            mavenId.javaClass.getMethod(field).invoke(mavenId) as? String
        } catch (_: Exception) { null }
    }

    fun getProperties(mavenProject: Any): Map<String, String> {
        return try {
            val props = mavenProject.javaClass.getMethod("getProperties").invoke(mavenProject) as java.util.Properties
            props.entries.associate { (k, v) -> k.toString() to v.toString() }
        } catch (_: Exception) { emptyMap() }
    }

    fun getDependencies(mavenProject: Any): List<MavenDependencyInfo> {
        return try {
            @Suppress("UNCHECKED_CAST")
            val deps = mavenProject.javaClass.getMethod("getDependencies").invoke(mavenProject) as List<Any>
            deps.mapNotNull { dep ->
                try {
                    val groupId = dep.javaClass.getMethod("getGroupId").invoke(dep) as? String ?: return@mapNotNull null
                    val artifactId = dep.javaClass.getMethod("getArtifactId").invoke(dep) as? String ?: return@mapNotNull null
                    val version = dep.javaClass.getMethod("getVersion").invoke(dep) as? String ?: ""
                    val scope = dep.javaClass.getMethod("getScope").invoke(dep) as? String ?: "compile"
                    MavenDependencyInfo(groupId, artifactId, version, scope)
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun noMavenError(): ToolResult {
        return ToolResult("Maven not configured. This tool requires a Maven project.", "No Maven", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}
