package com.workflow.orchestrator.agent.tools.ide

import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.workflow.orchestrator.agent.tools.builtin.RunCommandTool
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * Runs a specific test class or test method using IntelliJ's native test runner
 * with structured output, falling back to Maven/Gradle shell execution.
 *
 * When use_native_runner=true (default), creates a JUnit run configuration
 * programmatically and executes it via IntelliJ's execution framework,
 * capturing structured SMTestProxy results.
 *
 * When use_native_runner=false or native runner fails, falls back to
 * ProcessBuilder-based Maven/Gradle execution.
 */
class RunTestsTool : AgentTool {
    override val name = "run_tests"
    override val description = "Run a specific test class or test method using IntelliJ's native test runner. Returns structured pass/fail with failure messages, assertions, and stack traces. Falls back to Maven/Gradle shell if native runner unavailable."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "class_name" to ParameterProperty(
                type = "string",
                description = "Fully qualified test class name (e.g., 'com.example.UserServiceTest')"
            ),
            "method" to ParameterProperty(
                type = "string",
                description = "Optional: specific test method name to run"
            ),
            "timeout" to ParameterProperty(
                type = "integer",
                description = "Seconds before test process is killed (default: 120, max: 600)"
            ),
            "use_native_runner" to ParameterProperty(
                type = "boolean",
                description = "Use IntelliJ native test runner (true) or Maven/Gradle shell (false). Default: true"
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description of what this action does and why (shown to user in approval dialog)"
            )
        ),
        required = listOf("class_name", "description")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER)

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 300L
        private const val MAX_TIMEOUT_SECONDS = 900L
        private const val MAX_OUTPUT_CHARS = 4000
        private const val MAX_STACK_FRAMES = 5
        private const val MAX_PASSED_SHOWN = 20
        private const val TOKEN_CAP_CHARS = 12000
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val className = params["class_name"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'class_name' parameter is required",
                "Error: missing class_name",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val method = params["method"]?.jsonPrimitive?.content
        val timeoutSeconds = (params["timeout"]?.jsonPrimitive?.intOrNull?.toLong() ?: DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)
        val useNativeRunner = params["use_native_runner"]?.jsonPrimitive?.booleanOrNull ?: true

        val testTarget = if (method != null) "$className#$method" else className

        if (useNativeRunner) {
            try {
                val result = executeWithNativeRunner(project, className, method, testTarget, timeoutSeconds)
                if (result != null) return result
                // null means native runner couldn't be set up — fall through to shell
            } catch (e: Exception) {
                // Native runner failed — fall back to shell with warning
                val shellResult = executeWithShell(project, testTarget, timeoutSeconds)
                val warning = "[WARNING] Native test runner failed (${e.javaClass.simpleName}: ${e.message}), used shell fallback.\n\n"
                return shellResult.copy(content = warning + shellResult.content)
            }
        }

        return executeWithShell(project, testTarget, timeoutSeconds)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Native IntelliJ Test Runner
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Execute tests via IntelliJ's native test runner.
     * Returns null if JUnit configuration cannot be created (e.g., JUnit plugin not available).
     */
    private suspend fun executeWithNativeRunner(
        project: Project,
        className: String,
        method: String?,
        testTarget: String,
        timeoutSeconds: Long
    ): ToolResult? {
        // Try to create a JUnit run configuration programmatically
        val settings = createJUnitRunSettings(project, className, method)
            ?: return null // JUnit plugin not available, signal to fall back

        val processHandlerRef = AtomicReference<ProcessHandler?>(null)
        val simpleClassName = className.substringAfterLast('.')

        // Launch the test run on EDT and wait for completion with timeout
        val result = withTimeoutOrNull(timeoutSeconds * 1000) {
            withContext(Dispatchers.EDT) {
                val executor = DefaultRunExecutor.getRunExecutorInstance()
                val env = ExecutionEnvironmentBuilder
                    .createOrNull(executor, settings)
                    ?.build()
                    ?: return@withContext null

                ProgramRunnerUtil.executeConfiguration(env, false, true)
            }

            // Poll for the run descriptor to appear and attach a process listener.
            // No internal poll limit — the outer withTimeoutOrNull handles the deadline.
            // Build + compilation can take minutes for large Gradle/Maven projects.
            suspendCancellableCoroutine { continuation ->
                val timer = java.util.Timer()
                continuation.invokeOnCancellation {
                    timer.cancel()
                }
                timer.schedule(object : java.util.TimerTask() {
                    override fun run() {
                        try {
                            val contentManager = RunContentManager.getInstance(project)
                            // Match by class name OR by any descriptor with a running/recently-started process
                            val descriptor = contentManager.allDescriptors.firstOrNull { desc ->
                                val name = desc.displayName ?: ""
                                name.contains(simpleClassName, ignoreCase = true) ||
                                    name.contains(className, ignoreCase = true)
                            } ?: contentManager.allDescriptors.firstOrNull { desc ->
                                // Fallback: find the most recent descriptor with an active process
                                desc.processHandler?.let { !it.isProcessTerminated || it.isProcessTerminating } == true
                            }

                            val handler = descriptor?.processHandler
                            if (handler != null) {
                                timer.cancel()
                                processHandlerRef.set(handler)

                                if (handler.isProcessTerminated) {
                                    // Wait for test framework to populate SMTestProxy tree
                                    waitForResultsThenResume(continuation, project, simpleClassName, testTarget)
                                } else {
                                    // Stream console output to chat UI in real-time
                                    val toolCallId = RunCommandTool.currentToolCallId.get()
                                    val activeStreamCallback = RunCommandTool.streamCallback

                                    handler.addProcessListener(object : ProcessAdapter() {
                                        override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                                            val text = event.text ?: return
                                            if (toolCallId != null && text.isNotBlank()) {
                                                activeStreamCallback?.invoke(toolCallId, text)
                                            }
                                        }

                                        override fun processTerminated(event: ProcessEvent) {
                                            waitForResultsThenResume(continuation, project, simpleClassName, testTarget)
                                        }
                                    })
                                }
                            }
                            // else: keep polling — outer withTimeoutOrNull handles the deadline
                        } catch (e: Exception) {
                            timer.cancel()
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    }
                }, 100, 500) // Initial 100ms delay, poll every 500ms
            }
        }

        if (result == null && processHandlerRef.get() != null) {
            // Timed out — kill process and collect partial results
            processHandlerRef.get()?.destroyProcess()
            // Brief delay to let results populate
            delay(300)

            val partialResult = extractNativeResults(project, simpleClassName, testTarget)
            return if (partialResult != null) {
                partialResult.copy(
                    content = "[TIMEOUT] Test execution timed out after ${timeoutSeconds}s. Partial results:\n\n${partialResult.content}",
                    isError = true
                )
            } else {
                ToolResult(
                    "[TIMEOUT] Test execution timed out after ${timeoutSeconds}s for $testTarget. No results captured.",
                    "Test timeout",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }
        }

        return result
    }

    /**
     * Poll for SMTestProxy results to be populated after process termination.
     * The test framework populates the tree asynchronously — a fixed delay is unreliable.
     * Polls every 200ms for up to 5 seconds for leaf test results to appear.
     */
    private fun waitForResultsThenResume(
        continuation: kotlin.coroutines.Continuation<ToolResult?>,
        project: Project,
        simpleClassName: String,
        testTarget: String
    ) {
        val cancellable = continuation as? kotlinx.coroutines.CancellableContinuation<ToolResult?> ?: return
        val timer = java.util.Timer()
        timer.schedule(object : java.util.TimerTask() {
            private var polls = 0
            override fun run() {
                polls++
                if (!cancellable.isActive) {
                    timer.cancel()
                    return
                }
                val result = extractNativeResults(project, simpleClassName, testTarget)
                val hasLeafResults = result != null && !result.content.contains("no test methods found") && !result.content.contains("no structured results")
                if (hasLeafResults || polls >= 25) {
                    timer.cancel()
                    if (cancellable.isActive) {
                        cancellable.resume(result)
                    }
                }
            }
        }, 500, 200) // Initial 500ms delay, then poll every 200ms
    }

    /**
     * Create a JUnit RunnerAndConfigurationSettings for the given class/method.
     * Returns null if JUnit configuration type is not available.
     * Uses reflection to avoid hard dependency on the JUnit plugin.
     */
    private fun createJUnitRunSettings(
        project: Project,
        className: String,
        method: String?
    ): com.intellij.execution.RunnerAndConfigurationSettings? {
        return try {
            val runManager = RunManager.getInstance(project)

            // Detect test framework from the class annotations, then find the matching config type.
            // Supports JUnit 4/5 and TestNG.
            val testFramework = detectTestFramework(project, className)
            val configTypeId = when (testFramework) {
                "TestNG" -> "TestNG"
                else -> "JUnit" // default to JUnit (covers JUnit 4 + 5)
            }
            val testConfigType = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.find { type ->
                type.id == configTypeId || type.displayName == configTypeId
            } ?: return null

            val factory = testConfigType.configurationFactories.firstOrNull() ?: return null
            val configName = "${className.substringAfterLast('.')}${if (method != null) ".$method" else ""}"
            val settings = runManager.createConfiguration(configName, factory)

            val config = settings.configuration
            val isTestNG = testFramework == "TestNG"

            // Set test class and method via the data object.
            //
            // JUnit:  getPersistentData() → JUnitConfiguration.Data
            //         TEST_OBJECT values: lowercase — "class", "method", "package", etc.
            //
            // TestNG: getPersistantData() → TestData   (note: typo in API name, missing 'e')
            //         TEST_OBJECT values: UPPERCASE — "CLASS", "METHOD", "PACKAGE", etc.
            //
            // Both have public fields: TEST_OBJECT, MAIN_CLASS_NAME, METHOD_NAME, PACKAGE_NAME.
            // Using setMainClassName()/setMethodName() does NOT work — those methods don't exist.
            try {
                // TestNG uses getPersistantData (typo), JUnit uses getPersistentData
                val dataMethodName = if (isTestNG) "getPersistantData" else "getPersistentData"
                val getDataMethod = config.javaClass.methods.find { it.name == dataMethodName }
                val data = getDataMethod?.invoke(config)
                if (data != null) {
                    val testObjectField = data.javaClass.getField("TEST_OBJECT")
                    val mainClassField = data.javaClass.getField("MAIN_CLASS_NAME")

                    // JUnit uses lowercase ("class"/"method"), TestNG uses UPPERCASE ("CLASS"/"METHOD")
                    val testType = if (method != null) {
                        if (isTestNG) "METHOD" else "method"
                    } else {
                        if (isTestNG) "CLASS" else "class"
                    }
                    testObjectField.set(data, testType)
                    mainClassField.set(data, className)

                    // Set package name (extracted from fully qualified class name)
                    try {
                        val packageField = data.javaClass.getField("PACKAGE_NAME")
                        val packageName = className.substringBeforeLast('.', "")
                        packageField.set(data, packageName)
                    } catch (_: Exception) { /* optional field */ }

                    if (method != null) {
                        val methodField = data.javaClass.getField("METHOD_NAME")
                        methodField.set(data, method)
                    }
                } else {
                    // Data accessor not available — native runner won't work
                    return null
                }
            } catch (_: Exception) {
                // Plugin fields not accessible — fall back to shell
                return null
            }

            // Resolve and set the module containing the test class.
            // Without this, IntelliJ can't resolve the classpath and forces the Edit Configuration dialog.
            val testModule = findModuleForClass(project, className)
            if (testModule == null) {
                // Can't resolve module — fall back to shell to avoid IntelliJ showing Edit Config dialog
                return null
            }
            run {
                // ModuleBasedConfiguration.setModule(Module)
                try {
                    val setModuleMethod = config.javaClass.getMethod("setModule", com.intellij.openapi.module.Module::class.java)
                    setModuleMethod.invoke(config, testModule)
                } catch (_: Exception) {
                    // Try alternative: config.configurationModule.module = testModule
                    try {
                        val getConfigModule = config.javaClass.getMethod("getConfigurationModule")
                        val configModule = getConfigModule.invoke(config)
                        val setModule = configModule.javaClass.getMethod("setModule", com.intellij.openapi.module.Module::class.java)
                        setModule.invoke(configModule, testModule)
                    } catch (_: Exception) {
                        // Can't set module — native runner may show dialog or fail
                    }
                }
            }

            // Register as temporary configuration
            settings.isTemporary = true
            runManager.addConfiguration(settings)
            runManager.selectedConfiguration = settings

            settings
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Detect whether a test class uses JUnit or TestNG by checking its annotations/imports.
     * Returns "JUnit", "TestNG", or "Unknown".
     */
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
        } catch (_: Exception) {
            "Unknown"
        }
    }

    /**
     * Find the IntelliJ module containing the given fully-qualified class.
     * Uses JavaPsiFacade to locate the class, then ModuleUtilCore to find its module.
     */
    private fun findModuleForClass(project: Project, className: String): com.intellij.openapi.module.Module? {
        return try {
            com.intellij.openapi.application.ReadAction.compute<com.intellij.openapi.module.Module?, Exception> {
                val psiClass = com.intellij.psi.JavaPsiFacade.getInstance(project)
                    .findClass(className, com.intellij.psi.search.GlobalSearchScope.projectScope(project))
                    ?: return@compute null
                com.intellij.openapi.module.ModuleUtilCore.findModuleForPsiElement(psiClass)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extract structured test results from the most recent RunContentDescriptor
     * matching the given class name, using SMTestProxy tree.
     */
    private fun extractNativeResults(project: Project, simpleClassName: String, testTarget: String): ToolResult? {
        val contentManager = RunContentManager.getInstance(project)
        val descriptor = contentManager.allDescriptors.firstOrNull { desc ->
            desc.displayName?.contains(simpleClassName, ignoreCase = true) == true
        } ?: return null

        val testRoot = findTestRoot(descriptor)
        if (testRoot == null) {
            return ToolResult(
                "Test run completed for $testTarget but no structured results available.\n" +
                    "Run session: ${descriptor.displayName}",
                "Tests completed, no structured data",
                20
            )
        }

        val allTests = collectTestResults(testRoot)
        if (allTests.isEmpty()) {
            return ToolResult(
                "Test run completed for $testTarget but no test methods found in results.",
                "No tests found",
                10
            )
        }

        return formatStructuredResults(allTests, descriptor.displayName ?: testTarget)
    }

    private fun findTestRoot(descriptor: RunContentDescriptor): SMTestProxy.SMRootTestProxy? {
        return try {
            val console = descriptor.executionConsole ?: return null
            val viewerMethod = console.javaClass.methods.find { it.name == "getResultsViewer" }
            val viewer = viewerMethod?.invoke(console) ?: return null
            val rootMethod = viewer.javaClass.methods.find { it.name == "getTestsRootNode" }
            rootMethod?.invoke(viewer) as? SMTestProxy.SMRootTestProxy
        } catch (_: Exception) {
            null
        }
    }

    private fun collectTestResults(root: SMTestProxy): List<TestResultEntry> {
        val results = mutableListOf<TestResultEntry>()
        collectLeafTests(root, results)
        return results
    }

    private fun collectLeafTests(proxy: AbstractTestProxy, results: MutableList<TestResultEntry>) {
        if (proxy.isLeaf) {
            val smProxy = proxy as? SMTestProxy
            val status = when {
                smProxy?.isDefect == true -> {
                    if (smProxy.stacktrace?.contains("AssertionError") == true ||
                        smProxy.stacktrace?.contains("AssertionFailedError") == true
                    ) {
                        TestStatus.FAILED
                    } else {
                        TestStatus.ERROR
                    }
                }
                smProxy?.isIgnored == true -> TestStatus.SKIPPED
                else -> TestStatus.PASSED
            }

            val stackTrace = smProxy?.stacktrace
                ?.lines()
                ?.filter { it.trimStart().startsWith("at ") || it.contains("Exception") || it.contains("Error") }
                ?.take(MAX_STACK_FRAMES)
                ?: emptyList()

            results.add(
                TestResultEntry(
                    name = proxy.name,
                    status = status,
                    durationMs = smProxy?.duration?.toLong() ?: 0L,
                    errorMessage = smProxy?.errorMessage,
                    stackTrace = stackTrace
                )
            )
        } else {
            for (child in proxy.children) {
                collectLeafTests(child, results)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Structured Output Formatting (shared by native and shell paths)
    // ──────────────────────────────────────────────────────────────────────

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

        // Failed/error tests first — full details
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

        // Skipped tests
        val skippedTests = allTests.filter { it.status == TestStatus.SKIPPED }
        if (skippedTests.isNotEmpty()) {
            sb.appendLine("--- SKIPPED ---")
            for (test in skippedTests) {
                sb.appendLine(test.name)
            }
            sb.appendLine()
        }

        // Passed tests — abbreviated
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
        val capped = if (content.length > TOKEN_CAP_CHARS) {
            content.take(TOKEN_CAP_CHARS) + "\n... (results truncated)"
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

    // ──────────────────────────────────────────────────────────────────────
    // Shell Fallback (Maven / Gradle)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Execute tests via Maven or Gradle shell process.
     * This is the fallback when native runner is unavailable or fails.
     */
    private fun executeWithShell(project: Project, testTarget: String, timeoutSeconds: Long): ToolResult {
        val basePath = project.basePath
            ?: return ToolResult(
                "Error: no project base path available",
                "Error: no project",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val baseDir = File(basePath)
        val hasMaven = File(baseDir, "pom.xml").exists()
        val hasGradle = File(baseDir, "build.gradle").exists() ||
            File(baseDir, "build.gradle.kts").exists()

        val command = when {
            hasMaven -> buildMavenCommand(testTarget)
            hasGradle -> buildGradleCommand(testTarget, baseDir)
            else -> return ToolResult(
                "No Maven (pom.xml) or Gradle (build.gradle) build file found in project root.",
                "No build tool found",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
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

            // Get tool call ID for streaming (set by SingleAgentSession)
            val toolCallId = RunCommandTool.currentToolCallId.get()
            val activeStreamCallback = RunCommandTool.streamCallback

            // Stream output line-by-line in a daemon thread (same pattern as RunCommandTool)
            val outputBuilder = StringBuilder()
            val readerThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            if (outputBuilder.length < MAX_OUTPUT_CHARS) {
                                outputBuilder.appendLine(line)
                            }
                            if (toolCallId != null) {
                                activeStreamCallback?.invoke(toolCallId, line + "\n")
                            }
                            line = reader.readLine()
                        }
                    }
                } catch (_: Exception) {
                    // Process killed or stream closed — expected during timeout/cancel
                }
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
                    "Test timeout",
                    TokenEstimator.estimate(truncatedOutput),
                    isError = true
                )
            }

            // Wait for reader thread to finish consuming remaining output
            readerThread.join(2000)
            val truncatedOutput = outputBuilder.toString()

            val exitCode = process.exitValue()
            if (exitCode == 0) {
                ToolResult(
                    "Tests PASSED for $testTarget.\n\n$truncatedOutput",
                    "Tests PASSED: $testTarget",
                    TokenEstimator.estimate(truncatedOutput)
                )
            } else {
                ToolResult(
                    "Tests FAILED for $testTarget (exit code $exitCode).\n\n$truncatedOutput",
                    "Tests FAILED: $testTarget",
                    TokenEstimator.estimate(truncatedOutput),
                    isError = true
                )
            }
        } catch (e: Exception) {
            ToolResult(
                "Error running tests: ${e.message}",
                "Test execution error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    /**
     * Build a Maven surefire command targeting a specific test class/method.
     */
    private fun buildMavenCommand(testTarget: String): String {
        return "mvn test -Dtest=$testTarget -Dsurefire.useFile=false -q"
    }

    /**
     * Build a Gradle test command targeting a specific test class/method.
     */
    private fun buildGradleCommand(testTarget: String, baseDir: File): String {
        val gradleWrapper = if (File(baseDir, "gradlew").exists()) "./gradlew" else "gradle"
        val gradleTarget = testTarget.replace('#', '.')
        return "$gradleWrapper test --tests '$gradleTarget' --no-daemon -q"
    }

    // ──────────────────────────────────────────────────────────────────────
    // Data types
    // ──────────────────────────────────────────────────────────────────────

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
}
