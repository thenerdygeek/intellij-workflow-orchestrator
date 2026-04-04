package com.workflow.orchestrator.agent.tools.config

import com.intellij.execution.RunManager
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Modifies an existing run/debug configuration's settings.
 *
 * Can modify any configuration (user-created or agent-created).
 * The risk level difference (MEDIUM for [Agent] prefix, HIGH for user configs)
 * is handled by ApprovalGate, not this tool.
 */
class ModifyRunConfigTool : AgentTool {
    override val name = "modify_run_config"
    override val description = "Modify an existing run/debug configuration settings"
    override val parameters = FunctionParameters(
        properties = mapOf(
            "name" to ParameterProperty(
                type = "string",
                description = "Exact name of the configuration to modify"
            ),
            "env_vars" to ParameterProperty(
                type = "object",
                description = "Environment variables as key-value pairs (replaces existing)"
            ),
            "vm_options" to ParameterProperty(
                type = "string",
                description = "JVM options to set"
            ),
            "program_args" to ParameterProperty(
                type = "string",
                description = "Program arguments to set"
            ),
            "working_dir" to ParameterProperty(
                type = "string",
                description = "Working directory to set"
            ),
            "active_profiles" to ParameterProperty(
                type = "string",
                description = "Spring Boot active profiles, comma-separated"
            )
        ),
        required = listOf("name")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val configName = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Missing required parameter: name",
                "Error: missing name",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val envVars = params["env_vars"]?.jsonObject?.let { obj ->
            obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
        }
        val vmOptions = params["vm_options"]?.jsonPrimitive?.contentOrNull
        val programArgs = params["program_args"]?.jsonPrimitive?.contentOrNull
        val workingDir = params["working_dir"]?.jsonPrimitive?.contentOrNull
        val activeProfiles = params["active_profiles"]?.jsonPrimitive?.contentOrNull

        // Check that at least one modification is provided
        if (envVars == null && vmOptions == null && programArgs == null && workingDir == null && activeProfiles == null) {
            return ToolResult(
                "No modifications specified. Provide at least one of: env_vars, vm_options, program_args, working_dir, active_profiles",
                "Error: no modifications",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return try {
            val runManager = RunManager.getInstance(project)
            val settings = runManager.findConfigurationByName(configName)
                ?: return ToolResult(
                    "Configuration '$configName' not found. Use get_run_configurations to list available configs.",
                    "Error: config not found",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )

            val config = settings.configuration
            val changes = mutableListOf<String>()

            // Apply modifications
            envVars?.let {
                applyEnvVars(config, it)
                changes.add("env_vars (${it.size} vars)")
            }
            vmOptions?.let {
                applyVmOptions(config, it)
                changes.add("vm_options")
            }
            programArgs?.let {
                applyProgramArgs(config, it)
                changes.add("program_args")
            }
            workingDir?.let {
                applyWorkingDir(config, it)
                changes.add("working_dir")
            }
            activeProfiles?.let {
                applyActiveProfiles(config, it)
                changes.add("active_profiles")
            }

            // Persist changes on EDT
            ApplicationManager.getApplication().invokeAndWait {
                runManager.addConfiguration(settings)
            }

            val content = buildString {
                appendLine("Modified configuration '$configName'")
                appendLine("  Changes applied: ${changes.joinToString(", ")}")
                vmOptions?.let { appendLine("  VM options: $it") }
                programArgs?.let { appendLine("  Program args: $it") }
                workingDir?.let { appendLine("  Working dir: $it") }
                envVars?.let { if (it.isNotEmpty()) appendLine("  Env vars: ${it.keys.joinToString(", ")}") }
                activeProfiles?.let { appendLine("  Active profiles: $it") }
            }.trimEnd()

            ToolResult(
                content,
                "Modified config '$configName': ${changes.joinToString(", ")}",
                TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult(
                "Error modifying run configuration: ${e.message}",
                "Error modifying config",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    private fun applyEnvVars(config: RunConfiguration, envVars: Map<String, String>) {
        if (config is ApplicationConfiguration) {
            config.envs = envVars
        } else {
            try {
                val method = config.javaClass.methods.find { it.name == "setEnvs" && it.parameterCount == 1 }
                method?.invoke(config, envVars)
            } catch (_: Exception) {
                // Best effort
            }
        }
    }

    private fun applyVmOptions(config: RunConfiguration, vmOptions: String) {
        if (config is ApplicationConfiguration) {
            config.vmParameters = vmOptions
        } else {
            try {
                val method = config.javaClass.methods.find {
                    (it.name == "setVMParameters" || it.name == "setVmParameters" || it.name == "setVmOptions")
                        && it.parameterCount == 1
                }
                method?.invoke(config, vmOptions)
            } catch (_: Exception) {
                // Best effort
            }
        }
    }

    private fun applyProgramArgs(config: RunConfiguration, args: String) {
        if (config is ApplicationConfiguration) {
            config.programParameters = args
        } else {
            try {
                val method = config.javaClass.methods.find {
                    (it.name == "setProgramParameters" || it.name == "setRawCommandLine")
                        && it.parameterCount == 1
                }
                method?.invoke(config, args)
            } catch (_: Exception) {
                // Best effort
            }
        }
    }

    private fun applyWorkingDir(config: RunConfiguration, dir: String) {
        if (config is ApplicationConfiguration) {
            config.workingDirectory = dir
        } else {
            try {
                val method = config.javaClass.methods.find {
                    it.name == "setWorkingDirectory" && it.parameterCount == 1
                }
                method?.invoke(config, dir)
            } catch (_: Exception) {
                // Best effort
            }
        }
    }

    private fun applyActiveProfiles(config: RunConfiguration, profiles: String) {
        try {
            val method = config.javaClass.methods.find {
                it.name == "setActiveProfiles" && it.parameterCount == 1
            }
            method?.invoke(config, profiles)
        } catch (_: Exception) {
            // Only applicable to Spring Boot configs
        }
    }
}
