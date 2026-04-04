package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.RunManager
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.execution.configurations.ConfigurationFactory
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Run configuration CRUD — create, modify, delete, and list IntelliJ run/debug configurations.
 *
 * Split from the monolithic RuntimeTool to isolate the heavy config creation params
 * (12 params for create_run_config alone) from the simpler execution operations.
 */
class RuntimeConfigTool : AgentTool {

    override val name = "runtime_config"

    override val description = """
IntelliJ run configuration management — create, modify, delete, and list run/debug configurations.

Actions and their parameters:
- get_run_configurations(type_filter?) → List configs (type_filter: application|spring_boot|junit|gradle|remote_debug)
- create_run_config(name, type, main_class?, test_class?, test_method?, module?, env_vars?, vm_options?, program_args?, working_dir?, active_profiles?, port?) → Create config (type: application|spring_boot|junit|gradle|remote_debug; main_class required for application/spring_boot; test_class required for junit)
- modify_run_config(name, env_vars?, vm_options?, program_args?, working_dir?, active_profiles?) → Modify existing config (at least one change required)
- delete_run_config(name) → Delete config (only [Agent]-prefixed configs)

description optional: for approval dialog on create/modify/delete.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "get_run_configurations", "create_run_config", "modify_run_config", "delete_run_config"
                )
            ),
            "type_filter" to ParameterProperty(
                type = "string",
                description = "Filter by configuration type — for get_run_configurations",
                enumValues = listOf("application", "spring_boot", "junit", "gradle", "remote_debug")
            ),
            "name" to ParameterProperty(
                type = "string",
                description = "Configuration name — for create_run_config (auto-prefixed with [Agent]), modify_run_config, delete_run_config"
            ),
            "type" to ParameterProperty(
                type = "string",
                description = "Configuration type — for create_run_config",
                enumValues = listOf("application", "spring_boot", "junit", "gradle", "remote_debug")
            ),
            "main_class" to ParameterProperty(
                type = "string",
                description = "Fully qualified main class (required for application/spring_boot) — for create_run_config"
            ),
            "test_class" to ParameterProperty(
                type = "string",
                description = "Fully qualified test class (required for junit) — for create_run_config"
            ),
            "test_method" to ParameterProperty(
                type = "string",
                description = "Specific test method name (junit only) — for create_run_config"
            ),
            "module" to ParameterProperty(
                type = "string",
                description = "Module name — for create_run_config (auto-detected if omitted)"
            ),
            "env_vars" to ParameterProperty(
                type = "object",
                description = "Environment variables as key-value pairs — for create_run_config, modify_run_config"
            ),
            "vm_options" to ParameterProperty(
                type = "string",
                description = "JVM options — for create_run_config, modify_run_config"
            ),
            "program_args" to ParameterProperty(
                type = "string",
                description = "Program arguments — for create_run_config, modify_run_config"
            ),
            "working_dir" to ParameterProperty(
                type = "string",
                description = "Working directory — for create_run_config, modify_run_config"
            ),
            "active_profiles" to ParameterProperty(
                type = "string",
                description = "Spring Boot active profiles, comma-separated — for create_run_config, modify_run_config"
            ),
            "port" to ParameterProperty(
                type = "integer",
                description = "Remote debug port (default 5005) — for create_run_config"
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description of what this action does and why (shown to user in approval dialog) — for create_run_config, modify_run_config, delete_run_config"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(
        WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return when (action) {
            "get_run_configurations" -> executeGetRunConfigurations(params, project)
            "create_run_config" -> executeCreateRunConfig(params, project)
            "modify_run_config" -> executeModifyRunConfig(params, project)
            "delete_run_config" -> executeDeleteRunConfig(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions: get_run_configurations, create_run_config, modify_run_config, delete_run_config",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: get_run_configurations
    // ══════════════════════════════════════════════════════════════════════

    private fun executeGetRunConfigurations(params: JsonObject, project: Project): ToolResult {
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
                return ToolResult("No run configurations found$filterMsg.", "No configurations", 10)
            }

            val selectedName = runManager.selectedConfiguration?.name
            val sb = StringBuilder()
            sb.appendLine("Run Configurations (${filtered.size} total):")
            sb.appendLine()

            for (settings in filtered) {
                val config = settings.configuration
                val isSelected = config.name == selectedName
                val marker = if (isSelected) " [SELECTED]" else ""
                val typeName = config.type.displayName

                sb.appendLine("${config.name}$marker")
                sb.appendLine("  Type: $typeName")
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

    private fun extractMainClass(config: RunConfiguration): String? {
        return try {
            if (config is ApplicationConfiguration) {
                config.mainClassName
            } else {
                val method = config.javaClass.methods.find {
                    it.name == "getMainClassName" || it.name == "getMainClass"
                }
                method?.invoke(config) as? String
            }
        } catch (_: Exception) { null }
    }

    private fun extractModule(config: RunConfiguration): String? {
        return try {
            val method = config.javaClass.methods.find {
                it.name == "getModuleName" || it.name == "getConfigurationModule"
            }
            val result = method?.invoke(config)
            result?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        } catch (_: Exception) { null }
    }

    private fun extractVmOptions(config: RunConfiguration): String? {
        return try {
            val method = config.javaClass.methods.find {
                it.name == "getVMParameters" || it.name == "getVmParameters"
            }
            (method?.invoke(config) as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    private fun extractEnvVars(config: RunConfiguration): String? {
        return try {
            val method = config.javaClass.methods.find { it.name == "getEnvs" }
            @Suppress("UNCHECKED_CAST")
            val envs = method?.invoke(config) as? Map<String, String>
            envs?.entries?.joinToString(", ") { "${it.key}=***" }?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: create_run_config
    // ══════════════════════════════════════════════════════════════════════

    private fun executeCreateRunConfig(params: JsonObject, project: Project): ToolResult {
        val configName = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult("Missing required parameter: name", "Error: missing name", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val configType = params["type"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult("Missing required parameter: type", "Error: missing type", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val validTypes = listOf("application", "spring_boot", "junit", "gradle", "remote_debug")
        if (configType !in validTypes) {
            return ToolResult(
                "Invalid type '$configType'. Must be one of: ${validTypes.joinToString(", ")}",
                "Error: invalid type", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        val mainClass = params["main_class"]?.jsonPrimitive?.contentOrNull
        val testClass = params["test_class"]?.jsonPrimitive?.contentOrNull
        val testMethod = params["test_method"]?.jsonPrimitive?.contentOrNull
        val module = params["module"]?.jsonPrimitive?.contentOrNull
        val envVars = params["env_vars"]?.jsonObject?.let { obj ->
            obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
        }
        val vmOptions = params["vm_options"]?.jsonPrimitive?.contentOrNull
        val programArgs = params["program_args"]?.jsonPrimitive?.contentOrNull
        val workingDir = params["working_dir"]?.jsonPrimitive?.contentOrNull
        val activeProfiles = params["active_profiles"]?.jsonPrimitive?.contentOrNull
        val port = params["port"]?.jsonPrimitive?.intOrNull

        if (configType in listOf("application", "spring_boot") && mainClass.isNullOrBlank()) {
            return ToolResult("Parameter 'main_class' is required for type '$configType'", "Error: missing main_class", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
        if (configType == "junit" && testClass.isNullOrBlank()) {
            return ToolResult("Parameter 'test_class' is required for type 'junit'", "Error: missing test_class", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val prefixedName = "$AGENT_PREFIX$configName"

        return try {
            val runManager = RunManager.getInstance(project)
            val existing = runManager.findConfigurationByName(prefixedName)
            if (existing != null) {
                return ToolResult(
                    "Configuration '$prefixedName' already exists. Use modify_run_config to update it.",
                    "Error: config exists", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )
            }

            val factory = resolveConfigurationFactory(configType)
                ?: return ToolResult(
                    "Could not resolve configuration factory for type '$configType'. The required plugin may not be installed.",
                    "Error: no factory for $configType", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )

            val settings = runManager.createConfiguration(prefixedName, factory)
            val config = settings.configuration

            applyCreateConfigSettings(
                config, configType, mainClass, testClass, testMethod, module,
                envVars, vmOptions, programArgs, workingDir ?: project.basePath,
                activeProfiles, port ?: 5005
            )

            ApplicationManager.getApplication().invokeAndWait {
                runManager.addConfiguration(settings)
            }

            val content = buildString {
                appendLine("Created run configuration '$prefixedName'")
                appendLine("  Type: $configType")
                mainClass?.let { appendLine("  Main class: $it") }
                testClass?.let { appendLine("  Test class: $it") }
                testMethod?.let { appendLine("  Test method: $it") }
                module?.let { appendLine("  Module: $it") }
                vmOptions?.let { appendLine("  VM options: $it") }
                programArgs?.let { appendLine("  Program args: $it") }
                envVars?.let { if (it.isNotEmpty()) appendLine("  Env vars: ${it.keys.joinToString(", ")}") }
                activeProfiles?.let { appendLine("  Active profiles: $it") }
                if (configType == "remote_debug") appendLine("  Debug port: ${port ?: 5005}")
            }.trimEnd()

            ToolResult(content, "Created config '$prefixedName' ($configType)", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error creating run configuration: ${e.message}", "Error creating config", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun resolveConfigurationFactory(type: String): ConfigurationFactory? {
        return try {
            when (type) {
                "application" -> ApplicationConfigurationType.getInstance().configurationFactories.firstOrNull()
                "spring_boot" -> resolveFactoryViaReflection("com.intellij.spring.boot.run.SpringBootApplicationConfigurationType")
                "junit" -> resolveFactoryViaReflection("com.intellij.execution.junit.JUnitConfigurationType")
                "gradle" -> resolveFactoryViaReflection("org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType")
                "remote_debug" -> resolveFactoryViaReflection("com.intellij.execution.remote.RemoteConfigurationType")
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun resolveFactoryViaReflection(className: String): ConfigurationFactory? {
        return try {
            val clazz = Class.forName(className)
            val getInstance = clazz.getMethod("getInstance")
            val instance = getInstance.invoke(null)
            val getFactories = instance.javaClass.getMethod("getConfigurationFactories")
            @Suppress("UNCHECKED_CAST")
            val factories = getFactories.invoke(instance) as Array<ConfigurationFactory>
            factories.firstOrNull()
        } catch (_: ClassNotFoundException) { null }
        catch (_: Exception) { null }
    }

    @Suppress("LongParameterList")
    private fun applyCreateConfigSettings(
        config: RunConfiguration, configType: String, mainClass: String?,
        testClass: String?, testMethod: String?, module: String?,
        envVars: Map<String, String>?, vmOptions: String?, programArgs: String?,
        workingDir: String?, activeProfiles: String?, port: Int
    ) {
        when (configType) {
            "application" -> applyApplicationConfig(config, mainClass, vmOptions, programArgs, envVars, workingDir)
            "spring_boot" -> applyReflectionConfig(config, mainClass, vmOptions, programArgs, envVars, workingDir, activeProfiles)
            "junit" -> applyJUnitConfig(config, testClass, testMethod, vmOptions, envVars, workingDir)
            "remote_debug" -> applyRemoteConfig(config, port)
            "gradle" -> applyGradleConfig(config, programArgs, vmOptions, envVars, workingDir)
        }
    }

    private fun applyApplicationConfig(
        config: RunConfiguration, mainClass: String?, vmOptions: String?,
        programArgs: String?, envVars: Map<String, String>?, workingDir: String?
    ) {
        if (config is ApplicationConfiguration) {
            mainClass?.let { config.mainClassName = it }
            vmOptions?.let { config.vmParameters = it }
            programArgs?.let { config.programParameters = it }
            envVars?.let { config.envs = it }
            workingDir?.let { config.workingDirectory = it }
        }
    }

    private fun applyReflectionConfig(
        config: RunConfiguration, mainClass: String?, vmOptions: String?,
        programArgs: String?, envVars: Map<String, String>?, workingDir: String?,
        activeProfiles: String?
    ) {
        try {
            mainClass?.let { setViaReflection(config, "setMainClassName", it) }
            vmOptions?.let { setViaReflection(config, "setVMParameters", it) }
            programArgs?.let { setViaReflection(config, "setProgramParameters", it) }
            envVars?.let { setEnvsViaReflection(config, it) }
            workingDir?.let { setViaReflection(config, "setWorkingDirectory", it) }
            activeProfiles?.let { setViaReflection(config, "setActiveProfiles", it) }
        } catch (_: Exception) { }
    }

    private fun applyJUnitConfig(
        config: RunConfiguration, testClass: String?, testMethod: String?,
        vmOptions: String?, envVars: Map<String, String>?, workingDir: String?
    ) {
        try {
            testClass?.let {
                val getPersistentData = config.javaClass.methods.find { m -> m.name == "getPersistentData" }
                val data = getPersistentData?.invoke(config)
                if (data != null) {
                    val testObjectField = data.javaClass.getField("TEST_OBJECT")
                    val mainClassField = data.javaClass.getField("MAIN_CLASS_NAME")
                    val testType = if (testMethod != null) "method" else "class"
                    testObjectField.set(data, testType)
                    mainClassField.set(data, it)
                    testMethod?.let { method ->
                        val methodField = data.javaClass.getField("METHOD_NAME")
                        methodField.set(data, method)
                    }
                }
            }
            vmOptions?.let { setViaReflection(config, "setVMParameters", it) }
            envVars?.let { setEnvsViaReflection(config, it) }
            workingDir?.let { setViaReflection(config, "setWorkingDirectory", it) }
        } catch (_: Exception) { }
    }

    private fun applyRemoteConfig(config: RunConfiguration, port: Int) {
        try {
            val portField = config.javaClass.getField("PORT")
            portField.set(config, port.toString())
            val hostField = config.javaClass.getField("HOST")
            hostField.set(config, "localhost")
            val serverModeField = config.javaClass.getField("SERVER_MODE")
            serverModeField.set(config, false)
        } catch (_: Exception) { }
    }

    private fun applyGradleConfig(
        config: RunConfiguration, programArgs: String?, vmOptions: String?,
        envVars: Map<String, String>?, workingDir: String?
    ) {
        try {
            programArgs?.let { setViaReflection(config, "setRawCommandLine", it) }
            vmOptions?.let { setViaReflection(config, "setVmOptions", it) }
            workingDir?.let { setViaReflection(config, "setWorkingDirectory", it) }
        } catch (_: Exception) { }
    }

    private fun setViaReflection(config: RunConfiguration, methodName: String, value: String) {
        try {
            val method = config.javaClass.methods.find { it.name == methodName && it.parameterCount == 1 }
            method?.invoke(config, value)
        } catch (_: Exception) { }
    }

    private fun setEnvsViaReflection(config: RunConfiguration, envs: Map<String, String>) {
        try {
            val method = config.javaClass.methods.find { it.name == "setEnvs" && it.parameterCount == 1 }
            method?.invoke(config, envs)
        } catch (_: Exception) { }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: modify_run_config
    // ══════════════════════════════════════════════════════════════════════

    private fun executeModifyRunConfig(params: JsonObject, project: Project): ToolResult {
        val configName = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult("Missing required parameter: name", "Error: missing name", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val envVars = params["env_vars"]?.jsonObject?.let { obj ->
            obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
        }
        val vmOptions = params["vm_options"]?.jsonPrimitive?.contentOrNull
        val programArgs = params["program_args"]?.jsonPrimitive?.contentOrNull
        val workingDir = params["working_dir"]?.jsonPrimitive?.contentOrNull
        val activeProfiles = params["active_profiles"]?.jsonPrimitive?.contentOrNull

        if (envVars == null && vmOptions == null && programArgs == null && workingDir == null && activeProfiles == null) {
            return ToolResult(
                "No modifications specified. Provide at least one of: env_vars, vm_options, program_args, working_dir, active_profiles",
                "Error: no modifications", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        return try {
            val runManager = RunManager.getInstance(project)
            val settings = runManager.findConfigurationByName(configName)
                ?: return ToolResult(
                    "Configuration '$configName' not found. Use get_run_configurations to list available configs.",
                    "Error: config not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )

            val config = settings.configuration
            val changes = mutableListOf<String>()

            envVars?.let {
                modifyApplyEnvVars(config, it)
                changes.add("env_vars (${it.size} vars)")
            }
            vmOptions?.let {
                modifyApplyVmOptions(config, it)
                changes.add("vm_options")
            }
            programArgs?.let {
                modifyApplyProgramArgs(config, it)
                changes.add("program_args")
            }
            workingDir?.let {
                modifyApplyWorkingDir(config, it)
                changes.add("working_dir")
            }
            activeProfiles?.let {
                modifyApplyActiveProfiles(config, it)
                changes.add("active_profiles")
            }

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

            ToolResult(content, "Modified config '$configName': ${changes.joinToString(", ")}", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error modifying run configuration: ${e.message}", "Error modifying config", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun modifyApplyEnvVars(config: RunConfiguration, envVars: Map<String, String>) {
        if (config is ApplicationConfiguration) {
            config.envs = envVars
        } else {
            try {
                val method = config.javaClass.methods.find { it.name == "setEnvs" && it.parameterCount == 1 }
                method?.invoke(config, envVars)
            } catch (_: Exception) { }
        }
    }

    private fun modifyApplyVmOptions(config: RunConfiguration, vmOptions: String) {
        if (config is ApplicationConfiguration) {
            config.vmParameters = vmOptions
        } else {
            try {
                val method = config.javaClass.methods.find {
                    (it.name == "setVMParameters" || it.name == "setVmParameters" || it.name == "setVmOptions")
                        && it.parameterCount == 1
                }
                method?.invoke(config, vmOptions)
            } catch (_: Exception) { }
        }
    }

    private fun modifyApplyProgramArgs(config: RunConfiguration, args: String) {
        if (config is ApplicationConfiguration) {
            config.programParameters = args
        } else {
            try {
                val method = config.javaClass.methods.find {
                    (it.name == "setProgramParameters" || it.name == "setRawCommandLine")
                        && it.parameterCount == 1
                }
                method?.invoke(config, args)
            } catch (_: Exception) { }
        }
    }

    private fun modifyApplyWorkingDir(config: RunConfiguration, dir: String) {
        if (config is ApplicationConfiguration) {
            config.workingDirectory = dir
        } else {
            try {
                val method = config.javaClass.methods.find {
                    it.name == "setWorkingDirectory" && it.parameterCount == 1
                }
                method?.invoke(config, dir)
            } catch (_: Exception) { }
        }
    }

    private fun modifyApplyActiveProfiles(config: RunConfiguration, profiles: String) {
        try {
            val method = config.javaClass.methods.find {
                it.name == "setActiveProfiles" && it.parameterCount == 1
            }
            method?.invoke(config, profiles)
        } catch (_: Exception) { }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: delete_run_config
    // ══════════════════════════════════════════════════════════════════════

    private fun executeDeleteRunConfig(params: JsonObject, project: Project): ToolResult {
        val configName = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult("Missing required parameter: name", "Error: missing name", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        if (!configName.startsWith("[Agent]")) {
            return ToolResult(
                "Cannot delete '$configName': only agent-created configurations (containing [Agent] in name) can be deleted. " +
                    "This is a safety constraint to protect user-created configurations.",
                "Error: cannot delete non-agent config", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        return try {
            val runManager = RunManager.getInstance(project)
            val settings = runManager.findConfigurationByName(configName)
                ?: return ToolResult(
                    "Configuration '$configName' not found. Use get_run_configurations to list available configs.",
                    "Error: config not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )

            ApplicationManager.getApplication().invokeAndWait {
                runManager.removeConfiguration(settings)
            }

            ToolResult("Deleted run configuration '$configName'", "Deleted config '$configName'", 10)
        } catch (e: Exception) {
            ToolResult("Error deleting run configuration: ${e.message}", "Error deleting config", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    companion object {
        const val AGENT_PREFIX = "[Agent] "
    }
}
