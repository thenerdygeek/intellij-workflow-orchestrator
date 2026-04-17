package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.compiler.CompilationStatusListener
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.compiler.CompilerTopics
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.TestConsoleUtils
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.builtin.RunCommandTool
import com.workflow.orchestrator.agent.tools.truncateOutput
import com.workflow.orchestrator.core.ai.TokenEstimator
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import com.intellij.task.ProjectTaskManager

/**
 * Java/Kotlin-specific runtime execution — runs JUnit/TestNG tests and compiles modules
 * via IntelliJ's CompilerManager. Registered only when the Java plugin is present
 * (see ToolRegistrationFilter.shouldRegisterJavaBuildTools).
 *
 * Split from the universal RuntimeExecTool so the LLM on PyCharm never sees these
 * actions (which cannot work without JavaPsiFacade + CompilerManager).
 */
class JavaRuntimeExecTool : AgentTool {

    /** Carries both the run settings and the resolved module out of [createJUnitRunSettings]. */
    private data class JUnitLaunchSpec(
        val settings: RunnerAndConfigurationSettings,
        val module: com.intellij.openapi.module.Module
    )

    override val name = "java_runtime_exec"

    override val description = """
Java/Kotlin runtime execution — JUnit/TestNG test running and module compilation.

Actions and their parameters:
- run_tests(class_name, method?, timeout?, use_native_runner?) → Run tests for a specific Java/Kotlin class via IntelliJ's JUnit/TestNG runner, with Maven/Gradle shell fallback (timeout default 300s, max 900s). class_name is required and must be fully qualified — use test_finder to discover test classes first.
- compile_module(module?) → Compile a Java/Kotlin module via CompilerManager (compiles entire project if omitted).

description optional: shown to user in approval dialog on run_tests, compile_module.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf("run_tests", "compile_module")
            ),
            "class_name" to ParameterProperty(
                type = "string",
                description = "Fully qualified test class name (required for run_tests — use test_finder to discover classes)"
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
            "module" to ParameterProperty(
                type = "string",
                description = "Module name — for compile_module (compiles entire project if omitted)"
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description of what this action does and why (shown to user in approval dialog) — for run_tests, compile_module"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(
        WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER
    )

    /** Resolve stream callback for live output. */
    private fun resolveStreamCallback(@Suppress("UNUSED_PARAMETER") project: Project): ((String, String) -> Unit)? {
        return RunCommandTool.streamCallback
    }

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
            "run_tests" -> executeRunTests(params, project)
            "compile_module" -> executeCompileModule(params, project)
            else -> ToolResult(
                content = "Unknown action '$action' in java_runtime_exec. Valid actions: run_tests, compile_module",
                summary = "Unknown action '$action' in java_runtime_exec",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: run_tests
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeRunTests(params: JsonObject, project: Project): ToolResult {
        val className = params["class_name"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'class_name' is required — specify a fully qualified test class to run (e.g. com.example.MyServiceTest). " +
                    "Running all tests is not supported as it can take 30+ minutes. " +
                    "Use the 'test_finder' tool to discover test classes if you don't know the class name.",
                "Error: missing class_name",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val method = params["method"]?.jsonPrimitive?.content
        val timeoutSeconds = (params["timeout"]?.jsonPrimitive?.intOrNull?.toLong() ?: RUN_TESTS_DEFAULT_TIMEOUT)
            .coerceIn(1, RUN_TESTS_MAX_TIMEOUT)
        val useNativeRunner = params["use_native_runner"]?.jsonPrimitive?.booleanOrNull ?: true

        val testTarget = if (method != null) "$className#$method" else className

        // Resolve the module once and share it with both the native runner (for config
        // wiring) and the shell fallback (for multi-module Gradle/Maven subproject path).
        val module = findModuleForClass(project, className)

        if (useNativeRunner) {
            val reasonOut = StringBuilder()
            try {
                val result = executeWithNativeRunner(project, className, method, testTarget, timeoutSeconds, reasonOut)
                if (result != null) return result
                // Explicit native opt-in but setup failed — do NOT silently use `mvn test`.
                // Previously this path silently fell through to executeWithShell, which is
                // why a multi-module project could land on Maven even with use_native_runner=true
                // (e.g. findModuleForClass returned null for a sibling module's class,
                // createJUnitRunSettings bailed, the dispatcher fell back without telling anyone).
                val reason = reasonOut.toString().ifBlank { "setup returned null without a specific reason" }
                return ToolResult(
                    content = "Native IntelliJ test runner could not be set up for '$className': $reason.\n\n" +
                        "Not falling back to Maven/Gradle shell because use_native_runner=true.\n" +
                        "Options:\n" +
                        "- Fix the underlying cause (most common: class not in the project source roots, " +
                        "module not resolvable, or the JUnit/TestNG plugin is disabled).\n" +
                        "- Pass use_native_runner=false to run via `mvn test` / `./gradlew test`.",
                    summary = "Native runner unavailable: $reason",
                    tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            } catch (e: Exception) {
                val shellResult = executeWithShell(project, testTarget, timeoutSeconds, module)
                val warning = "[WARNING] Native test runner threw ${e.javaClass.simpleName}: ${e.message}, used shell fallback.\n\n"
                return shellResult.copy(content = warning + shellResult.content)
            }
        }

        return executeWithShell(project, testTarget, timeoutSeconds, module)
    }

    private suspend fun executeWithNativeRunner(
        project: Project, className: String, method: String?,
        testTarget: String, timeoutSeconds: Long,
        reasonOut: StringBuilder
    ): ToolResult? {
        val launchSpec = createJUnitRunSettings(project, className, method, reasonOut) ?: return null
        val settings = launchSpec.settings
        val testModule = launchSpec.module

        val processHandlerRef = AtomicReference<ProcessHandler?>(null)
        val descriptorRef = AtomicReference<RunContentDescriptor?>(null)
        val buildConnectionRef = AtomicReference<com.intellij.util.messages.MessageBusConnection?>(null)

        val result = withTimeoutOrNull(timeoutSeconds * 1000) {
            suspendCancellableCoroutine { continuation ->
                // Kill the spawned process and disconnect the bus watcher when the agent is
                // stopped or when withTimeoutOrNull fires — prevents orphaned JUnit processes
                // that block subsequent runs with "initialization error".
                continuation.invokeOnCancellation {
                    processHandlerRef.get()?.destroyProcess()
                    buildConnectionRef.get()?.disconnect()
                }
                com.intellij.openapi.application.invokeLater {
                    try {
                        // Capture the full CompileContext so the build-failure branch can
                        // walk per-file error messages via formatCompileErrors(). Prior
                        // implementation stored only "N errors, M warnings" in a String,
                        // which the LLM misread as "TDD red phase" — missing that the
                        // real failure was a typo the user could see at file:line:col.
                        //
                        // AtomicReference for thread safety: compilationFinished() fires
                        // on a background thread (CompilerManager's build thread) while
                        // the outer scope may touch the ref from EDT.
                        val compileContextRef = AtomicReference<CompileContext?>(null)
                        val buildConnection = project.messageBus.connect()
                        buildConnectionRef.set(buildConnection)  // expose for invokeOnCancellation

                        // Subscribe to CompilationStatusListener BEFORE starting the build so
                        // we capture the CompileContext from the build phase. Direct typed
                        // subscription — no reflection needed; the class and topic are
                        // part of the public intellij.java.compiler module.
                        buildConnection.subscribe(
                            CompilerTopics.COMPILATION_STATUS,
                            object : CompilationStatusListener {
                                override fun compilationFinished(
                                    aborted: Boolean,
                                    errors: Int,
                                    warnings: Int,
                                    compileContext: CompileContext
                                ) {
                                    if (aborted || errors > 0) {
                                        compileContextRef.set(compileContext)
                                    }
                                }
                            }
                        )

                        // Explicit build phase: the transient RunnerAndConfigurationSettings is
                        // intentionally never registered in RunManager (see commit 9b164bf3), so
                        // IntelliJ's factory-default "Build" before-run task is NOT wired to it.
                        // We invoke ProjectTaskManager.build(module) ourselves to guarantee the
                        // test class is compiled before JUnit starts — preventing initializationError.
                        //
                        // Do NOT "fix" this by calling RunManager.setTemporaryConfiguration(settings):
                        // that API sets selectedConfiguration as a side-effect and re-triggers the
                        // "initialization error on next manual run" regression from commit 9b164bf3.
                        try {
                            ProjectTaskManager.getInstance(project)
                                .build(testModule)
                                .onSuccess { buildResult ->
                                    buildConnection.disconnect()
                                    buildConnectionRef.set(null)

                                    if (buildResult.hasErrors() || buildResult.isAborted) {
                                        if (continuation.isActive) {
                                            continuation.resume(
                                                buildCompileFailureResult(
                                                    compileContextRef.get(),
                                                    testTarget,
                                                    buildResult.isAborted
                                                )
                                            )
                                        }
                                        return@onSuccess
                                    }

                                    // Build succeeded — launch JUnit on EDT.
                                    com.intellij.openapi.application.invokeLater {
                                        try {
                                            val executor = DefaultRunExecutor.getRunExecutorInstance()
                                            val env = ExecutionEnvironmentBuilder
                                                .createOrNull(executor, settings)
                                                ?.build()

                                            if (env == null) {
                                                reasonOut.append("ExecutionEnvironmentBuilder.createOrNull returned null (no runner registered for this configuration)")
                                                if (continuation.isActive) continuation.resume(null)
                                                return@invokeLater
                                            }

                                            val callback = object : ProgramRunner.Callback {
                                                override fun processStarted(descriptor: RunContentDescriptor?) {
                                                    if (descriptor == null) {
                                                        reasonOut.append("ProgramRunner.Callback produced no RunContentDescriptor (the runner refused to start the process)")
                                                        if (continuation.isActive) continuation.resume(null)
                                                        return
                                                    }
                                                    handleDescriptorReady(descriptor, continuation, testTarget, descriptorRef, processHandlerRef, project)
                                                }
                                            }

                                            try {
                                                ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)
                                            } catch (_: NoSuchMethodError) {
                                                env.callback = callback
                                                ProgramRunnerUtil.executeConfiguration(env, false, true)
                                            }

                                            // Defence-in-depth watchdog: ExecutionListener.processNotStarted()
                                            // fires when the execution framework aborts the run for non-build
                                            // reasons (no ProgramRunner registered, executor disabled, JDK
                                            // resolution failure, etc.). Build failures are caught above via
                                            // ProjectTaskManager.build — this handles everything else.
                                            val runConnection = project.messageBus.connect()
                                            buildConnectionRef.set(runConnection)

                                            runConnection.subscribe(
                                                com.intellij.execution.ExecutionManager.EXECUTION_TOPIC,
                                                object : com.intellij.execution.ExecutionListener {
                                                    override fun processNotStarted(
                                                        executorId: String,
                                                        e: com.intellij.execution.runners.ExecutionEnvironment
                                                    ) {
                                                        if (e == env) {
                                                            runConnection.disconnect()
                                                            if (continuation.isActive) {
                                                                continuation.resume(ToolResult(
                                                                    content = "Test execution did not start after a successful build.\n\n" +
                                                                        "Possible causes: no ProgramRunner registered for this configuration, " +
                                                                        "executor is disabled, or JDK resolution failed.",
                                                                    summary = "Run aborted after successful build",
                                                                    tokenEstimate = 30,
                                                                    isError = true
                                                                ))
                                                            }
                                                        }
                                                    }

                                                    override fun processStarted(
                                                        executorId: String,
                                                        e: com.intellij.execution.runners.ExecutionEnvironment,
                                                        handler: com.intellij.execution.process.ProcessHandler
                                                    ) {
                                                        if (e == env) runConnection.disconnect()
                                                    }
                                                }
                                            )

                                            // Safety: disconnect on timeout to prevent leaks
                                            Thread {
                                                try {
                                                    Thread.sleep(BUILD_WATCHDOG_MAX_MS)
                                                    runConnection.disconnect()
                                                } catch (_: InterruptedException) { /* normal */ }
                                            }.apply { isDaemon = true; name = "build-watchdog-timeout"; start() }

                                        } catch (e: Exception) {
                                            reasonOut.append("run launch threw ${e.javaClass.simpleName}: ${e.message}")
                                            if (continuation.isActive) continuation.resume(null)
                                        }
                                    }
                                }
                                .onError { _ ->
                                    buildConnection.disconnect()
                                    buildConnectionRef.set(null)
                                    if (continuation.isActive) {
                                        continuation.resume(
                                            buildCompileFailureResult(
                                                compileContextRef.get(),
                                                testTarget,
                                                aborted = false
                                            )
                                        )
                                    }
                                }
                        } catch (e: Exception) {
                            buildConnection.disconnect()
                            buildConnectionRef.set(null)
                            reasonOut.append("ProjectTaskManager.build threw ${e.javaClass.simpleName}: ${e.message}")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    } catch (e: Exception) {
                        reasonOut.append("setup threw ${e.javaClass.simpleName}: ${e.message}")
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

    /**
     * Build the [ToolResult] returned when `ProjectTaskManager.build(testModule)` fails
     * before tests can launch. Routes per-file error messages through the shared
     * [formatCompileErrors] helper when a [CompileContext] was captured by the
     * [CompilationStatusListener] — otherwise falls back to a generic message that
     * still tells the LLM "BUILD FAILED" rather than "Compilation result: N errors".
     *
     * Leading-line format: `BUILD FAILED — N compile error(s) prevented tests from starting:`
     * — matches the plan document so the LLM cannot skim-read this as a red test.
     */
    private fun buildCompileFailureResult(
        context: CompileContext?,
        testTarget: String,
        aborted: Boolean
    ): ToolResult {
        if (context != null) {
            val errorCount = context.getMessages(CompilerMessageCategory.ERROR).size
            val leading = if (errorCount > 0) {
                "BUILD FAILED — $errorCount compile error(s) prevented tests from starting:"
            } else {
                // Build reported failure but no error messages were captured. Rare, but
                // possible when the build aborts for a non-compile reason (e.g. Gradle
                // before-task). Give a generic leading line.
                "BUILD FAILED — test execution did not start."
            }
            return formatCompileErrors(
                context = context,
                target = testTarget,
                leadingLine = leading
            )
        }
        // Fall back to a generic failure message when no CompileContext was captured —
        // typically means the listener callback didn't fire (reflection/early-abort path).
        val reason = if (aborted) "build was aborted" else "build failed with no compile context captured"
        return ToolResult(
            content = "BUILD FAILED — test execution did not start.\n\n" +
                "Reason: $reason.\n\n" +
                "Fix the compilation errors and try again. " +
                "Use diagnostics tool to check for errors in the test class.",
            summary = "Build failed before tests ($reason)",
            tokenEstimate = 30,
            isError = true
        )
    }

    private fun handleDescriptorReady(
        descriptor: RunContentDescriptor,
        continuation: CancellableContinuation<ToolResult?>,
        testTarget: String,
        descriptorRef: AtomicReference<RunContentDescriptor?>,
        processHandlerRef: AtomicReference<ProcessHandler?>,
        project: Project? = null
    ) {
        descriptorRef.set(descriptor)
        val handler = descriptor.processHandler
        processHandlerRef.set(handler)

        val toolCallId = RunCommandTool.currentToolCallId.get()
        val activeStreamCallback = if (project != null) resolveStreamCallback(project) else RunCommandTool.streamCallback

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
                        continuation.resume(interpretTestRoot(root, descriptor.displayName ?: testTarget))
                    }
                }
            })
        } else {
            // Fallback: no test console available — wait for process exit, then retry
            // until the test tree is populated. No TestResultsViewer.EventsListener is
            // available here, so we poll with short intervals instead of a blind 2s Timer.
            if (handler != null) {
                val doExtract = {
                    Thread {
                        for (attempt in 1..TEST_TREE_RETRY_ATTEMPTS) {
                            if (!continuation.isActive) return@Thread
                            if (TestConsoleUtils.findTestRoot(descriptor)?.children?.isNotEmpty() == true) break
                            Thread.sleep(TEST_TREE_RETRY_INTERVAL_MS)
                        }
                        if (continuation.isActive) {
                            continuation.resume(extractNativeResults(descriptor, testTarget))
                        }
                    }.apply { isDaemon = true; name = "test-tree-finalize"; start() }
                }

                if (handler.isProcessTerminated) {
                    doExtract()
                } else {
                    handler.addProcessListener(object : ProcessAdapter() {
                        override fun processTerminated(event: ProcessEvent) {
                            doExtract()
                        }
                    })
                }
            } else {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private fun createJUnitRunSettings(
        project: Project, className: String, method: String?,
        reasonOut: StringBuilder
    ): JUnitLaunchSpec? {
        // reasonOut is appended (single reason) on every return-null branch so the
        // dispatcher can surface WHY the native runner could not be set up instead
        // of silently falling back to `mvn test`. Each branch writes its own reason
        // only if the builder is still empty, so the earliest failure wins.
        fun fail(why: String): JUnitLaunchSpec? {
            if (reasonOut.isEmpty()) reasonOut.append(why)
            return null
        }
        return try {
            val runManager = RunManager.getInstance(project)

            val testFramework = detectTestFramework(project, className)
            val configTypeId = when (testFramework) {
                "TestNG" -> "TestNG"
                else -> "JUnit"
            }
            val testConfigType = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.find { type ->
                type.id == configTypeId || type.displayName == configTypeId
            } ?: return fail("no '$configTypeId' ConfigurationType registered (is the JUnit/TestNG plugin enabled?)")

            val factory = testConfigType.configurationFactories.firstOrNull()
                ?: return fail("$configTypeId ConfigurationType has no configuration factories")
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
                    return fail("$configTypeId config exposes neither getPersistentData nor getPersistantData (unexpected plugin version)")
                }
            } catch (e: Exception) {
                return fail("failed to populate $configTypeId persistent data via reflection: ${e.javaClass.simpleName}: ${e.message}")
            }

            val testModule = findModuleForClass(project, className)
                ?: return fail("could not resolve an IntelliJ module for '$className'. " +
                    "Most common cause in a multi-module project: the class is not under any module's source roots, " +
                    "or the module containing it hasn't been re-imported since it was added. " +
                    "Open the test class in the editor and verify it has a module badge on the file tab")
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
            // Do NOT add to RunManager and do NOT overwrite selectedConfiguration.
            // ExecutionEnvironmentBuilder doesn't require the config to be registered,
            // and stealing the user's selected config causes "initialization error" on
            // the next manual run after the agent is stopped.
            // See commit 9b164bf3 — do NOT "fix" this by calling
            // RunManager.setTemporaryConfiguration(settings): that sets selectedConfiguration
            // as a side-effect and re-triggers the original bug.
            JUnitLaunchSpec(settings, testModule)
        } catch (e: Exception) {
            fail("unexpected ${e.javaClass.simpleName} during native run config setup: ${e.message}")
        }
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

        return interpretTestRoot(testRoot, descriptor.displayName ?: testTarget)
    }

    private fun executeWithShell(
        project: Project,
        testTarget: String,
        timeoutSeconds: Long,
        module: com.intellij.openapi.module.Module? = null
    ): ToolResult {
        val basePath = project.basePath
            ?: return ToolResult("Error: no project base path available", "Error: no project", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val baseDir = File(basePath)
        val hasMaven = File(baseDir, "pom.xml").exists()
        val hasGradle = File(baseDir, "build.gradle").exists() || File(baseDir, "build.gradle.kts").exists()

        // Derive the Gradle subproject path from the module's content root directory.
        // For a module rooted at <projectRoot>/core/, the Gradle task is :core:test.
        // For nested modules (<projectRoot>/services/auth/), the task is :services:auth:test.
        // Falls back to a root-level `test` task if the module dir can't be determined or
        // if it equals the project root (single-module project).
        val gradleSubprojectPath: String? = module?.let { m ->
            try {
                val contentRoot = com.intellij.openapi.roots.ModuleRootManager.getInstance(m)
                    .contentRoots.firstOrNull()?.path
                if (contentRoot != null) {
                    val rel = contentRoot.removePrefix(basePath).trimStart('/', File.separatorChar)
                    if (rel.isNotBlank()) ":" + rel.replace(File.separatorChar, ':') else null
                } else null
            } catch (_: Exception) { null }
        }

        // Maven: for multi-module projects, restrict the build to the module containing
        // the test class. Maven module directories contain their own pom.xml.
        val mavenModuleDir: File? = module?.let { m ->
            try {
                val contentRoot = com.intellij.openapi.roots.ModuleRootManager.getInstance(m)
                    .contentRoots.firstOrNull()?.path
                if (contentRoot != null && File(contentRoot, "pom.xml").exists()) File(contentRoot)
                else null
            } catch (_: Exception) { null }
        }

        val command = when {
            hasMaven -> {
                val className = testTarget.substringBefore('#')
                val methodPart = if ('#' in testTarget) "#${testTarget.substringAfter('#')}" else ""
                if (mavenModuleDir != null && mavenModuleDir != baseDir) {
                    // Run only the submodule to avoid rebuilding unrelated modules
                    "mvn test -Dtest=${className}${methodPart} -Dsurefire.useFile=false -q --also-make"
                } else {
                    "mvn test -Dtest=${className}${methodPart} -Dsurefire.useFile=false -q"
                }
            }
            hasGradle -> {
                val gradleWrapper = if (File(baseDir, "gradlew").exists()) "./gradlew" else "gradle"
                val gradleTarget = testTarget.replace('#', '.')
                if (gradleSubprojectPath != null) {
                    // Multi-module: run only the subproject that owns the test class
                    "$gradleWrapper ${gradleSubprojectPath}:test --tests '$gradleTarget' --no-daemon -q"
                } else {
                    "$gradleWrapper test --tests '$gradleTarget' --no-daemon -q"
                }
            }
            else -> return ToolResult(
                "No Maven (pom.xml) or Gradle (build.gradle) build file found in project root.",
                "No build tool found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        // For Maven multi-module: run from the submodule directory so Maven uses the
        // submodule's pom.xml and doesn't walk unrelated modules. For everything else
        // (Gradle, single-module Maven) always run from the project root.
        val workDir = if (hasMaven && mavenModuleDir != null && mavenModuleDir != baseDir) mavenModuleDir else baseDir

        return try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val processBuilder = if (isWindows) {
                ProcessBuilder("cmd.exe", "/c", command)
            } else {
                ProcessBuilder("sh", "-c", command)
            }

            processBuilder.directory(workDir)
            processBuilder.redirectErrorStream(true)

            val process = processBuilder.start()
            val toolCallId = RunCommandTool.currentToolCallId.get()
            val activeStreamCallback = resolveStreamCallback(project)

            val outputBuilder = StringBuilder()
            val readerThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            outputBuilder.appendLine(line)
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
                val truncatedOutput = truncateOutput(outputBuilder.toString(), RUN_TESTS_MAX_OUTPUT_CHARS)
                return ToolResult(
                    "[TIMEOUT] Test execution timed out after ${timeoutSeconds}s for $testTarget.\nPartial output:\n$truncatedOutput",
                    "Test timeout", TokenEstimator.estimate(truncatedOutput), isError = true
                )
            }

            readerThread.join(2000)
            val rawOutput = outputBuilder.toString()
            // Check build failure markers on full output before truncation
            val isBuildFailure = rawOutput.contains("BUILD FAILURE") ||       // Maven
                rawOutput.contains("COMPILATION ERROR") ||                    // Maven
                rawOutput.contains("compileTestJava FAILED") ||               // Gradle Java
                rawOutput.contains("compileTestKotlin FAILED")                // Gradle Kotlin
            val truncatedOutput = truncateOutput(rawOutput, RUN_TESTS_MAX_OUTPUT_CHARS)

            val exitCode = process.exitValue()
            if (exitCode == 0) {
                ToolResult("Tests PASSED for $testTarget.\n\n$truncatedOutput", "Tests PASSED: $testTarget", TokenEstimator.estimate(truncatedOutput))
            } else {
                // Distinguish build/compilation failure from test failure so the agent
                // doesn't confuse "no tests ran" with "tests ran and failed".
                if (isBuildFailure) {
                    ToolResult(
                        "BUILD FAILED — test execution did not start (exit code $exitCode).\n\n" +
                            "Fix compilation errors and try again. Use diagnostics tool to check for errors.\n\n" +
                            truncatedOutput,
                        "Build failed before tests",
                        TokenEstimator.estimate(truncatedOutput),
                        isError = true
                    )
                } else {
                    ToolResult(
                        "Tests FAILED for $testTarget (exit code $exitCode).\n\n$truncatedOutput",
                        "Tests FAILED: $testTarget", TokenEstimator.estimate(truncatedOutput), isError = true
                    )
                }
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
                                    // Delegate to the shared formatter so run_tests and compile_module
                                    // produce identical per-file output. Leading line mirrors the old
                                    // format for caller-compatibility.
                                    formatCompileErrors(
                                        context = context,
                                        target = target,
                                        leadingLine = "Compilation of $target failed: $errors error(s), $warnings warning(s).",
                                        warnings = warnings
                                    )
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
}
