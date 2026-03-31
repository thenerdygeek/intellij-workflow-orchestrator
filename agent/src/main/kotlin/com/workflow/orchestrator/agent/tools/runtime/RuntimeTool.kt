package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.TestConsoleUtils
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.RunCommandTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Consolidated runtime meta-tool replacing 9 individual runtime/config/test tools.
 *
 * Saves token budget per API call by collapsing all runtime operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: get_run_configurations, create_run_config, modify_run_config,
 *          delete_run_config, get_running_processes, get_run_output,
 *          get_test_results, run_tests, compile_module
 */
class RuntimeTool : AgentTool {

    override val name = "runtime"

    override val description =
        "Runtime management — run configurations, processes, test results, compile, run tests.\n" +
        "Actions: get_run_configurations, create_run_config, modify_run_config, delete_run_config, " +
        "get_running_processes, get_run_output, get_test_results, run_tests, compile_module"

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "get_run_configurations", "create_run_config", "modify_run_config",
                    "delete_run_config", "get_running_processes", "get_run_output",
                    "get_test_results", "run_tests", "compile_module"
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
                description = "Module name — for create_run_config (auto-detected if omitted), compile_module (compiles entire project if omitted)"
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
            "config_name" to ParameterProperty(
                type = "string",
                description = "Name of the run configuration — for get_run_output, get_test_results"
            ),
            "last_n_lines" to ParameterProperty(
                type = "integer",
                description = "Number of lines to return from the end (default: 200, max: 1000) — for get_run_output"
            ),
            "filter" to ParameterProperty(
                type = "string",
                description = "Regex pattern to filter output lines — for get_run_output"
            ),
            "status_filter" to ParameterProperty(
                type = "string",
                description = "Filter by test status — for get_test_results",
                enumValues = listOf("FAILED", "ERROR", "PASSED", "SKIPPED")
            ),
            "class_name" to ParameterProperty(
                type = "string",
                description = "Fully qualified test class name — for run_tests"
            ),
            "method" to ParameterProperty(
                type = "string",
                description = "Specific test method name — for run_tests"
            ),
            "timeout" to ParameterProperty(
                type = "integer",
                description = "Seconds before test process is killed (default: 300, max: 900) — for run_tests"
            ),
            "use_native_runner" to ParameterProperty(
                type = "boolean",
                description = "Use IntelliJ native test runner (true) or Maven/Gradle shell (false). Default: true — for run_tests"
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description of what this action does and why (shown to user in approval dialog) — for delete_run_config, run_tests, compile_module"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(
        WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
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
            "get_running_processes" -> executeGetRunningProcesses(params, project)
            "get_run_output" -> executeGetRunOutput(params, project)
            "get_test_results" -> executeGetTestResults(params, project)
            "run_tests" -> executeRunTests(params, project)
            "compile_module" -> executeCompileModule(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. See tool description for valid actions.",
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

    // ══════════════════════════════════════════════════════════════════════
    // Action: get_running_processes
    // ══════════════════════════════════════════════════════════════════════

    private fun executeGetRunningProcesses(params: JsonObject, project: Project): ToolResult {
        return try {
            val entries = mutableListOf<ProcessEntry>()

            val runningProcesses = ExecutionManager.getInstance(project).getRunningProcesses()
            for (handler in runningProcesses) {
                val processName = extractProcessName(handler)
                val isDestroyed = handler.isProcessTerminated || handler.isProcessTerminating
                if (!isDestroyed) {
                    entries.add(ProcessEntry(
                        name = processName, type = "Running", status = "Active", pid = extractPid(handler)
                    ))
                }
            }

            val debugSessions = XDebuggerManager.getInstance(project).debugSessions
            for (session in debugSessions) {
                val sessionName = session.sessionName
                val isStopped = session.isStopped
                if (!isStopped && entries.none { it.name == sessionName && it.type == "Debug" }) {
                    val isPaused = session.isPaused
                    val status = when {
                        isPaused -> "Paused (at breakpoint)"
                        else -> "Active"
                    }
                    entries.add(ProcessEntry(name = sessionName, type = "Debug", status = status, pid = null))
                }
            }

            if (entries.isEmpty()) {
                return ToolResult("No active run/debug sessions.", "No processes", 10)
            }

            val sb = StringBuilder()
            sb.appendLine("Active Sessions (${entries.size}):")
            sb.appendLine()

            for (entry in entries) {
                sb.appendLine(entry.name)
                sb.appendLine("  Type: ${entry.type}")
                sb.appendLine("  Status: ${entry.status}")
                entry.pid?.let { sb.appendLine("  PID: $it") }
                sb.appendLine()
            }

            val content = sb.toString().trimEnd()
            ToolResult(content, "${entries.size} active sessions", content.length / 4)
        } catch (e: Exception) {
            ToolResult("Error listing processes: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun extractProcessName(handler: ProcessHandler): String {
        return try { handler.toString() } catch (_: Exception) { "Unknown process" }
    }

    private fun extractPid(handler: ProcessHandler): Long? {
        return try {
            val method = handler.javaClass.methods.find { it.name == "getProcess" }
            val process = method?.invoke(handler) as? Process
            process?.pid()
        } catch (_: Exception) { null }
    }

    private data class ProcessEntry(val name: String, val type: String, val status: String, val pid: Long?)

    // ══════════════════════════════════════════════════════════════════════
    // Action: get_run_output
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeGetRunOutput(params: JsonObject, project: Project): ToolResult {
        val configName = params["config_name"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'config_name' required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val lastNLines = (params["last_n_lines"]?.jsonPrimitive?.intOrNull ?: RUN_OUTPUT_DEFAULT_LINES)
            .coerceIn(1, RUN_OUTPUT_MAX_LINES)

        val filterPattern = params["filter"]?.jsonPrimitive?.content?.let {
            try { Regex(it) }
            catch (e: Exception) {
                return ToolResult("Error: invalid regex pattern '${it}': ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }
        }

        return try {
            val contentManager = RunContentManager.getInstance(project)
            val allDescriptors = contentManager.allDescriptors

            val descriptor = allDescriptors.find { desc ->
                desc.displayName?.contains(configName, ignoreCase = true) == true
            }

            if (descriptor == null) {
                val available = allDescriptors.mapNotNull { it.displayName }
                val availableMsg = if (available.isNotEmpty()) {
                    "\nAvailable sessions: ${available.joinToString(", ")}"
                } else {
                    "\nNo run sessions available."
                }
                return ToolResult("No run session found matching '$configName'.$availableMsg", "Not found", 30, isError = true)
            }

            val consoleText = extractConsoleText(descriptor)

            if (consoleText == null || consoleText.isBlank()) {
                return ToolResult("Run session '${descriptor.displayName}' found but console output is empty.", "Empty output", 10)
            }

            var lines = consoleText.lines()
            if (filterPattern != null) {
                lines = lines.filter { filterPattern.containsMatchIn(it) }
            }

            val totalLines = lines.size
            lines = lines.takeLast(lastNLines)

            val sb = StringBuilder()
            sb.appendLine("Console Output: ${descriptor.displayName}")
            val processStatus = when {
                descriptor.processHandler?.isProcessTerminated == true -> "Terminated"
                descriptor.processHandler?.isProcessTerminating == true -> "Terminating"
                else -> "Running"
            }
            sb.appendLine("Status: $processStatus")
            if (filterPattern != null) {
                sb.appendLine("Filter: ${filterPattern.pattern}")
            }
            if (totalLines > lastNLines) {
                sb.appendLine("Showing last $lastNLines of $totalLines lines")
            }
            sb.appendLine("---")

            val startLineNum = (totalLines - lines.size) + 1
            for ((index, line) in lines.withIndex()) {
                sb.appendLine("${startLineNum + index}: $line")
            }

            val content = sb.toString().trimEnd()
            val capped = if (content.length > RUN_OUTPUT_TOKEN_CAP_CHARS) {
                content.take(RUN_OUTPUT_TOKEN_CAP_CHARS) + "\n... (output truncated)"
            } else {
                content
            }

            ToolResult(capped, "${lines.size} lines from ${descriptor.displayName}", capped.length / 4)
        } catch (e: Exception) {
            ToolResult("Error getting run output: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private suspend fun extractConsoleText(descriptor: RunContentDescriptor): String? {
        val console = descriptor.executionConsole ?: return null

        val unwrapped = unwrapToConsoleView(console)
        if (unwrapped != null) {
            val text = readConsoleViewText(unwrapped)
            if (!text.isNullOrBlank()) return text
        }

        if (console is com.intellij.execution.impl.ConsoleViewImpl) {
            val text = readConsoleViewText(console)
            if (!text.isNullOrBlank()) return text
        }

        if (console is com.intellij.execution.testframework.ui.BaseTestsOutputConsoleView) {
            val innerConsole = console.console
            if (innerConsole is com.intellij.execution.impl.ConsoleViewImpl) {
                val text = readConsoleViewText(innerConsole)
                if (!text.isNullOrBlank()) return text
            }
            val text = readViaEditor(innerConsole)
            if (!text.isNullOrBlank()) return text
        }

        try {
            val getConsole = console.javaClass.getMethod("getConsole")
            val innerConsole = getConsole.invoke(console)
            if (innerConsole is com.intellij.execution.impl.ConsoleViewImpl) {
                val text = readConsoleViewText(innerConsole)
                if (!text.isNullOrBlank()) return text
            }
            if (innerConsole != null) {
                val text = readViaEditor(innerConsole)
                if (!text.isNullOrBlank()) return text
            }
        } catch (_: Exception) {}

        return readViaEditor(console)
    }

    private fun unwrapToConsoleView(console: Any): com.intellij.execution.impl.ConsoleViewImpl? {
        var current: Any? = console
        repeat(MAX_UNWRAP_DEPTH) {
            if (current is com.intellij.execution.impl.ConsoleViewImpl) return current

            val delegate = tryReflectiveCall(current, "getDelegate")
            if (delegate is com.intellij.execution.impl.ConsoleViewImpl) return delegate
            if (delegate != null && delegate !== current) {
                current = delegate
                return@repeat
            }

            val inner = tryReflectiveCall(current, "getConsole")
            if (inner is com.intellij.execution.impl.ConsoleViewImpl) return inner
            if (inner != null && inner !== current) {
                current = inner
                return@repeat
            }

            return null
        }
        return current as? com.intellij.execution.impl.ConsoleViewImpl
    }

    private fun tryReflectiveCall(target: Any?, methodName: String): Any? {
        return try {
            target?.javaClass?.getMethod(methodName)?.invoke(target)
        } catch (_: Exception) { null }
    }

    private suspend fun readConsoleViewText(console: com.intellij.execution.impl.ConsoleViewImpl): String? {
        return try {
            withContext(Dispatchers.EDT) {
                console.component
                console.flushDeferredText()
                console.editor?.document?.text
            }
        } catch (_: Exception) { null }
    }

    private suspend fun readViaEditor(console: Any): String? {
        return try {
            withContext(Dispatchers.EDT) {
                try { console.javaClass.getMethod("getComponent").invoke(console) } catch (_: Exception) {}
                val editorMethod = console.javaClass.getMethod("getEditor")
                val editor = editorMethod.invoke(console) as? com.intellij.openapi.editor.Editor
                editor?.document?.text
            }
        } catch (_: Exception) { null }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: get_test_results
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeGetTestResults(params: JsonObject, project: Project): ToolResult {
        val configName = params["config_name"]?.jsonPrimitive?.content
        val statusFilter = params["status_filter"]?.jsonPrimitive?.content?.uppercase()

        return try {
            val contentManager = RunContentManager.getInstance(project)
            val allDescriptors = contentManager.allDescriptors

            val descriptor = if (configName != null) {
                allDescriptors.find { desc ->
                    desc.displayName?.contains(configName, ignoreCase = true) == true
                }
            } else {
                allDescriptors.firstOrNull { desc -> hasTestResults(desc) }
                    ?: allDescriptors.firstOrNull { desc ->
                        desc.processHandler?.let { !it.isProcessTerminated } == true
                    }
            }

            if (descriptor == null) {
                val msg = if (configName != null) "No test run found matching '$configName'." else "No test run results available."
                return ToolResult(msg, "No test results", 10, isError = true)
            }

            val handler = descriptor.processHandler
            if (handler != null && !handler.isProcessTerminated) {
                val processTerminated = awaitProcessTermination(handler, MAX_PROCESS_WAIT_SECONDS * 1000L)
                if (!processTerminated) {
                    return ToolResult(
                        "Process for '${descriptor.displayName}' is still running after ${MAX_PROCESS_WAIT_SECONDS}s " +
                            "(may still be building/compiling). Try again later.",
                        "Process still running", 20, isError = true
                    )
                }
            }

            val testConsole = TestConsoleUtils.unwrapToTestConsole(descriptor.executionConsole)
            if (testConsole != null) {
                awaitTestingFinished(testConsole.resultsViewer, TEST_TREE_FINALIZE_TIMEOUT_MS)
            } else {
                delay(1000)
            }

            val testRoot = TestConsoleUtils.findTestRoot(descriptor)
            if (testRoot == null) {
                return ToolResult(
                    "Run session '${descriptor.displayName}' found but no test results available. It may not be a test run.",
                    "No test data", 15, isError = true
                )
            }

            val allTests = collectTestResults(testRoot)
            val filtered = if (statusFilter != null) {
                allTests.filter { it.status.name == statusFilter }
            } else {
                allTests
            }

            val passed = allTests.count { it.status == TestStatus.PASSED }
            val failed = allTests.count { it.status == TestStatus.FAILED }
            val errors = allTests.count { it.status == TestStatus.ERROR }
            val skipped = allTests.count { it.status == TestStatus.SKIPPED }
            val totalDuration = allTests.sumOf { it.durationMs }

            val sb = StringBuilder()
            sb.appendLine("Test Run: ${descriptor.displayName ?: "Unknown"}")

            val overallStatus = when {
                errors > 0 || failed > 0 -> "FAILED"
                else -> "PASSED"
            }
            sb.appendLine("Status: $overallStatus ($passed passed, $failed failed, $errors error, $skipped skipped)")
            sb.appendLine("Duration: ${formatDuration(totalDuration)}")
            sb.appendLine()

            val failedTests = filtered.filter { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR }
            val skippedTests = filtered.filter { it.status == TestStatus.SKIPPED }
            val passedTests = filtered.filter { it.status == TestStatus.PASSED }

            if (failedTests.isNotEmpty()) {
                sb.appendLine("--- FAILED ---")
                for (test in failedTests) {
                    sb.appendLine("${test.name} (${formatDuration(test.durationMs)})")
                    test.errorMessage?.let { sb.appendLine("  Assertion: $it") }
                    if (test.stackTrace.isNotEmpty()) {
                        sb.appendLine("  Stack:")
                        for (frame in test.stackTrace.take(MAX_STACK_FRAMES)) {
                            sb.appendLine("    $frame")
                        }
                    }
                    sb.appendLine()
                }
            }

            if (skippedTests.isNotEmpty()) {
                sb.appendLine("--- SKIPPED ---")
                for (test in skippedTests) {
                    sb.appendLine(test.name)
                }
                sb.appendLine()
            }

            if (statusFilter == "PASSED" && passedTests.isNotEmpty()) {
                sb.appendLine("--- PASSED ---")
                for (test in passedTests) {
                    sb.appendLine("${test.name} (${formatDuration(test.durationMs)})")
                }
            } else if (passedTests.isNotEmpty() && statusFilter == null) {
                sb.appendLine("--- PASSED ($passed tests) ---")
                val shown = passedTests.take(MAX_PASSED_SHOWN)
                for (test in shown) {
                    sb.appendLine("${test.name} (${formatDuration(test.durationMs)})")
                }
                if (passedTests.size > MAX_PASSED_SHOWN) {
                    sb.appendLine("... and ${passedTests.size - MAX_PASSED_SHOWN} more passed tests")
                }
            }

            val content = sb.toString().trimEnd()
            val capped = if (content.length > TEST_RESULTS_TOKEN_CAP_CHARS) {
                content.take(TEST_RESULTS_TOKEN_CAP_CHARS) + "\n... (results truncated)"
            } else {
                content
            }

            ToolResult(capped, "$overallStatus: $passed passed, $failed failed", capped.length / 4)
        } catch (e: Exception) {
            ToolResult("Error getting test results: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private suspend fun awaitProcessTermination(handler: ProcessHandler, timeoutMs: Long): Boolean {
        if (handler.isProcessTerminated) return true

        val toolCallId = RunCommandTool.currentToolCallId.get()
        val streamCallback = RunCommandTool.streamCallback

        val terminated = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val progressJob = if (toolCallId != null && streamCallback != null) {
                    launch {
                        var elapsed = 0L
                        while (true) {
                            delay(PROGRESS_INTERVAL_MS)
                            elapsed += PROGRESS_INTERVAL_MS
                            streamCallback.invoke(toolCallId, "[waiting for process... ${elapsed / 1000}s elapsed]\n")
                        }
                    }
                } else null

                try {
                    suspendCancellableCoroutine { continuation ->
                        val listener = object : ProcessAdapter() {
                            override fun processTerminated(event: ProcessEvent) {
                                if (continuation.isActive) continuation.resume(true)
                            }
                        }
                        handler.addProcessListener(listener)
                        continuation.invokeOnCancellation {
                            handler.removeProcessListener(listener)
                        }
                        if (handler.isProcessTerminated && continuation.isActive) {
                            continuation.resume(true)
                        }
                    }
                } finally {
                    progressJob?.cancel()
                }
            }
        }

        if (terminated == null && toolCallId != null) {
            streamCallback?.invoke(toolCallId, "[process still running after ${timeoutMs / 1000}s]\n")
        }

        return terminated ?: false
    }

    private suspend fun awaitTestingFinished(resultsViewer: TestResultsViewer, timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : TestResultsViewer.EventsListener {
                    override fun onTestingFinished(sender: TestResultsViewer) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
                resultsViewer.addEventsListener(listener)
            }
        }
    }

    private fun hasTestResults(descriptor: RunContentDescriptor): Boolean {
        val root = TestConsoleUtils.findTestRoot(descriptor) ?: return false
        return root.children.isNotEmpty()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: run_tests
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeRunTests(params: JsonObject, project: Project): ToolResult {
        val className = params["class_name"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'class_name' parameter is required", "Error: missing class_name", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val method = params["method"]?.jsonPrimitive?.content
        val timeoutSeconds = (params["timeout"]?.jsonPrimitive?.intOrNull?.toLong() ?: RUN_TESTS_DEFAULT_TIMEOUT)
            .coerceIn(1, RUN_TESTS_MAX_TIMEOUT)
        val useNativeRunner = params["use_native_runner"]?.jsonPrimitive?.booleanOrNull ?: true

        val testTarget = if (method != null) "$className#$method" else className

        if (useNativeRunner) {
            try {
                val result = executeWithNativeRunner(project, className, method, testTarget, timeoutSeconds)
                if (result != null) return result
            } catch (e: Exception) {
                val shellResult = executeWithShell(project, testTarget, timeoutSeconds)
                val warning = "[WARNING] Native test runner failed (${e.javaClass.simpleName}: ${e.message}), used shell fallback.\n\n"
                return shellResult.copy(content = warning + shellResult.content)
            }
        }

        return executeWithShell(project, testTarget, timeoutSeconds)
    }

    private suspend fun executeWithNativeRunner(
        project: Project, className: String, method: String?,
        testTarget: String, timeoutSeconds: Long
    ): ToolResult? {
        val settings = createJUnitRunSettings(project, className, method) ?: return null

        val processHandlerRef = AtomicReference<ProcessHandler?>(null)
        val descriptorRef = AtomicReference<RunContentDescriptor?>(null)

        val result = withTimeoutOrNull(timeoutSeconds * 1000) {
            suspendCancellableCoroutine { continuation ->
                com.intellij.openapi.application.invokeLater {
                    try {
                        val executor = DefaultRunExecutor.getRunExecutorInstance()
                        val env = ExecutionEnvironmentBuilder
                            .createOrNull(executor, settings)
                            ?.build()

                        if (env == null) {
                            if (continuation.isActive) continuation.resume(null)
                            return@invokeLater
                        }

                        val callback = object : ProgramRunner.Callback {
                            override fun processStarted(descriptor: RunContentDescriptor?) {
                                if (descriptor == null) {
                                    if (continuation.isActive) continuation.resume(null)
                                    return
                                }
                                handleDescriptorReady(descriptor, continuation, testTarget, descriptorRef, processHandlerRef)
                            }
                        }

                        try {
                            ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)
                        } catch (_: NoSuchMethodError) {
                            env.callback = callback
                            ProgramRunnerUtil.executeConfiguration(env, false, true)
                        }

                        // Build watchdog: polls until compilation finishes.
                        // If Make completes but processStarted() still hasn't fired,
                        // the build failed. Uses polling (not a fixed timer) so large
                        // projects that take >30s to compile won't false-positive.
                        Thread {
                            try {
                                // Initial grace period — give Make time to start
                                Thread.sleep(BUILD_WATCHDOG_INITIAL_MS)

                                // Poll: wait while compilation is still active
                                var waited = BUILD_WATCHDOG_INITIAL_MS
                                while (processHandlerRef.get() == null && continuation.isActive && waited < BUILD_WATCHDOG_MAX_MS) {
                                    val stillCompiling = try {
                                        com.intellij.openapi.application.ReadAction.compute<Boolean, Exception> {
                                            CompilerManager.getInstance(project).isCompilationActive
                                        }
                                    } catch (_: Exception) { false }

                                    if (!stillCompiling) break  // Make finished — check result
                                    Thread.sleep(BUILD_WATCHDOG_POLL_MS)
                                    waited += BUILD_WATCHDOG_POLL_MS
                                }

                                // Make finished but process never started → build failed
                                if (processHandlerRef.get() == null && continuation.isActive) {
                                    continuation.resume(ToolResult(
                                        content = "BUILD FAILED — test execution did not start.\n\n" +
                                            "Compilation completed but no test process was created. " +
                                            "This means the build had errors.\n\n" +
                                            "Fix the compilation errors and try again. " +
                                            "Use diagnostics tool to check for errors in the test class.",
                                        summary = "Build failed before tests",
                                        tokenEstimate = 30,
                                        isError = true
                                    ))
                                }
                            } catch (_: InterruptedException) {
                                // Continuation was resumed normally, watchdog no longer needed
                            }
                        }.apply {
                            isDaemon = true
                            name = "run_tests-build-watchdog"
                            start()
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        }

        if (result == null && processHandlerRef.get() != null) {
            processHandlerRef.get()?.destroyProcess()
            val descriptor = descriptorRef.get()
            val partialResult = descriptor?.let { extractNativeResults(it, testTarget) }
            return if (partialResult != null) {
                partialResult.copy(
                    content = "[TIMEOUT] Test execution timed out after ${timeoutSeconds}s. Partial results:\n\n${partialResult.content}",
                    isError = true
                )
            } else {
                ToolResult(
                    "[TIMEOUT] Test execution timed out after ${timeoutSeconds}s for $testTarget. No results captured.",
                    "Test timeout", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )
            }
        }

        return result
    }

    private fun handleDescriptorReady(
        descriptor: RunContentDescriptor,
        continuation: kotlinx.coroutines.CancellableContinuation<ToolResult?>,
        testTarget: String,
        descriptorRef: AtomicReference<RunContentDescriptor?>,
        processHandlerRef: AtomicReference<ProcessHandler?>
    ) {
        descriptorRef.set(descriptor)
        val handler = descriptor.processHandler
        processHandlerRef.set(handler)

        val toolCallId = RunCommandTool.currentToolCallId.get()
        val activeStreamCallback = RunCommandTool.streamCallback

        if (handler != null && toolCallId != null) {
            handler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                    val text = event.text ?: return
                    if (text.isNotBlank()) {
                        activeStreamCallback?.invoke(toolCallId, text)
                    }
                }
            })
        }

        val testConsole = TestConsoleUtils.unwrapToTestConsole(descriptor.executionConsole)
        if (testConsole != null) {
            val resultsViewer = testConsole.resultsViewer
            resultsViewer.addEventsListener(object : TestResultsViewer.EventsListener {
                override fun onTestingFinished(sender: TestResultsViewer) {
                    val root = sender.testsRootNode as? SMTestProxy.SMRootTestProxy
                    if (root != null && continuation.isActive) {
                        val allTests = collectTestResults(root)
                        val resultVal = if (allTests.isNotEmpty()) {
                            formatStructuredResults(allTests, descriptor.displayName ?: testTarget)
                        } else {
                            ToolResult("Test run completed for $testTarget but no test methods found in results.", "No tests found", 10)
                        }
                        continuation.resume(resultVal)
                    }
                }
            })
        } else {
            if (handler != null) {
                if (handler.isProcessTerminated) {
                    if (continuation.isActive) {
                        continuation.resume(extractNativeResults(descriptor, testTarget))
                    }
                } else {
                    handler.addProcessListener(object : ProcessAdapter() {
                        override fun processTerminated(event: ProcessEvent) {
                            java.util.Timer().schedule(object : java.util.TimerTask() {
                                override fun run() {
                                    if (continuation.isActive) {
                                        continuation.resume(extractNativeResults(descriptor, testTarget))
                                    }
                                }
                            }, 2000)
                        }
                    })
                }
            } else {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private fun createJUnitRunSettings(
        project: Project, className: String, method: String?
    ): RunnerAndConfigurationSettings? {
        return try {
            val runManager = RunManager.getInstance(project)

            val testFramework = detectTestFramework(project, className)
            val configTypeId = when (testFramework) {
                "TestNG" -> "TestNG"
                else -> "JUnit"
            }
            val testConfigType = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.find { type ->
                type.id == configTypeId || type.displayName == configTypeId
            } ?: return null

            val factory = testConfigType.configurationFactories.firstOrNull() ?: return null
            val configName = "${className.substringAfterLast('.')}${if (method != null) ".$method" else ""}"
            val settings = runManager.createConfiguration(configName, factory)

            val config = settings.configuration
            val isTestNG = testFramework == "TestNG"

            try {
                val dataMethodName = if (isTestNG) "getPersistantData" else "getPersistentData"
                val getDataMethod = config.javaClass.methods.find { it.name == dataMethodName }
                val data = getDataMethod?.invoke(config)
                if (data != null) {
                    val testObjectField = data.javaClass.getField("TEST_OBJECT")
                    val mainClassField = data.javaClass.getField("MAIN_CLASS_NAME")

                    val testType = if (method != null) {
                        if (isTestNG) "METHOD" else "method"
                    } else {
                        if (isTestNG) "CLASS" else "class"
                    }
                    testObjectField.set(data, testType)
                    mainClassField.set(data, className)

                    try {
                        val packageField = data.javaClass.getField("PACKAGE_NAME")
                        val packageName = className.substringBeforeLast('.', "")
                        packageField.set(data, packageName)
                    } catch (_: Exception) { }

                    if (method != null) {
                        val methodField = data.javaClass.getField("METHOD_NAME")
                        methodField.set(data, method)
                    }
                } else {
                    return null
                }
            } catch (_: Exception) {
                return null
            }

            val testModule = findModuleForClass(project, className) ?: return null
            run {
                try {
                    val setModuleMethod = config.javaClass.getMethod("setModule", com.intellij.openapi.module.Module::class.java)
                    setModuleMethod.invoke(config, testModule)
                } catch (_: Exception) {
                    try {
                        val getConfigModule = config.javaClass.getMethod("getConfigurationModule")
                        val configModule = getConfigModule.invoke(config)
                        val setModule = configModule.javaClass.getMethod("setModule", com.intellij.openapi.module.Module::class.java)
                        setModule.invoke(configModule, testModule)
                    } catch (_: Exception) { }
                }
            }

            settings.isTemporary = true
            runManager.addConfiguration(settings)
            runManager.selectedConfiguration = settings

            settings
        } catch (_: Exception) { null }
    }

    private fun detectTestFramework(project: Project, className: String): String {
        return try {
            com.intellij.openapi.application.ReadAction.compute<String, Exception> {
                val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
                    .findClass(className, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
                    ?: return@compute "Unknown"

                val annotations = psiClass.annotations.map { it.qualifiedName.orEmpty() } +
                    psiClass.methods.flatMap { m -> m.annotations.map { it.qualifiedName.orEmpty() } }

                when {
                    annotations.any { it.startsWith("org.testng.") } -> "TestNG"
                    annotations.any { it.startsWith("org.junit.") } -> "JUnit"
                    else -> "Unknown"
                }
            }
        } catch (_: Exception) { "Unknown" }
    }

    private fun findModuleForClass(project: Project, className: String): com.intellij.openapi.module.Module? {
        return try {
            com.intellij.openapi.application.ReadAction.compute<com.intellij.openapi.module.Module?, Exception> {
                val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
                    .findClass(className, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
                    ?: return@compute null
                com.intellij.openapi.module.ModuleUtilCore.findModuleForPsiElement(psiClass)
            }
        } catch (_: Exception) { null }
    }

    private fun extractNativeResults(descriptor: RunContentDescriptor, testTarget: String): ToolResult? {
        val testRoot = TestConsoleUtils.findTestRoot(descriptor)
        if (testRoot == null) {
            return ToolResult(
                "Test run completed for $testTarget but no structured results available.\nRun session: ${descriptor.displayName}",
                "Tests completed, no structured data", 20
            )
        }

        val allTests = collectTestResults(testRoot)
        if (allTests.isEmpty()) {
            return ToolResult("Test run completed for $testTarget but no test methods found in results.", "No tests found", 10)
        }

        return formatStructuredResults(allTests, descriptor.displayName ?: testTarget)
    }

    private fun executeWithShell(project: Project, testTarget: String, timeoutSeconds: Long): ToolResult {
        val basePath = project.basePath
            ?: return ToolResult("Error: no project base path available", "Error: no project", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val baseDir = File(basePath)
        val hasMaven = File(baseDir, "pom.xml").exists()
        val hasGradle = File(baseDir, "build.gradle").exists() || File(baseDir, "build.gradle.kts").exists()

        val command = when {
            hasMaven -> "mvn test -Dtest=$testTarget -Dsurefire.useFile=false -q"
            hasGradle -> {
                val gradleWrapper = if (File(baseDir, "gradlew").exists()) "./gradlew" else "gradle"
                val gradleTarget = testTarget.replace('#', '.')
                "$gradleWrapper test --tests '$gradleTarget' --no-daemon -q"
            }
            else -> return ToolResult(
                "No Maven (pom.xml) or Gradle (build.gradle) build file found in project root.",
                "No build tool found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        return try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val processBuilder = if (isWindows) {
                ProcessBuilder("cmd.exe", "/c", command)
            } else {
                ProcessBuilder("sh", "-c", command)
            }

            processBuilder.directory(baseDir)
            processBuilder.redirectErrorStream(true)

            val process = processBuilder.start()
            val toolCallId = RunCommandTool.currentToolCallId.get()
            val activeStreamCallback = RunCommandTool.streamCallback

            val outputBuilder = StringBuilder()
            val readerThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            if (outputBuilder.length < RUN_TESTS_MAX_OUTPUT_CHARS) {
                                outputBuilder.appendLine(line)
                            }
                            if (toolCallId != null) {
                                activeStreamCallback?.invoke(toolCallId, line + "\n")
                            }
                            line = reader.readLine()
                        }
                    }
                } catch (_: Exception) { }
            }.apply {
                isDaemon = true
                name = "RunTests-Output-${toolCallId ?: "shell"}"
                start()
            }

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                readerThread.join(1000)
                val truncatedOutput = outputBuilder.toString()
                return ToolResult(
                    "[TIMEOUT] Test execution timed out after ${timeoutSeconds}s for $testTarget.\nPartial output:\n$truncatedOutput",
                    "Test timeout", TokenEstimator.estimate(truncatedOutput), isError = true
                )
            }

            readerThread.join(2000)
            val truncatedOutput = outputBuilder.toString()

            val exitCode = process.exitValue()
            if (exitCode == 0) {
                ToolResult("Tests PASSED for $testTarget.\n\n$truncatedOutput", "Tests PASSED: $testTarget", TokenEstimator.estimate(truncatedOutput))
            } else {
                ToolResult(
                    "Tests FAILED for $testTarget (exit code $exitCode).\n\n$truncatedOutput",
                    "Tests FAILED: $testTarget", TokenEstimator.estimate(truncatedOutput), isError = true
                )
            }
        } catch (e: Exception) {
            ToolResult("Error running tests: ${e.message}", "Test execution error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: compile_module
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeCompileModule(params: JsonObject, project: Project): ToolResult {
        val moduleName = params["module"]?.jsonPrimitive?.content

        return try {
            val result = withTimeoutOrNull(120_000L) {
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { }
                    ApplicationManager.getApplication().invokeLater {
                        val compiler = CompilerManager.getInstance(project)

                        val scope = if (moduleName != null) {
                            val module = ModuleManager.getInstance(project).modules.find { it.name == moduleName }
                            if (module != null) {
                                compiler.createModuleCompileScope(module, false)
                            } else {
                                val available = ModuleManager.getInstance(project).modules
                                    .map { it.name }
                                    .joinToString(", ")
                                if (!cont.isCompleted) {
                                    cont.resume(
                                        ToolResult(
                                            "Module '$moduleName' not found. Available modules: $available",
                                            "Module not found", TokenEstimator.estimate(available), isError = true
                                        )
                                    )
                                }
                                return@invokeLater
                            }
                        } else {
                            compiler.createProjectCompileScope(project)
                        }

                        val target = moduleName ?: "project"

                        compiler.make(scope) { aborted, errors, warnings, context ->
                            val compileResult = when {
                                aborted -> ToolResult(
                                    "Compilation of $target was aborted.",
                                    "Compilation aborted", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                                )
                                errors > 0 -> {
                                    val messages = context.getMessages(CompilerMessageCategory.ERROR)
                                        .take(COMPILE_MAX_ERROR_MESSAGES)
                                        .joinToString("\n") { msg ->
                                            val file = msg.virtualFile?.name ?: "<unknown>"
                                            val nav = msg.navigatable
                                            val location = if (nav is com.intellij.openapi.fileEditor.OpenFileDescriptor) {
                                                "$file:${nav.line + 1}:${nav.column + 1}"
                                            } else {
                                                file
                                            }
                                            "  $location: ${msg.message}"
                                        }
                                    val content = "Compilation of $target failed: $errors error(s), $warnings warning(s).\n\nErrors:\n$messages"
                                    ToolResult(content, "$errors errors, $warnings warnings", TokenEstimator.estimate(content), isError = true)
                                }
                                else -> {
                                    val warningNote = if (warnings > 0) " with $warnings warning(s)" else ""
                                    ToolResult(
                                        "Compilation of $target successful$warningNote: 0 errors.",
                                        "Build OK", ToolResult.ERROR_TOKEN_ESTIMATE
                                    )
                                }
                            }
                            if (!cont.isCompleted) cont.resume(compileResult)
                        }
                    }
                }
            }

            result ?: ToolResult(
                "Compilation timed out after 120 seconds. The build may be stuck.",
                "Compile timeout", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        } catch (e: Exception) {
            ToolResult("Compilation error: ${e.message}", "Compilation error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Shared helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun collectTestResults(root: SMTestProxy): List<TestResultEntry> {
        return root.allTests
            .filterIsInstance<SMTestProxy>()
            .filter { it.isLeaf }
            .map { mapToTestResultEntry(it) }
    }

    private fun mapToTestResultEntry(proxy: SMTestProxy): TestResultEntry {
        val status = when {
            proxy.isDefect -> {
                if (proxy.stacktrace?.contains("AssertionError") == true ||
                    proxy.stacktrace?.contains("AssertionFailedError") == true
                ) TestStatus.FAILED else TestStatus.ERROR
            }
            proxy.isIgnored -> TestStatus.SKIPPED
            else -> TestStatus.PASSED
        }
        val stackTrace = proxy.stacktrace
            ?.lines()
            ?.filter { it.trimStart().startsWith("at ") || it.contains("Exception") || it.contains("Error") }
            ?.take(MAX_STACK_FRAMES)
            ?: emptyList()

        return TestResultEntry(
            name = proxy.name,
            status = status,
            durationMs = proxy.duration?.toLong() ?: 0L,
            errorMessage = proxy.errorMessage,
            stackTrace = stackTrace
        )
    }

    private fun formatStructuredResults(allTests: List<TestResultEntry>, runName: String): ToolResult {
        val passed = allTests.count { it.status == TestStatus.PASSED }
        val failed = allTests.count { it.status == TestStatus.FAILED }
        val errors = allTests.count { it.status == TestStatus.ERROR }
        val skipped = allTests.count { it.status == TestStatus.SKIPPED }
        val totalDuration = allTests.sumOf { it.durationMs }

        val overallStatus = when {
            errors > 0 || failed > 0 -> "FAILED"
            else -> "PASSED"
        }

        val sb = StringBuilder()
        sb.appendLine("Test Run: $runName")
        sb.appendLine("Status: $overallStatus ($passed passed, $failed failed, $errors error, $skipped skipped)")
        sb.appendLine("Duration: ${formatDuration(totalDuration)}")
        sb.appendLine()

        val failedTests = allTests.filter { it.status == TestStatus.FAILED || it.status == TestStatus.ERROR }
        if (failedTests.isNotEmpty()) {
            sb.appendLine("--- FAILED ---")
            for (test in failedTests) {
                sb.appendLine("${test.name} (${formatDuration(test.durationMs)})")
                test.errorMessage?.let { sb.appendLine("  Assertion: $it") }
                if (test.stackTrace.isNotEmpty()) {
                    sb.appendLine("  Stack:")
                    for (frame in test.stackTrace) {
                        sb.appendLine("    $frame")
                    }
                }
                sb.appendLine()
            }
        }

        val skippedTests = allTests.filter { it.status == TestStatus.SKIPPED }
        if (skippedTests.isNotEmpty()) {
            sb.appendLine("--- SKIPPED ---")
            for (test in skippedTests) {
                sb.appendLine(test.name)
            }
            sb.appendLine()
        }

        val passedTests = allTests.filter { it.status == TestStatus.PASSED }
        if (passedTests.isNotEmpty()) {
            sb.appendLine("--- PASSED ($passed tests) ---")
            val shown = passedTests.take(MAX_PASSED_SHOWN)
            for (test in shown) {
                sb.appendLine("${test.name} (${formatDuration(test.durationMs)})")
            }
            if (passedTests.size > MAX_PASSED_SHOWN) {
                sb.appendLine("... and ${passedTests.size - MAX_PASSED_SHOWN} more passed tests")
            }
        }

        val content = sb.toString().trimEnd()
        val capped = if (content.length > RUN_TESTS_TOKEN_CAP_CHARS) {
            content.take(RUN_TESTS_TOKEN_CAP_CHARS) + "\n... (results truncated)"
        } else {
            content
        }

        return ToolResult(
            capped,
            "$overallStatus: $passed passed, $failed failed",
            capped.length / 4,
            isError = overallStatus == "FAILED"
        )
    }

    private fun formatDuration(ms: Long): String {
        return when {
            ms < 1000 -> "${ms}ms"
            else -> "${"%.1f".format(ms / 1000.0)}s"
        }
    }

    private enum class TestStatus { PASSED, FAILED, ERROR, SKIPPED }

    private data class TestResultEntry(
        val name: String,
        val status: TestStatus,
        val durationMs: Long,
        val errorMessage: String?,
        val stackTrace: List<String>
    )

    companion object {
        const val AGENT_PREFIX = "[Agent] "

        // get_run_output constants
        private const val RUN_OUTPUT_DEFAULT_LINES = 200
        private const val RUN_OUTPUT_MAX_LINES = 1000
        private const val RUN_OUTPUT_TOKEN_CAP_CHARS = 12000

        // Console unwrap depth
        private const val MAX_UNWRAP_DEPTH = 5

        // get_test_results constants
        private const val MAX_PROCESS_WAIT_SECONDS = 600
        private const val TEST_TREE_FINALIZE_TIMEOUT_MS = 10_000L
        private const val PROGRESS_INTERVAL_MS = 10_000L
        private const val TEST_RESULTS_TOKEN_CAP_CHARS = 12000

        // run_tests constants
        private const val RUN_TESTS_DEFAULT_TIMEOUT = 300L
        private const val RUN_TESTS_MAX_TIMEOUT = 900L
        /** Build watchdog timing — polls CompilerManager.isCompilationActive
         *  instead of using a fixed timer, so large projects won't false-positive. */
        private const val BUILD_WATCHDOG_INITIAL_MS = 10_000L  // Wait 10s before first check
        private const val BUILD_WATCHDOG_POLL_MS = 2_000L      // Check every 2s while compiling
        private const val BUILD_WATCHDOG_MAX_MS = 300_000L     // Hard cap at 5 min (matches test timeout)
        private const val RUN_TESTS_MAX_OUTPUT_CHARS = 4000
        private const val RUN_TESTS_TOKEN_CAP_CHARS = 12000

        // Shared test result constants
        private const val MAX_STACK_FRAMES = 5
        private const val MAX_PASSED_SHOWN = 20

        // compile_module constants
        private const val COMPILE_MAX_ERROR_MESSAGES = 20
    }
}
