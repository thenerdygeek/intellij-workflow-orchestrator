package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class GetRunConfigurationsTool : AgentTool {
    override val name = "get_run_configurations"
    override val description = "List all available run/debug configurations in the project with their settings"
    override val parameters = FunctionParameters(
        properties = mapOf(
            "type_filter" to ParameterProperty(
                type = "string",
                description = "Filter by configuration type",
                enumValues = listOf("application", "spring_boot", "junit", "gradle", "remote_debug")
            )
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        return try {
            val typeFilter = params["type_filter"]?.jsonPrimitive?.content
            val runManager = RunManager.getInstance(project)
            val allSettings = runManager.allSettings

            val filtered = if (typeFilter != null) {
                allSettings.filter { matchesTypeFilter(it.configuration, typeFilter) }
            } else {
                allSettings
            }

            if (filtered.isEmpty()) {
                val filterMsg = if (typeFilter != null) " matching type '$typeFilter'" else ""
                return ToolResult(
                    "No run configurations found$filterMsg.",
                    "No configurations",
                    10
                )
            }

            val selectedName = runManager.selectedConfiguration?.name
            val sb = StringBuilder()
            sb.appendLine("Run Configurations (${filtered.size} total):")
            sb.appendLine()

            for (settings in filtered) {
                val config = settings.configuration
                val isSelected = config.name == selectedName
                val marker = if (isSelected) " [SELECTED]" else ""
                val typeName = resolveTypeName(config)

                sb.appendLine("${config.name}$marker")
                sb.appendLine("  Type: $typeName")

                // Extract main class if available via reflection (ApplicationConfiguration etc.)
                extractMainClass(config)?.let { sb.appendLine("  Main class: $it") }
                extractModule(config)?.let { sb.appendLine("  Module: $it") }
                extractVmOptions(config)?.let { sb.appendLine("  VM options: $it") }
                extractEnvVars(config)?.let { if (it.isNotEmpty()) sb.appendLine("  Env vars: $it") }

                sb.appendLine()
            }

            val content = sb.toString().trimEnd()
            ToolResult(content, "${filtered.size} configurations listed", content.length / 4)
        } catch (e: Exception) {
            ToolResult("Error listing run configurations: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun matchesTypeFilter(config: RunConfiguration, filter: String): Boolean {
        val typeName = config.type.displayName.lowercase()
        val typeId = config.type.id.lowercase()
        return when (filter.lowercase()) {
            "application" -> typeId.contains("application") || typeName.contains("application")
            "spring_boot" -> typeId.contains("spring") || typeName.contains("spring")
            "junit" -> typeId.contains("junit") || typeName.contains("junit") || typeId.contains("test")
            "gradle" -> typeId.contains("gradle") || typeName.contains("gradle")
            "remote_debug" -> typeId.contains("remote") || typeName.contains("remote")
            else -> true
        }
    }

    private fun resolveTypeName(config: RunConfiguration): String {
        return config.type.displayName
    }

    private fun extractMainClass(config: RunConfiguration): String? {
        return try {
            if (config is ApplicationConfiguration) {
                config.mainClassName
            } else {
                // Try via reflection for other config types
                val method = config.javaClass.methods.find {
                    it.name == "getMainClassName" || it.name == "getMainClass"
                }
                method?.invoke(config) as? String
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractModule(config: RunConfiguration): String? {
        return try {
            val method = config.javaClass.methods.find {
                it.name == "getModuleName" || it.name == "getConfigurationModule"
            }
            val result = method?.invoke(config)
            result?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractVmOptions(config: RunConfiguration): String? {
        return try {
            val method = config.javaClass.methods.find {
                it.name == "getVMParameters" || it.name == "getVmParameters"
            }
            (method?.invoke(config) as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractEnvVars(config: RunConfiguration): String? {
        return try {
            val method = config.javaClass.methods.find { it.name == "getEnvs" }
            @Suppress("UNCHECKED_CAST")
            val envs = method?.invoke(config) as? Map<String, String>
            envs?.entries?.joinToString(", ") { "${it.key}=***" }?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
