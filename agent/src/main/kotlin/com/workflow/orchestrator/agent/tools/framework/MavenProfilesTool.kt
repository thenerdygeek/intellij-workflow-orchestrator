package com.workflow.orchestrator.agent.tools.framework

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject

class MavenProfilesTool : AgentTool {
    override val name = "maven_profiles"
    override val description = "List Maven profiles — active and available."
    override val parameters = FunctionParameters(properties = emptyMap(), required = emptyList())
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        return try {
            val manager = MavenUtils.getMavenManager(project)
                ?: return MavenUtils.noMavenError()

            val mavenProjects = MavenUtils.getMavenProjects(manager)
            if (mavenProjects.isEmpty()) {
                return ToolResult("No Maven projects found.", "No Maven projects", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }

            // Get explicit profiles from manager (active/inactive toggles)
            val activeProfiles = getExplicitProfiles(manager, enabled = true)
            val disabledProfiles = getExplicitProfiles(manager, enabled = false)

            // Scan all Maven projects for available profiles defined in POMs
            val availableProfiles = mutableSetOf<String>()
            for (mavenProject in mavenProjects) {
                availableProfiles.addAll(getProfilesFromModel(mavenProject))
            }

            val content = buildString {
                appendLine("Maven Profiles:")
                appendLine()

                if (activeProfiles.isNotEmpty()) {
                    appendLine("Active profiles: ${activeProfiles.joinToString(", ")}")
                } else {
                    appendLine("Active profiles: (none explicitly activated)")
                }

                if (disabledProfiles.isNotEmpty()) {
                    appendLine("Disabled profiles: ${disabledProfiles.joinToString(", ")}")
                }

                if (availableProfiles.isNotEmpty()) {
                    appendLine("Available profiles (from POMs): ${availableProfiles.sorted().joinToString(", ")}")
                } else {
                    appendLine("Available profiles: (none defined in POMs)")
                }
            }

            ToolResult(
                content = content.trimEnd(),
                summary = "${availableProfiles.size} profiles available",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult("Error reading profiles: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun getExplicitProfiles(manager: Any, enabled: Boolean): List<String> {
        return try {
            val explicitProfiles = manager.javaClass.getMethod("getExplicitProfiles").invoke(manager)
            val methodName = if (enabled) "getEnabledProfiles" else "getDisabledProfiles"
            @Suppress("UNCHECKED_CAST")
            val profiles = explicitProfiles.javaClass.getMethod(methodName).invoke(explicitProfiles) as Collection<String>
            profiles.toList().sorted()
        } catch (_: Exception) { emptyList() }
    }

    private fun getProfilesFromModel(mavenProject: Any): List<String> {
        return try {
            val model = try {
                mavenProject.javaClass.getMethod("getMavenModel").invoke(mavenProject)
            } catch (_: Exception) {
                mavenProject.javaClass.getMethod("getModel").invoke(mavenProject)
            }
            @Suppress("UNCHECKED_CAST")
            val profiles = model.javaClass.getMethod("getProfiles").invoke(model) as List<Any>
            profiles.mapNotNull { profile ->
                try {
                    profile.javaClass.getMethod("getId").invoke(profile) as? String
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }
}
