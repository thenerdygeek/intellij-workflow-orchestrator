package com.workflow.orchestrator.agent.tools.config

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

/**
 * Creates a new run/debug configuration in IntelliJ.
 *
 * Agent-created configurations are prefixed with [Agent] to distinguish them
 * from user-created ones. Supports application, spring_boot, junit, gradle,
 * and remote_debug configuration types.
 *
 * JUnit, Remote, Gradle, and Spring Boot configuration types are resolved via
 * reflection since their plugins may not be available at compile time.
 */
class CreateRunConfigTool : AgentTool {
    override val name = "create_run_config"
    override val description = "Create a new run/debug configuration in IntelliJ. Agent-created configs are prefixed with [Agent]."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "name" to ParameterProperty(
                type = "string",
                description = "Configuration name (auto-prefixed with [Agent])"
            ),
            "type" to ParameterProperty(
                type = "string",
                description = "Configuration type",
                enumValues = listOf("application", "spring_boot", "junit", "gradle", "remote_debug")
            ),
            "main_class" to ParameterProperty(
                type = "string",
                description = "Fully qualified main class (required for application/spring_boot)"
            ),
            "test_class" to ParameterProperty(
                type = "string",
                description = "Fully qualified test class (required for junit)"
            ),
            "test_method" to ParameterProperty(
                type = "string",
                description = "Specific test method name (junit only)"
            ),
            "module" to ParameterProperty(
                type = "string",
                description = "Module name (auto-detected if omitted)"
            ),
            "env_vars" to ParameterProperty(
                type = "object",
                description = "Environment variables as key-value pairs, e.g. {\"DB_HOST\": \"localhost\"}"
            ),
            "vm_options" to ParameterProperty(
                type = "string",
                description = "JVM options (e.g. '-Xmx512m -Dspring.profiles.active=dev')"
            ),
            "program_args" to ParameterProperty(
                type = "string",
                description = "Program arguments"
            ),
            "working_dir" to ParameterProperty(
                type = "string",
                description = "Working directory (default: project root)"
            ),
            "active_profiles" to ParameterProperty(
                type = "string",
                description = "Spring Boot active profiles, comma-separated (spring_boot only)"
            ),
            "port" to ParameterProperty(
                type = "integer",
                description = "Remote debug port (remote_debug only, default 5005)"
            )
        ),
        required = listOf("name", "type")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    companion object {
        const val AGENT_PREFIX = "[Agent] "
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val configName = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Missing required parameter: name",
                "Error: missing name",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val configType = params["type"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Missing required parameter: type",
                "Error: missing type",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val validTypes = listOf("application", "spring_boot", "junit", "gradle", "remote_debug")
        if (configType !in validTypes) {
            return ToolResult(
                "Invalid type '$configType'. Must be one of: ${validTypes.joinToString(", ")}",
                "Error: invalid type",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
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

        // Validate conditional requirements
        if (configType in listOf("application", "spring_boot") && mainClass.isNullOrBlank()) {
            return ToolResult(
                "Parameter 'main_class' is required for type '$configType'",
                "Error: missing main_class",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        if (configType == "junit" && testClass.isNullOrBlank()) {
            return ToolResult(
                "Parameter 'test_class' is required for type 'junit'",
                "Error: missing test_class",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val prefixedName = "$AGENT_PREFIX$configName"

        return try {
            val runManager = RunManager.getInstance(project)

            // Check if config with this name already exists
            val existing = runManager.findConfigurationByName(prefixedName)
            if (existing != null) {
                return ToolResult(
                    "Configuration '$prefixedName' already exists. Use modify_run_config to update it.",
                    "Error: config exists",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            val factory = resolveConfigurationFactory(configType)
                ?: return ToolResult(
                    "Could not resolve configuration factory for type '$configType'. The required plugin may not be installed.",
                    "Error: no factory for $configType",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )

            val settings = runManager.createConfiguration(prefixedName, factory)
            val config = settings.configuration

            // Apply type-specific settings
            applyConfigSettings(
                config = config,
                configType = configType,
                mainClass = mainClass,
                testClass = testClass,
                testMethod = testMethod,
                module = module,
                envVars = envVars,
                vmOptions = vmOptions,
                programArgs = programArgs,
                workingDir = workingDir ?: project.basePath,
                activeProfiles = activeProfiles,
                port = port ?: 5005
            )

            // Add to RunManager on EDT
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

            ToolResult(
                content,
                "Created config '$prefixedName' ($configType)",
                TokenEstimator.estimate(content)
            )
        } catch (e: Exception) {
            ToolResult(
                "Error creating run configuration: ${e.message}",
                "Error creating config",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    private fun resolveConfigurationFactory(type: String): ConfigurationFactory? {
        return try {
            when (type) {
                "application" -> ApplicationConfigurationType.getInstance().configurationFactories.firstOrNull()
                "spring_boot" -> resolveFactoryViaReflection(
                    "com.intellij.spring.boot.run.SpringBootApplicationConfigurationType"
                )
                "junit" -> resolveFactoryViaReflection(
                    "com.intellij.execution.junit.JUnitConfigurationType"
                )
                "gradle" -> resolveFactoryViaReflection(
                    "org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType"
                )
                "remote_debug" -> resolveFactoryViaReflection(
                    "com.intellij.execution.remote.RemoteConfigurationType"
                )
                else -> null
            }
        } catch (_: Exception) {
            null
        }
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
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("LongParameterList")
    private fun applyConfigSettings(
        config: RunConfiguration,
        configType: String,
        mainClass: String?,
        testClass: String?,
        testMethod: String?,
        module: String?,
        envVars: Map<String, String>?,
        vmOptions: String?,
        programArgs: String?,
        workingDir: String?,
        activeProfiles: String?,
        port: Int
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
        config: RunConfiguration,
        mainClass: String?,
        vmOptions: String?,
        programArgs: String?,
        envVars: Map<String, String>?,
        workingDir: String?
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
        config: RunConfiguration,
        mainClass: String?,
        vmOptions: String?,
        programArgs: String?,
        envVars: Map<String, String>?,
        workingDir: String?,
        activeProfiles: String?
    ) {
        try {
            mainClass?.let { setViaReflection(config, "setMainClassName", it) }
            vmOptions?.let { setViaReflection(config, "setVMParameters", it) }
            programArgs?.let { setViaReflection(config, "setProgramParameters", it) }
            envVars?.let { setEnvsViaReflection(config, it) }
            workingDir?.let { setViaReflection(config, "setWorkingDirectory", it) }
            activeProfiles?.let { setViaReflection(config, "setActiveProfiles", it) }
        } catch (_: Exception) {
            // Best effort — plugin may vary
        }
    }

    private fun applyJUnitConfig(
        config: RunConfiguration,
        testClass: String?,
        testMethod: String?,
        vmOptions: String?,
        envVars: Map<String, String>?,
        workingDir: String?
    ) {
        // JUnit plugin may not be available — use reflection
        try {
            testClass?.let {
                // Access persistentData via reflection
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
        } catch (_: Exception) {
            // Best effort for JUnit configuration
        }
    }

    private fun applyRemoteConfig(config: RunConfiguration, port: Int) {
        // Remote debug plugin may not be available — use reflection
        try {
            val portField = config.javaClass.getField("PORT")
            portField.set(config, port.toString())
            val hostField = config.javaClass.getField("HOST")
            hostField.set(config, "localhost")
            val serverModeField = config.javaClass.getField("SERVER_MODE")
            serverModeField.set(config, false)
        } catch (_: Exception) {
            // Best effort for Remote configuration
        }
    }

    private fun applyGradleConfig(
        config: RunConfiguration,
        programArgs: String?,
        vmOptions: String?,
        envVars: Map<String, String>?,
        workingDir: String?
    ) {
        try {
            programArgs?.let { setViaReflection(config, "setRawCommandLine", it) }
            vmOptions?.let { setViaReflection(config, "setVmOptions", it) }
            workingDir?.let { setViaReflection(config, "setWorkingDirectory", it) }
        } catch (_: Exception) {
            // Best effort for Gradle configuration
        }
    }

    private fun setViaReflection(config: RunConfiguration, methodName: String, value: String) {
        try {
            val method = config.javaClass.methods.find { it.name == methodName && it.parameterCount == 1 }
            method?.invoke(config, value)
        } catch (_: Exception) {
            // Silently fail — method may not exist on this config type
        }
    }

    private fun setEnvsViaReflection(config: RunConfiguration, envs: Map<String, String>) {
        try {
            val method = config.javaClass.methods.find { it.name == "setEnvs" && it.parameterCount == 1 }
            method?.invoke(config, envs)
        } catch (_: Exception) {
            // Silently fail
        }
    }
}
