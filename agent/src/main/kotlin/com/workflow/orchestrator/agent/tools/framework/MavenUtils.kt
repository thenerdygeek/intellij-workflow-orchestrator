package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.util.ReflectionUtils

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

    fun getMavenManager(project: Project): Any? =
        ReflectionUtils.tryReflective {
            val clazz = Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager")
            val getInstance = clazz.getMethod("getInstance", Project::class.java)
            val manager = getInstance.invoke(null, project)
            val isMaven = clazz.getMethod("isMavenizedProject").invoke(manager) as Boolean
            if (isMaven) manager else null
        }

    fun getMavenProjects(manager: Any): List<Any> =
        ReflectionUtils.tryReflective {
            @Suppress("UNCHECKED_CAST")
            manager.javaClass.getMethod("getProjects").invoke(manager) as List<Any>
        } ?: emptyList()

    fun findMavenProject(projects: List<Any>, manager: Any, moduleFilter: String?): Any? {
        if (moduleFilter == null) return projects.firstOrNull()
        for (mavenProject in projects) {
            val moduleName = ReflectionUtils.tryReflective {
                val managerClass = manager.javaClass
                val findModuleMethod = managerClass.getMethod("findModule", mavenProject.javaClass)
                val module = findModuleMethod.invoke(manager, mavenProject)
                if (module != null) module.javaClass.getMethod("getName").invoke(module) as? String else null
            }

            val displayName = getDisplayName(mavenProject)
            val artifactId = getMavenId(mavenProject, "getArtifactId")

            if (moduleName == moduleFilter || displayName == moduleFilter || artifactId == moduleFilter) {
                return mavenProject
            }
        }
        return null
    }

    fun getDisplayName(mavenProject: Any): String =
        ReflectionUtils.tryReflective {
            mavenProject.javaClass.getMethod("getDisplayName").invoke(mavenProject) as? String
        } ?: "unknown"

    fun getProjectNames(projects: List<Any>): String {
        return projects.mapNotNull { getDisplayName(it).takeIf { n -> n != "unknown" } }.joinToString(", ")
    }

    fun getMavenId(mavenProject: Any, field: String): String? =
        ReflectionUtils.tryReflective {
            val mavenId = mavenProject.javaClass.getMethod("getMavenId").invoke(mavenProject)
            mavenId.javaClass.getMethod(field).invoke(mavenId) as? String
        }

    fun getProperties(mavenProject: Any): Map<String, String> =
        ReflectionUtils.tryReflective {
            val props = mavenProject.javaClass.getMethod("getProperties").invoke(mavenProject) as java.util.Properties
            props.entries.associate { (k, v) -> k.toString() to v.toString() }
        } ?: emptyMap()

    fun getDependencies(mavenProject: Any): List<MavenDependencyInfo> =
        ReflectionUtils.tryReflective {
            @Suppress("UNCHECKED_CAST")
            val deps = mavenProject.javaClass.getMethod("getDependencies").invoke(mavenProject) as List<Any>
            deps.mapNotNull { dep ->
                ReflectionUtils.tryReflective {
                    val groupId = dep.javaClass.getMethod("getGroupId").invoke(dep) as? String
                        ?: return@tryReflective null
                    val artifactId = dep.javaClass.getMethod("getArtifactId").invoke(dep) as? String
                        ?: return@tryReflective null
                    val version = dep.javaClass.getMethod("getVersion").invoke(dep) as? String ?: ""
                    val scope = dep.javaClass.getMethod("getScope").invoke(dep) as? String ?: "compile"
                    MavenDependencyInfo(groupId, artifactId, version, scope)
                }
            }
        } ?: emptyList()

    fun noMavenError(): ToolResult {
        return ToolResult("Maven not configured. This tool requires a Maven project.", "No Maven", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}
