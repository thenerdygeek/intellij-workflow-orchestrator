package com.workflow.orchestrator.agent.tools.framework.build

import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.serialization.json.JsonObject

private data class MavenModuleInfo(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val packaging: String,
    val dependencyCount: Int
)

private data class SourceRootSnapshot(val relativePath: String, val isTest: Boolean)

private data class ModuleSnapshot(
    val name: String,
    val contentRootRelativePath: String?,
    val sourceRoots: List<SourceRootSnapshot>,
    val sourceRootOverflow: Int
)

private data class ProjectModulesSnapshot(
    val moduleCount: Int,
    val modules: List<ModuleSnapshot>,
    val mavenInfo: Map<String, MavenModuleInfo>
)

internal suspend fun executeProjectModules(params: JsonObject, project: Project): ToolResult {
    return try {
        val snapshot = readAction { collectProjectModulesSnapshot(project) }
        if (snapshot.moduleCount == 0) {
            return ToolResult("No modules found in project.", "No modules", 5)
        }

        val content = buildString {
            appendLine("Project modules (${snapshot.moduleCount}):")
            appendLine()

            for (module in snapshot.modules) {
                appendLine("Module: ${module.name}")

                val maven = snapshot.mavenInfo[module.name]
                if (maven != null) {
                    appendLine("  Maven: ${maven.groupId}:${maven.artifactId}:${maven.version}")
                    if (maven.packaging.isNotBlank() && maven.packaging != "jar") {
                        appendLine("  Packaging: ${maven.packaging}")
                    }
                }

                module.contentRootRelativePath?.let { appendLine("  Path: $it") }

                if (module.sourceRoots.isNotEmpty()) {
                    appendLine("  Sources:")
                    module.sourceRoots.forEach { root ->
                        val tag = if (root.isTest) "test" else "main"
                        appendLine("    [$tag] ${root.relativePath}")
                    }
                    if (module.sourceRootOverflow > 0) {
                        appendLine("    ... and ${module.sourceRootOverflow} more")
                    }
                }

                if (maven != null && maven.dependencyCount > 0) {
                    appendLine("  Dependencies: ${maven.dependencyCount}")
                }

                appendLine()
            }
        }

        ToolResult(
            content = content,
            summary = "${snapshot.moduleCount} module(s)",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    } catch (e: Exception) {
        ToolResult("Error listing modules: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
    }
}

private fun collectProjectModulesSnapshot(project: Project): ProjectModulesSnapshot {
    val modules = ModuleManager.getInstance(project).modules
    if (modules.isEmpty()) {
        return ProjectModulesSnapshot(0, emptyList(), emptyMap())
    }

    val basePath = project.basePath
    val moduleSnapshots = modules.sortedBy { it.name }.map { module ->
        val rootManager = ModuleRootManager.getInstance(module)
        val contentRoots = rootManager.contentRoots
        val sourceRoots = rootManager.sourceRoots

        val contentRootRelativePath = contentRoots.firstOrNull()?.path?.let { p ->
            basePath?.let { base ->
                if (p.startsWith(base)) p.removePrefix("$base/") else p
            } ?: p
        }

        val sourceRootSnapshots = sourceRoots.take(10).map { root ->
            val relativePath = basePath?.let { base ->
                if (root.path.startsWith(base)) root.path.removePrefix("$base/") else root.path
            } ?: root.path
            SourceRootSnapshot(
                relativePath = relativePath,
                isTest = rootManager.fileIndex.isInTestSourceContent(root)
            )
        }

        ModuleSnapshot(
            name = module.name,
            contentRootRelativePath = contentRootRelativePath,
            sourceRoots = sourceRootSnapshots,
            sourceRootOverflow = (sourceRoots.size - 10).coerceAtLeast(0)
        )
    }

    return ProjectModulesSnapshot(
        moduleCount = modules.size,
        modules = moduleSnapshots,
        mavenInfo = tryGetMavenInfo(project)
    )
}

private fun tryGetMavenInfo(project: Project): Map<String, MavenModuleInfo> {
    return try {
        val mavenManagerClass = Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager")
        val getInstanceMethod = mavenManagerClass.getMethod("getInstance", Project::class.java)
        val manager = getInstanceMethod.invoke(null, project)

        val isMavenized = mavenManagerClass.getMethod("isMavenizedProject").invoke(manager) as Boolean
        if (!isMavenized) return emptyMap()

        @Suppress("UNCHECKED_CAST")
        val projects = mavenManagerClass.getMethod("getProjects").invoke(manager) as List<Any>

        val result = mutableMapOf<String, MavenModuleInfo>()
        for (mavenProject in projects) {
            val mavenIdObj = mavenProject.javaClass.getMethod("getMavenId").invoke(mavenProject)
            val groupId = mavenIdObj.javaClass.getMethod("getGroupId").invoke(mavenIdObj) as? String ?: ""
            val artifactId = mavenIdObj.javaClass.getMethod("getArtifactId").invoke(mavenIdObj) as? String ?: ""
            val version = mavenIdObj.javaClass.getMethod("getVersion").invoke(mavenIdObj) as? String ?: ""

            val displayName = mavenProject.javaClass.getMethod("getDisplayName").invoke(mavenProject) as? String ?: artifactId

            val packaging = try {
                mavenProject.javaClass.getMethod("getPackaging").invoke(mavenProject) as? String ?: "jar"
            } catch (_: Exception) { "jar" }

            val depCount = try {
                @Suppress("UNCHECKED_CAST")
                val deps = mavenProject.javaClass.getMethod("getDependencies").invoke(mavenProject) as List<Any>
                deps.size
            } catch (_: Exception) { 0 }

            val moduleName = try {
                val findModuleMethod = mavenManagerClass.getMethod("findModule", mavenProject.javaClass)
                val module = findModuleMethod.invoke(manager, mavenProject)
                if (module != null) {
                    module.javaClass.getMethod("getName").invoke(module) as? String ?: displayName
                } else {
                    displayName
                }
            } catch (_: Exception) {
                displayName
            }

            result[moduleName] = MavenModuleInfo(
                groupId = groupId,
                artifactId = artifactId,
                version = version,
                packaging = packaging,
                dependencyCount = depCount
            )
        }
        result
    } catch (_: Exception) {
        emptyMap()
    }
}
