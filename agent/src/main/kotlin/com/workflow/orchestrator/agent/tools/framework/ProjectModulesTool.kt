package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject

class ProjectModulesTool : AgentTool {
    override val name = "project_modules"
    override val description = "List project modules: name, path, source directories. For Maven projects, shows groupId:artifactId:version and dependencies."
    override val parameters = FunctionParameters(properties = emptyMap(), required = emptyList())
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        return try {
            val modules = ModuleManager.getInstance(project).modules
            if (modules.isEmpty()) {
                return ToolResult("No modules found in project.", "No modules", 5)
            }

            // Try Maven integration (may not be available)
            val mavenInfo = tryGetMavenInfo(project)

            val content = buildString {
                appendLine("Project modules (${modules.size}):")
                appendLine()

                for (module in modules.sortedBy { it.name }) {
                    appendLine("Module: ${module.name}")

                    // Maven info if available
                    val maven = mavenInfo[module.name]
                    if (maven != null) {
                        appendLine("  Maven: ${maven.groupId}:${maven.artifactId}:${maven.version}")
                        if (maven.packaging.isNotBlank() && maven.packaging != "jar") {
                            appendLine("  Packaging: ${maven.packaging}")
                        }
                    }

                    // Source roots
                    val rootManager = ModuleRootManager.getInstance(module)
                    val sourceRoots = rootManager.sourceRoots
                    val contentRoots = rootManager.contentRoots

                    if (contentRoots.isNotEmpty()) {
                        val modulePath = contentRoots.first().path
                        val relativePath = project.basePath?.let { base ->
                            if (modulePath.startsWith(base)) modulePath.removePrefix("$base/") else modulePath
                        } ?: modulePath
                        appendLine("  Path: $relativePath")
                    }

                    if (sourceRoots.isNotEmpty()) {
                        appendLine("  Sources:")
                        sourceRoots.take(10).forEach { root ->
                            val relativePath = project.basePath?.let { base ->
                                if (root.path.startsWith(base)) root.path.removePrefix("$base/") else root.path
                            } ?: root.path
                            val isTest = rootManager.fileIndex.isInTestSourceContent(root)
                            val tag = if (isTest) "test" else "main"
                            appendLine("    [$tag] $relativePath")
                        }
                        if (sourceRoots.size > 10) {
                            appendLine("    ... and ${sourceRoots.size - 10} more")
                        }
                    }

                    // Dependencies count from Maven
                    if (maven != null && maven.dependencyCount > 0) {
                        appendLine("  Dependencies: ${maven.dependencyCount}")
                    }

                    appendLine()
                }
            }

            ToolResult(
                content = content,
                summary = "${modules.size} module(s)",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult("Error listing modules: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
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

                // Find the IntelliJ module name for this Maven project
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
            // Maven plugin not available
            emptyMap()
        }
    }

    private data class MavenModuleInfo(
        val groupId: String,
        val artifactId: String,
        val version: String,
        val packaging: String,
        val dependencyCount: Int
    )
}
