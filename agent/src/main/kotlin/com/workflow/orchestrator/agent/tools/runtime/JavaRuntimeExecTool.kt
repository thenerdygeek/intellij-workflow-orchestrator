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
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.workflow.orchestrator.agent.AgentService
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
 * Matches a real Gradle `> Task :...:test` progress line, ignoring `compileTestJava`,
 * `testClasses`, and arbitrary log lines that happen to contain the substring `:test `.
 *
 * - `^> Task :` — anchored (MULTILINE) so we only match the canonical Gradle progress
 *   prefix at line start, not `:test ` appearing in random log noise.
 * - `(\S*:)*` — allows any number of nested project path segments
 *   (e.g. `> Task :services:auth:test`); the trailing `:` before `test` ensures the
 *   task name is actually `test` and not `testClasses`.
 * - `\btest\b` — whole-word match so `testIntegration` / `testClasses` don't match.
 */
internal val GRADLE_TEST_TASK_REGEX: Regex = Regex("""^> Task :(\S*:)*test\b""", RegexOption.MULTILINE)

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

        // Pre-flight validation (Phase 2 / Tasks 2.7–2.9): when we have a resolved module,
        // run BuildSystemValidator to catch the "module exists in IntelliJ but unrunnable
        // in the build tool" failure family BEFORE dispatching. If module is null, skip the
        // validator and let the native/shell paths surface their own "module could not be
        // resolved" error — the validator requires a module to check against.
        var authoritativeBuildPath: String? = null
        var detectedTestCount: Int = 0
        var validatorWarning: String? = null
        if (module != null) {
            when (val validation = BuildSystemValidator(project).validateForTestRun(className, module)) {
                is BuildSystemValidator.ValidationResult.Blocked -> return ToolResult(
                    content = "${validation.reason}\n\n${validation.suggestion}",
                    summary = validation.reason.substringBefore('\n'),
                    tokenEstimate = 50,
                    isError = true
                )
                is BuildSystemValidator.ValidationResult.Ok -> {
                    authoritativeBuildPath = validation.authoritativeBuildPath
                    detectedTestCount = validation.detectedTestCount
                    // Populated by Task 2.5 filesystem fallback — surface it to the LLM so
                    // non-standard-layout dispatch failures aren't silently blamed on the
                    // build tool. Prepended to the breadcrumb below with the same
                    // `[WARNING]` prefix we use for the native-runner-fell-back-to-shell case.
                    validatorWarning = validation.warning
                }
            }
        }

        // Build the success breadcrumb (Task 2.9). Prepended to any happy-path result
        // returned by the native runner or shell fallback. Compose the parenthesized parts
        // as a list so the "Build path" segment is dropped entirely when no authoritative
        // path is available — otherwise we'd emit a dangling "(Build path, …)" label that
        // reads broken to humans and is a confusing LLM anchor.
        val breadcrumb = if (module != null) {
            val parts = buildList {
                authoritativeBuildPath?.let { path ->
                    val label = if (path.startsWith(":")) "Gradle path" else "Maven dir"
                    add("$label: $path")
                }
                val simpleClass = className.substringAfterLast('.')
                add("$detectedTestCount test methods detected in $simpleClass")
            }
            val line = "Running tests in module: ${module.name} (${parts.joinToString(", ")})"
            if (validatorWarning != null) "[WARNING] $validatorWarning\n$line" else line
        } else {
            null
        }

        fun prependBreadcrumb(result: ToolResult): ToolResult {
            if (breadcrumb == null || result.isError) return result
            val newContent = breadcrumb + "\n\n" + result.content
            return result.copy(
                content = newContent,
                tokenEstimate = result.tokenEstimate + TokenEstimator.estimate(breadcrumb)
            )
        }

        if (useNativeRunner) {
            val reasonOut = StringBuilder()
            try {
                val result = executeWithNativeRunner(project, className, method, testTarget, timeoutSeconds, reasonOut)
                if (result != null) return prependBreadcrumb(result)
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
                // Catch branch: the validator may have produced an authoritative path we
                // should still prefer, but if anything about the native launch exploded we
                // pass `null` for the authoritative path so executeWithShell falls back to
                // filesystem derivation — the validator's Ok was only authoritative for a
                // happy-path launch. Defensive choice; can be relaxed later.
                val shellResult = executeWithShell(project, testTarget, timeoutSeconds, module, null)
                val warning = "[WARNING] Native test runner threw ${e.javaClass.simpleName}: ${e.message}, used shell fallback.\n\n"
                return prependBreadcrumb(shellResult.copy(content = warning + shellResult.content))
            }
        }

        return prependBreadcrumb(executeWithShell(project, testTarget, timeoutSeconds, module, authoritativeBuildPath))
    }

    private suspend fun executeWithNativeRunner(
        project: Project, className: String, method: String?,
        testTarget: String, timeoutSeconds: Long,
        reasonOut: StringBuilder
    ): ToolResult? {
        val launchSpec = createJUnitRunSettings(project, className, method, reasonOut) ?: return null
        val settings = launchSpec.settings
        val testModule = launchSpec.module

        // Phase 3 / Task 2.3: route all listener/connection/descriptor tracking through
        // a single RunInvocation. The try/finally block below disposes the invocation on
        // every exit path (success / processNotStarted / timeout / exception / coroutine
        // cancel), which in turn:
        //   - destroys the captured ProcessHandler if it hasn't terminated,
        //   - disconnects both the build-phase and run-phase MessageBusConnection
        //     (registered via invocation.subscribeTopic below),
        //   - runs the `removeRunContent` onDispose callback installed after the
        //     descriptor is captured (removes the descriptor from RunContentManager,
        //     which in turn disposes the TestResultsViewer and its EventsListener),
        //   - auto-cleans any 2-arg process listeners attached inside handleDescriptorReady.
        //
        // The old raw `build-watchdog-timeout` Thread (5 min sleep that manually
        // disconnected the run connection) is gone — the outer `withTimeoutOrNull`
        // fires → this finally runs → everything is released.
        val invocation = project.service<AgentService>().newRunInvocation("run-tests-${System.currentTimeMillis()}")
        // Task 2.4 follow-up: handleDescriptorReady now consumes `invocation` directly
        // (its descriptorRef / processHandlerRef / attachListener / attachProcessListener),
        // so the local-var aliases that bridged Task 2.3 are gone.

        try {
            val result = withTimeoutOrNull(timeoutSeconds * 1000) {
                suspendCancellableCoroutine { continuation ->
                    // Single cleanup path: dispose the invocation. This destroys the
                    // process handler, disconnects both MessageBusConnections, removes
                    // the run content descriptor, and runs all auto-cleaning 2-arg
                    // process listeners — replacing the old manual disconnect/destroy
                    // dance.
                    continuation.invokeOnCancellation {
                        Disposer.dispose(invocation)
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
                            invocation.subscribeTopic(buildConnection)

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
                                                        handleDescriptorReady(descriptor, continuation, testTarget, invocation, project)
                                                        // Descriptor is populated into invocation.descriptorRef by
                                                        // handleDescriptorReady directly.
                                                        // Register an onDispose callback that removes it from
                                                        // RunContentManager — this is the release mechanism for the
                                                        // TestResultsViewer (and its EventsListener) because
                                                        // TestResultsViewer is Disposable with NO removeEventsListener
                                                        // API. Per design, the literal `removeRunContent` call
                                                        // lives here in the tool file (source-text test anchor).
                                                        invocation.onDispose {
                                                            val currentDesc = invocation.descriptorRef.get() ?: return@onDispose
                                                            val runExecutor = DefaultRunExecutor.getRunExecutorInstance()
                                                            ApplicationManager.getApplication().invokeLater {
                                                                com.intellij.execution.ui.RunContentManager.getInstance(project)
                                                                    .removeRunContent(runExecutor, currentDesc)
                                                            }
                                                        }
                                                    }
                                                }

                                                try {
                                                    ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)
                                                } catch (_: NoSuchMethodError) {
                                                    env.callback = callback
                                                    ProgramRunnerUtil.executeConfiguration(env, false, true)
                                                }

                                                // Defence-in-depth: ExecutionListener.processNotStarted()
                                                // fires when the execution framework aborts the run for non-build
                                                // reasons (no ProgramRunner registered, executor disabled, JDK
                                                // resolution failure, etc.). Build failures are caught above via
                                                // ProjectTaskManager.build — this handles everything else.
                                                //
                                                // Registered through invocation.subscribeTopic so disposal of the
                                                // RunInvocation (on timeout / cancel / success) disconnects it
                                                // automatically. No raw watchdog Thread needed.
                                                val runConnection = project.messageBus.connect()
                                                invocation.subscribeTopic(runConnection)

                                                runConnection.subscribe(
                                                    com.intellij.execution.ExecutionManager.EXECUTION_TOPIC,
                                                    object : com.intellij.execution.ExecutionListener {
                                                        override fun processNotStarted(
                                                            executorId: String,
                                                            e: com.intellij.execution.runners.ExecutionEnvironment
                                                        ) {
                                                            if (e == env) {
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
                                                            // Observation-only; teardown happens through invocation.dispose().
                                                        }
                                                    }
                                                )
                                            } catch (e: Exception) {
                                                reasonOut.append("run launch threw ${e.javaClass.simpleName}: ${e.message}")
                                                if (continuation.isActive) continuation.resume(null)
                                            }
                                        }
                                    }
                                    .onError { _ ->
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

            if (result == null && invocation.processHandlerRef.get() != null) {
                invocation.processHandlerRef.get()?.destroyProcess()
                val descriptor = invocation.descriptorRef.get()
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
        } finally {
            Disposer.dispose(invocation)
        }
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
    internal fun buildCompileFailureResult(
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

    internal fun handleDescriptorReady(
        descriptor: RunContentDescriptor,
        continuation: CancellableContinuation<ToolResult?>,
        testTarget: String,
        invocation: RunInvocation,
        project: Project? = null,
    ) {
        invocation.descriptorRef.set(descriptor)
        val handler = descriptor.processHandler
        invocation.processHandlerRef.set(handler)

        val toolCallId = RunCommandTool.currentToolCallId.get()
        val activeStreamCallback = if (project != null) resolveStreamCallback(project) else RunCommandTool.streamCallback

        if (handler != null && toolCallId != null) {
            // Phase 3 / Task 2.4: route through invocation.attachProcessListener so the
            // listener is auto-removed when invocation disposes (uses 2-arg
            // addProcessListener(listener, disposable) form internally — no manual
            // removeProcessListener needed on terminal notification).
            val streamingListener = object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                    val text = event.text ?: return
                    if (text.isNotBlank()) {
                        activeStreamCallback?.invoke(toolCallId, text)
                    }
                }
            }
            invocation.attachProcessListener(handler, streamingListener)
        }

        val testConsole = TestConsoleUtils.unwrapToTestConsole(descriptor.executionConsole)
        if (testConsole != null) {
            val resultsViewer = testConsole.resultsViewer
            // Phase 3 / Task 2.4: route through invocation.attachListener — wraps the
            // listener in a defense-in-depth proxy that gates on the invocation's
            // disposed flag. If the framework re-fires onTestingFinished after we've
            // already disposed (e.g. timeout fired and continuation was cancelled),
            // the proxy silently drops the call instead of resuming an already-consumed
            // continuation.
            val eventsListener = object : TestResultsViewer.EventsListener {
                override fun onTestingFinished(sender: TestResultsViewer) {
                    val root = sender.testsRootNode as? SMTestProxy.SMRootTestProxy
                    if (root != null && continuation.isActive) {
                        continuation.resume(interpretTestRoot(root, descriptor.displayName ?: testTarget))
                    }
                }
            }
            invocation.attachListener(eventsListener, resultsViewer)
        } else {
            // Fallback: no test console available — wait for process exit, then retry
            // until the test tree is populated. No TestResultsViewer.EventsListener is
            // available here, so we poll with short intervals instead of a blind 2s Timer.
            //
            // Phase 3 / Task 2.4: replaces the prior raw `test-tree-finalize` Thread.
            // We launch a coroutine on a per-invocation scope (Dispatchers.IO) and
            // tie its cancellation to invocation.onDispose so:
            //   - timeout/cancel disposes the invocation → scope.cancel() → poll loop
            //     stops promptly without leaking a daemon thread,
            //   - delay() is interruptible (unlike Thread.sleep) so cancellation is
            //     immediate rather than waiting up to TEST_TREE_RETRY_INTERVAL_MS.
            if (handler != null) {
                val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                invocation.onDispose { pollScope.cancel() }

                val doExtract = {
                    pollScope.launch {
                        for (attempt in 1..TEST_TREE_RETRY_ATTEMPTS) {
                            if (!continuation.isActive) return@launch
                            if (TestConsoleUtils.findTestRoot(descriptor)?.children?.isNotEmpty() == true) break
                            delay(TEST_TREE_RETRY_INTERVAL_MS)
                        }
                        if (continuation.isActive) {
                            continuation.resume(extractNativeResults(descriptor, testTarget))
                        }
                    }
                }

                if (handler.isProcessTerminated) {
                    doExtract()
                } else {
                    val terminationListener = object : ProcessAdapter() {
                        override fun processTerminated(event: ProcessEvent) {
                            doExtract()
                        }
                    }
                    invocation.attachProcessListener(handler, terminationListener)
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
        module: com.intellij.openapi.module.Module? = null,
        authoritativeBuildPath: String? = null
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
        //
        // Fallback-only: this filesystem-derived path is used when the validator could not
        // supply an authoritative Gradle path (plugin unavailable, non-Gradle project, or
        // the validator was not run because `module` was null). When the authoritative path
        // IS available, it supersedes this value (see `effectiveGradlePath` below).
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
        //
        // Fallback-only for the same reason as `gradleSubprojectPath` above — the validator's
        // authoritative directory (when available) supersedes this via `effectiveMavenDir`.
        val mavenModuleDir: File? = module?.let { m ->
            try {
                val contentRoot = com.intellij.openapi.roots.ModuleRootManager.getInstance(m)
                    .contentRoots.firstOrNull()?.path
                if (contentRoot != null && File(contentRoot, "pom.xml").exists()) File(contentRoot)
                else null
            } catch (_: Exception) { null }
        }

        // Prefer the validator-supplied authoritative build path when available. Gradle
        // subproject paths start with `:` (e.g. `:services:auth`); Maven module directories
        // are absolute filesystem paths. Disambiguation is unambiguous because no legal
        // filesystem path begins with `:`.
        val effectiveGradlePath: String? =
            if (authoritativeBuildPath != null && authoritativeBuildPath.startsWith(":")) authoritativeBuildPath
            else gradleSubprojectPath

        val effectiveMavenDir: File? =
            if (authoritativeBuildPath != null && !authoritativeBuildPath.startsWith(":")) File(authoritativeBuildPath)
            else mavenModuleDir

        val command = when {
            hasMaven -> {
                val className = testTarget.substringBefore('#')
                val methodPart = if ('#' in testTarget) "#${testTarget.substringAfter('#')}" else ""
                if (effectiveMavenDir != null && effectiveMavenDir != baseDir) {
                    // Run only the submodule to avoid rebuilding unrelated modules
                    "mvn test -Dtest=${className}${methodPart} -Dsurefire.useFile=false -q --also-make"
                } else {
                    "mvn test -Dtest=${className}${methodPart} -Dsurefire.useFile=false -q"
                }
            }
            hasGradle -> {
                val gradleWrapper = if (File(baseDir, "gradlew").exists()) "./gradlew" else "gradle"
                val gradleTarget = testTarget.replace('#', '.')
                if (effectiveGradlePath != null) {
                    // Multi-module: run only the subproject that owns the test class
                    "$gradleWrapper ${effectiveGradlePath}:test --tests '$gradleTarget' --no-daemon -q"
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
        val workDir = if (hasMaven && effectiveMavenDir != null && effectiveMavenDir != baseDir) effectiveMavenDir else baseDir

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
            // "Tests ran" markers from Maven (Surefire summary line) and Gradle
            // (task header). Used to distinguish "tests ran but reports missing"
            // from "nothing happened — not a test class".
            //
            // Gradle marker is matched per-line via [GRADLE_TEST_TASK_REGEX]: a
            // MULTILINE-anchored `^> Task :(...:)*test\b` pattern that correctly
            // handles nested project paths (e.g. `> Task :services:auth:test`)
            // and rejects compile phases like `compileTestJava`, `testClasses`,
            // or arbitrary log lines that happen to contain `":test "`.
            val hasTestRanMarker = rawOutput.contains("Tests run:") ||
                GRADLE_TEST_TASK_REGEX.containsMatchIn(rawOutput)
            val truncatedOutput = truncateOutput(rawOutput, RUN_TESTS_MAX_OUTPUT_CHARS)

            val exitCode = process.exitValue()

            // Parse Surefire/Gradle XML reports. This is the authoritative signal —
            // exit code 0 with 0 tests means the target class isn't actually a test
            // class, and should surface as NO_TESTS_FOUND (not "Tests PASSED").
            val tool = if (hasMaven) "maven" else "gradle"
            val reportEntries = parseJUnitXmlReports(workDir, tool)

            // Branch ordering rationale: when we have parsed test entries, prefer
            // them over the `isBuildFailure` banner. With `-fae` / `--fail-at-end`,
            // or a compile abort in main sources that happens AFTER tests have
            // already run, `BUILD FAILURE` can appear alongside genuine surefire
            // reports. The parsed XML entries are more authoritative than a later
            // build-phase failure — the build-failure banner is still preserved
            // for the LLM via the appended `--- stdout ---` tail.
            when {
                reportEntries != null && reportEntries.isNotEmpty() -> {
                    val base = formatStructuredResults(reportEntries, testTarget)
                    // Diagnostic note: Surefire's default <useFile>true</useFile>
                    // writes per-suite XML that may drop individual testcases. If
                    // the "Tests run: N" summary count exceeds what we parsed,
                    // warn the LLM so it doesn't trust our count blindly.
                    val summaryCount = extractMavenTestsRunCount(rawOutput)
                    val diagnostic = if (summaryCount != null && summaryCount > reportEntries.size) {
                        "[WARN] Parsed ${reportEntries.size} test cases but Maven summary reports $summaryCount — " +
                            "reports may be missing individual testcases (Surefire <useFile>true> default).\n\n"
                    } else ""
                    val combined = diagnostic + base.content + "\n\n--- stdout ---\n" + truncatedOutput
                    base.copy(
                        content = combined,
                        tokenEstimate = TokenEstimator.estimate(combined)
                    )
                }
                reportEntries != null && reportEntries.isEmpty() -> {
                    // XML reports exist but all had tests="0" → class had no @Test methods
                    noTestsFoundResult(testTarget, command, exitCode, truncatedOutput)
                }
                isBuildFailure -> {
                    // Preserve the existing BUILD FAILED message — no reports to parse
                    // because the build phase failed before tests could even start.
                    ToolResult(
                        "BUILD FAILED — test execution did not start (exit code $exitCode).\n\n" +
                            "Fix compilation errors and try again. Use diagnostics tool to check for errors.\n\n" +
                            truncatedOutput,
                        "Build failed before tests",
                        TokenEstimator.estimate(truncatedOutput),
                        isError = true
                    )
                }
                hasTestRanMarker -> {
                    // stdout claims tests ran but XML is absent — rare edge case
                    // (Surefire <useFile>false> plus no report dir, or a build-abort
                    // after tests started). Surface as an explicit warning so the
                    // agent knows the result is ambiguous.
                    ToolResult(
                        "Tests ran but no XML reports were found under ${workDir.path}. " +
                            "Possible causes: Surefire -Dmaven.test.skip, custom reports dir, " +
                            "or build aborted during write.\n\n" +
                            "Exit code: $exitCode\n\n--- stdout ---\n$truncatedOutput",
                        "Tests ran but XML missing",
                        TokenEstimator.estimate(truncatedOutput),
                        isError = true
                    )
                }
                else -> {
                    // Neither XML reports nor "Tests run:" marker — nothing actually
                    // executed. This is the user-incident-#2 case: target wasn't a
                    // real test class.
                    noTestsFoundResult(testTarget, command, exitCode, truncatedOutput)
                }
            }
        } catch (e: Exception) {
            ToolResult("Error running tests: ${e.message}", "Test execution error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    /**
     * Build the standard NO_TESTS_FOUND result used by [executeWithShell] when
     * the Surefire/Gradle run succeeded but matched zero test methods. This is
     * the critical anti-"Tests PASSED" signal for incident #2 — the user asked
     * to run tests for a class that had no `@Test` annotations, Surefire exited 0
     * with 0 tests, and the agent previously reported the run as a success. The
     * `isError = true` flag guarantees the LLM treats this as a problem to fix.
     */
    private fun noTestsFoundResult(
        testTarget: String,
        command: String,
        exitCode: Int,
        truncatedOutput: String
    ): ToolResult {
        val content = "NO_TESTS_FOUND — Surefire/Gradle ran successfully but matched no test methods.\n" +
            "Verify the class has @Test methods and is in a test source root.\n\n" +
            "Command: $command\n" +
            "Exit code: $exitCode\n\n" +
            "--- stdout (last N lines) ---\n$truncatedOutput"
        return ToolResult(
            content = content,
            summary = "NO_TESTS_FOUND: $testTarget",
            tokenEstimate = TokenEstimator.estimate(content),
            isError = true
        )
    }

    /**
     * Extract the "Tests run: N" count from Maven Surefire's summary line.
     * Returns null if no summary line is present. Used to diagnose cases where
     * the XML reports undercount (Surefire <useFile>true</useFile> default).
     *
     * Surefire emits `Tests run: N, ...` per suite AND a final aggregate total
     * after all suites complete. We want the aggregate, so we match the LAST
     * occurrence rather than the first.
     */
    internal fun extractMavenTestsRunCount(rawOutput: String): Int? {
        val regex = Regex("""Tests run:\s*(\d+)""")
        return regex.findAll(rawOutput).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
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
