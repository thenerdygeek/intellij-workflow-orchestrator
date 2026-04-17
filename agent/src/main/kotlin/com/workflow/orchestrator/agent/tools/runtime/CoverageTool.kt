package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.TestConsoleUtils
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.util.ReflectionUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/**
 * Coverage tool — runs tests with IntelliJ's native coverage executor (or JaCoCo) and returns
 * rich per-file coverage data: line hits, branch coverage (jumps + switches), method coverage,
 * and partial-coverage indicators.
 *
 * Works with both the IntelliJ native runner (.ic files) and JaCoCo (.exec files). IntelliJ
 * converts JaCoCo data to its own ProjectData format internally, so the same reflection-based
 * extraction path handles both runners.
 *
 * Actions:
 * - run_with_coverage: Launch tests via CoverageExecutor, return test results + full coverage detail
 * - get_file_coverage: Re-read coverage for a specific file from cached data (no re-run)
 */
class CoverageTool : AgentTool {

    override val name = "coverage"

    override val description = """
Run tests with coverage analysis and retrieve rich per-file coverage data.

Actions and their parameters:
- run_with_coverage(test_class, method?, timeout?) → Run tests via IntelliJ Coverage executor, return test results + line/branch/method coverage
- get_file_coverage(file_path) → Re-read full coverage detail for a specific file from the last coverage run (no re-run)
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf("run_with_coverage", "get_file_coverage")
            ),
            "test_class" to ParameterProperty(
                type = "string",
                description = "Fully qualified test class name — for run_with_coverage"
            ),
            "method" to ParameterProperty(
                type = "string",
                description = "Specific test method name — for run_with_coverage"
            ),
            "timeout" to ParameterProperty(
                type = "integer",
                description = "Seconds before test process is killed (default: 300, max: 900) — for run_with_coverage"
            ),
            "file_path" to ParameterProperty(
                type = "string",
                description = "File path (e.g. 'src/main/java/com/example/ServiceImpl.java') or fully qualified class name (e.g. 'com.example.ServiceImpl') — for get_file_coverage"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(
        WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER
    )

    /** Cached coverage snapshot from the last run_with_coverage execution. */
    @Volatile
    internal var lastSnapshot: CoverageSnapshot? = null

    /** Diagnostic info from the last extraction attempt — included in error responses. */
    @Volatile
    internal var lastExtractionDiag: String? = null

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return when (action) {
            "run_with_coverage" -> executeRunWithCoverage(params, project)
            "get_file_coverage" -> executeGetFileCoverage(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions: run_with_coverage, get_file_coverage",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: run_with_coverage
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeRunWithCoverage(params: JsonObject, project: Project): ToolResult {
        val testClass = params["test_class"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Error: 'test_class' parameter is required for run_with_coverage",
                "Error: missing test_class",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val method = params["method"]?.jsonPrimitive?.contentOrNull
        val timeoutSeconds = (params["timeout"]?.jsonPrimitive?.intOrNull?.toLong() ?: DEFAULT_TIMEOUT)
            .coerceIn(1, MAX_TIMEOUT)

        val testTarget = if (method != null) "$testClass#$method" else testClass

        val settings = createJUnitRunSettings(project, testClass, method)
            ?: return ToolResult(
                "Error: Could not create run configuration for '$testTarget'. " +
                    "Ensure the class exists and JUnit plugin is available.",
                "Error: config creation failed",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val coverageExecutor = com.intellij.execution.ExecutorRegistry.getInstance()
            .getExecutorById("Coverage")
            ?: return ToolResult(
                "Error: Coverage executor not available. Ensure the Coverage plugin is enabled.",
                "Error: no coverage executor",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        // Phase 3 / Task 2.5: route all listener/connection/descriptor tracking through
        // a single RunInvocation, mirroring the Task 2.3+2.4 refactor in JavaRuntimeExecTool.
        // The try/finally block below disposes the invocation on every exit path (success /
        // processNotStarted / timeout / exception / coroutine cancel), which in turn:
        //   - destroys the captured ProcessHandler if it hasn't terminated,
        //   - disconnects the build-phase MessageBusConnection (registered via
        //     invocation.subscribeTopic below),
        //   - runs the `removeRunContent` onDispose callback installed after the
        //     descriptor is captured (removes the descriptor from RunContentManager,
        //     which in turn disposes the TestResultsViewer and its EventsListener),
        //   - auto-cleans the 2-arg process listener attached via attachProcessListener,
        //   - disposes the CoverageSuiteListener (folded into invocation.onDispose),
        //   - cancels any poll scope launched inside processTerminated.
        val invocation = project.service<AgentService>().newRunInvocation(
            "run-with-coverage-${System.currentTimeMillis()}"
        )
        try {
            val coverageDeferred = CompletableDeferred<CoverageSnapshot?>()
            val listenerDisposable = registerCoverageListener(project, coverageDeferred)
            listenerDisposable?.let {
                invocation.onDispose { Disposer.dispose(it) }
            }

            val result = withTimeoutOrNull<CoverageRunResult>(timeoutSeconds * 1000) {
                suspendCancellableCoroutine { continuation ->
                    // Single cleanup path: dispose the invocation. This destroys the
                    // process handler, disconnects the MessageBusConnection, removes
                    // the run content descriptor, and runs all auto-cleaning 2-arg
                    // process listeners — replacing the old manual destroy/disconnect
                    // dance.
                    continuation.invokeOnCancellation {
                        Disposer.dispose(invocation)
                    }
                    invokeLater {
                        try {
                            val env = com.intellij.execution.runners.ExecutionEnvironmentBuilder
                                .createOrNull(coverageExecutor, settings)
                                ?.build()

                            if (env == null) {
                                if (continuation.isActive) continuation.resume(
                                    CoverageRunResult("Error: could not build execution environment", "Error", testIsError = true)
                                )
                                return@invokeLater
                            }

                            val callback = object : com.intellij.execution.runners.ProgramRunner.Callback {
                                override fun processStarted(descriptor: RunContentDescriptor?) {
                                    if (descriptor == null) {
                                        if (continuation.isActive) continuation.resume(
                                            CoverageRunResult("Error: no descriptor from coverage run", "Error", testIsError = true)
                                        )
                                        return
                                    }
                                    invocation.descriptorRef.set(descriptor)
                                    val handler = descriptor.processHandler
                                    invocation.processHandlerRef.set(handler)

                                    // Register an onDispose callback that removes the descriptor
                                    // from RunContentManager — this is the release mechanism for
                                    // the TestResultsViewer (and its EventsListener) because
                                    // TestResultsViewer is Disposable with NO removeEventsListener
                                    // API. Per design, the literal `removeRunContent` call lives
                                    // here in the tool file (source-text test anchor).
                                    invocation.onDispose {
                                        val currentDesc = invocation.descriptorRef.get() ?: return@onDispose
                                        ApplicationManager.getApplication().invokeLater {
                                            com.intellij.execution.ui.RunContentManager.getInstance(project)
                                                .removeRunContent(coverageExecutor, currentDesc)
                                        }
                                    }

                                    val testConsole = TestConsoleUtils.unwrapToTestConsole(descriptor.executionConsole)
                                    if (testConsole != null) {
                                        // Phase 3 / Task 2.5: replace the raw
                                        // `resultsViewer.addEventsListener(...)` leak with
                                        // invocation.attachListener — which internally wraps the
                                        // listener in a defense-in-depth proxy that gates on the
                                        // invocation's disposed flag. Prevents stale resume() on an
                                        // already-consumed continuation after timeout/cancel. The
                                        // TestResultsViewer has no removeEventsListener API, so the
                                        // listener is released when the RunContentDescriptor is
                                        // disposed via removeRunContent (registered below).
                                        val eventsListener = object : TestResultsViewer.EventsListener {
                                            override fun onTestingFinished(sender: TestResultsViewer) {
                                                val root = sender.testsRootNode as? SMTestProxy.SMRootTestProxy
                                                if (root != null && continuation.isActive) {
                                                    continuation.resume(interpretTestRoot(root, testTarget).toCoverageRunResult())
                                                }
                                            }
                                        }
                                        invocation.attachListener(eventsListener, testConsole.resultsViewer)
                                    } else if (handler != null) {
                                        // Phase 3 / Task 2.5: the old raw `handler.addProcessListener(1-arg)`
                                        // leak is replaced with invocation.attachProcessListener, which
                                        // internally uses the 2-arg form addProcessListener(listener, disposable)
                                        // so the listener is auto-removed when the invocation disposes —
                                        // no manual removeProcessListener needed. The raw java.util.Timer
                                        // that previously fired a 2s delayed callback has been replaced
                                        // with a cancellable coroutine delay tied to invocation lifecycle
                                        // via invocation.onDispose.
                                        val terminateListener = object : ProcessAdapter() {
                                            override fun processTerminated(event: ProcessEvent) {
                                                val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                                                invocation.onDispose { pollScope.cancel() }
                                                pollScope.launch {
                                                    delay(2000)
                                                    if (continuation.isActive) {
                                                        val root = TestConsoleUtils.findTestRoot(descriptor)
                                                        if (root != null) {
                                                            continuation.resume(interpretTestRoot(root, testTarget).toCoverageRunResult())
                                                        } else {
                                                            continuation.resume(
                                                                CoverageRunResult(
                                                                    "Tests completed for $testTarget but no structured results available.",
                                                                    "Tests completed"
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        invocation.attachProcessListener(handler, terminateListener)
                                    } else {
                                        if (continuation.isActive) continuation.resume(
                                            CoverageRunResult("Error: no process handler", "Error", testIsError = true)
                                        )
                                    }
                                }
                            }

                            try {
                                ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)
                            } catch (_: NoSuchMethodError) {
                                env.callback = callback
                                ProgramRunnerUtil.executeConfiguration(env, false, true)
                            }

                            // Build watchdog: detect before-run task failure via
                            // ExecutionListener.processNotStarted. Registered through
                            // invocation.subscribeTopic so disposal (on timeout / cancel /
                            // success) disconnects it automatically. No manual disconnect
                            // needed inside the listener.
                            val buildConn = project.messageBus.connect()
                            invocation.subscribeTopic(buildConn)
                            buildConn.subscribe(com.intellij.execution.ExecutionManager.EXECUTION_TOPIC,
                                object : com.intellij.execution.ExecutionListener {
                                    override fun processNotStarted(executorId: String, e: com.intellij.execution.runners.ExecutionEnvironment) {
                                        if (e == env) {
                                            if (continuation.isActive) {
                                                continuation.resume(CoverageRunResult(
                                                    "BUILD FAILED — coverage run did not start.\n\n" +
                                                        "Compilation failed before test execution. " +
                                                        "Fix the errors and try again.",
                                                    "Build failed before coverage",
                                                    testIsError = true,
                                                ))
                                            }
                                        }
                                    }
                                    override fun processStarted(executorId: String, e: com.intellij.execution.runners.ExecutionEnvironment, handler: ProcessHandler) {
                                        // Observation-only; teardown happens through invocation.dispose().
                                    }
                                }
                            )
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resume(
                                CoverageRunResult(
                                    "Error launching coverage run: ${e.message}",
                                    "Error: ${e.javaClass.simpleName}",
                                    testIsError = true,
                                )
                            )
                        }
                    }
                }
            }

            if (result == null) {
                // Timeout path: outer `finally { Disposer.dispose(invocation) }` handles all
                // cleanup (destroy handler, disconnect bus, remove descriptor, dispose
                // coverage listener). No manual cleanup needed here.
                return ToolResult(
                    "[TIMEOUT] Coverage run timed out after ${timeoutSeconds}s for $testTarget.",
                    "Coverage timeout",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }

            val snapshot = if (listenerDisposable != null) {
                withTimeoutOrNull(COVERAGE_LISTENER_TIMEOUT_MS) { coverageDeferred.await() }
                    ?: extractCoverageSnapshot(project)
            } else {
                delay(COVERAGE_FALLBACK_DELAY_MS)
                extractCoverageSnapshot(project)
            }

            lastSnapshot = snapshot

            val coverageSummary = if (snapshot != null && snapshot.files.isNotEmpty()) {
                formatCoverageSummary(snapshot)
            } else {
                val diag = lastExtractionDiag?.let { "\n\nExtraction diagnostics:\n$it" } ?: ""
                "\nCoverage: No coverage data available. " +
                    "The coverage tab in the IDE may still show results — use get_file_coverage to re-read.$diag"
            }

            val content = "${result.testResult}\n$coverageSummary"
            return ToolResult(
                content = content,
                summary = result.testSummary + if (snapshot != null) " | ${snapshot.files.size} files covered" else "",
                tokenEstimate = content.length / 4,
                isError = result.testIsError,
            )
        } finally {
            Disposer.dispose(invocation)
        }
    }

    /**
     * Register a CoverageSuiteListener via reflection to be notified when coverage data
     * is ready. Returns a Disposable to unregister the listener, or null if registration failed.
     */
    private fun registerCoverageListener(
        project: Project,
        deferred: CompletableDeferred<CoverageSnapshot?>
    ): com.intellij.openapi.Disposable? {
        return try {
            val dataManagerClass = Class.forName("com.intellij.coverage.CoverageDataManager")
            val getInstanceMethod = dataManagerClass.getMethod("getInstance", Project::class.java)
            val dataManager = getInstanceMethod.invoke(null, project) ?: return null

            val listenerClass = Class.forName("com.intellij.coverage.CoverageSuiteListener")
            val listener = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, _ ->
                if (method.name == "coverageDataCalculated" && !deferred.isCompleted) {
                    val snapshot = extractCoverageSnapshot(project)
                    deferred.complete(snapshot)
                }
                null
            }

            val disposable = com.intellij.openapi.util.Disposer.newDisposable("CoverageTool-listener")
            val addListenerMethod = dataManagerClass.getMethod(
                "addSuiteListener",
                listenerClass,
                com.intellij.openapi.Disposable::class.java
            )
            addListenerMethod.invoke(dataManager, listener, disposable)
            disposable
        } catch (_: Exception) {
            null
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: get_file_coverage
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeGetFileCoverage(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file_path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(
                "Error: 'file_path' parameter is required for get_file_coverage",
                "Error: missing file_path",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        var snapshot = lastSnapshot
        if (snapshot == null || snapshot.files.isEmpty()) {
            snapshot = withContext(Dispatchers.IO) { extractCoverageSnapshot(project) }
            if (snapshot != null && snapshot.files.isNotEmpty()) lastSnapshot = snapshot
        }
        if (snapshot == null || snapshot.files.isEmpty()) {
            val diag = lastExtractionDiag?.let { "\n\nExtraction diagnostics:\n$it" } ?: ""
            return ToolResult(
                "No coverage data available. Run 'run_with_coverage' first to collect coverage data, " +
                    "or check that the Coverage plugin is enabled.$diag",
                "No coverage data",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val resolvedClassName = resolveToClassName(filePath, project)
        val simpleClassName = filePath.substringAfterLast('/').removeSuffix(".java").removeSuffix(".kt").removeSuffix(".groovy")

        val matchedEntry = snapshot.files.entries.find { it.key == filePath }
            ?: resolvedClassName?.let { cn -> snapshot.files.entries.find { it.key == cn } }
            ?: snapshot.files.entries.find { it.key.endsWith(".$simpleClassName") }
            ?: return ToolResult(
                "No coverage data found for '$filePath'." +
                    (resolvedClassName?.let { " (resolved to class: $it)" } ?: "") +
                    "\nAvailable classes: ${snapshot.files.keys.joinToString(", ")}",
                "File not in coverage",
                20,
                isError = true
            )

        val content = formatFileCoverageDetail(matchedEntry.key, matchedEntry.value)
        return ToolResult(
            content = content,
            summary = "${matchedEntry.key.substringAfterLast(".")} — ${String.format("%.1f", matchedEntry.value.lineCoveragePercent)}% lines, " +
                if (matchedEntry.value.totalBranches > 0) "${String.format("%.1f", matchedEntry.value.branchCoveragePercent)}% branches"
                else "no branch data",
            tokenEstimate = content.length / 4
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Coverage data extraction
    // Works for both IntelliJ native runner and JaCoCo — IntelliJ converts
    // JaCoCo exec data to its own ProjectData/ClassData/LineData format
    // internally, so the same reflection path handles both runners.
    // ══════════════════════════════════════════════════════════════════════

    private fun extractCoverageSnapshot(project: Project): CoverageSnapshot? {
        val diag = StringBuilder()
        return try {
            val dataManagerClass = Class.forName("com.intellij.coverage.CoverageDataManager")
            val dataManager = dataManagerClass.getMethod("getInstance", Project::class.java)
                .invoke(null, project)
            if (dataManager == null) {
                lastExtractionDiag = "CoverageDataManager.getInstance() returned null"
                return null
            }
            diag.appendLine("CoverageDataManager: ${dataManager.javaClass.name}")

            // Path A: getCurrentSuitesBundle() → bundle.getCoverageData()
            var projectData: Any? = null
            val bundle = ReflectionUtils.tryReflective {
                dataManager.javaClass.getMethod("getCurrentSuitesBundle").invoke(dataManager)
            }
            if (bundle != null) {
                diag.appendLine("getCurrentSuitesBundle(): ${bundle.javaClass.simpleName}")
                projectData = ReflectionUtils.tryReflective {
                    bundle.javaClass.getMethod("getCoverageData").invoke(bundle)
                }
                if (projectData != null) diag.appendLine("Path A: bundle.getCoverageData() OK")
            } else {
                diag.appendLine("getCurrentSuitesBundle() returned null")
            }

            // Path B: getSuites() → most recent suite → getCoverageData(manager)
            if (projectData == null) {
                try {
                    val suites = dataManager.javaClass.getMethod("getSuites").invoke(dataManager) as? Array<*>
                    diag.appendLine("getSuites() returned ${suites?.size ?: "null"} suites")
                    val latestSuite = suites?.lastOrNull()
                    if (latestSuite != null) {
                        val getCoverageData = latestSuite.javaClass.getMethod("getCoverageData", dataManagerClass)
                        projectData = getCoverageData.invoke(latestSuite, dataManager)
                        if (projectData != null) diag.appendLine("Path B: suite.getCoverageData(manager) OK")
                        else diag.appendLine("Path B: suite.getCoverageData(manager) returned null")
                    }
                } catch (e: Exception) {
                    diag.appendLine("Path B failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }

            if (projectData == null) {
                diag.appendLine("No ProjectData from either path")
                lastExtractionDiag = diag.toString()
                return null
            }

            @Suppress("UNCHECKED_CAST")
            val classes = projectData.javaClass.getMethod("getClasses").invoke(projectData) as? Map<String, Any>
            if (classes == null || classes.isEmpty()) {
                diag.appendLine("getClasses() returned null or empty")
                lastExtractionDiag = diag.toString()
                return null
            }
            diag.appendLine("Classes: ${classes.size}")

            val files = mutableMapOf<String, FileCoverageDetail>()
            var processed = 0; var skipped = 0
            for ((className, classData) in classes) {
                val detail = extractFileCoverageDetail(classData)
                if (detail != null) { files[className] = detail; processed++ } else skipped++
            }
            diag.appendLine("Processed: $processed, Skipped: $skipped, Files: ${files.size}")
            lastExtractionDiag = diag.toString()
            CoverageSnapshot(files)
        } catch (e: Exception) {
            diag.appendLine("EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            lastExtractionDiag = diag.toString()
            null
        }
    }

    /**
     * Extract full coverage detail for one class from its ClassData object.
     *
     * Extracts per-line hit counts, FULL/PARTIAL/NONE status, jump (if/else/ternary/&&/||)
     * branch counts, switch case counts, and per-method coverage rollups.
     * Only PARTIAL and NONE lines are stored in [FileCoverageDetail.lines] to keep output
     * concise — FULL lines are counted in totals but not stored individually.
     */
    private fun extractFileCoverageDetail(classData: Any): FileCoverageDetail? {
        val linesArray = ReflectionUtils.tryReflective {
            classData.javaClass.getMethod("getLines").invoke(classData) as? Array<*>
        } ?: return null

        var coveredLines = 0; var totalLines = 0
        var coveredBranches = 0; var totalBranches = 0
        val lineDetails = mutableListOf<LineCoverageDetail>()
        val methodStats = mutableMapOf<String, MethodStats>()

        for (lineObj in linesArray) {
            if (lineObj == null) continue
            val lineClass = lineObj.javaClass

            val lineNumber = ReflectionUtils.tryReflective { lineClass.getMethod("getLineNumber").invoke(lineObj) as? Int }
                ?: continue
            val hits = ReflectionUtils.tryReflective { lineClass.getMethod("getHits").invoke(lineObj) as? Int } ?: 0
            val methodSig = ReflectionUtils.tryReflective { lineClass.getMethod("getMethodSignature").invoke(lineObj) as? String }

            // getStatus() returns a byte (FULL=2, PARTIAL=1, NONE=0) — handle both Byte and Int boxing
            val statusInt = when (val raw = ReflectionUtils.tryReflective { lineClass.getMethod("getStatus").invoke(lineObj) }) {
                is Byte -> raw.toInt()
                is Int -> raw
                else -> if (hits > 0) 2 else 0
            }
            val status = when (statusInt) {
                2 -> LineCoverageStatus.FULL
                1 -> LineCoverageStatus.PARTIAL
                else -> LineCoverageStatus.NONE
            }

            totalLines++
            if (hits > 0) coveredLines++

            // Extract jump (if/else/ternary/&&/||) branch data
            val jumps = mutableListOf<JumpCoverageDetail>()
            val jumpsArray = ReflectionUtils.tryReflective { lineClass.getMethod("getJumps").invoke(lineObj) as? Array<*> }
            jumpsArray?.forEachIndexed { idx, jumpObj ->
                if (jumpObj == null) return@forEachIndexed
                val trueHits = ReflectionUtils.tryReflective { jumpObj.javaClass.getMethod("getTrueHits").invoke(jumpObj) as? Int } ?: 0
                val falseHits = ReflectionUtils.tryReflective { jumpObj.javaClass.getMethod("getFalseHits").invoke(jumpObj) as? Int } ?: 0
                jumps.add(JumpCoverageDetail(idx, trueHits, falseHits))
                totalBranches += 2
                if (trueHits > 0) coveredBranches++
                if (falseHits > 0) coveredBranches++
            }

            // Extract switch statement branch data
            val switches = mutableListOf<SwitchCoverageDetail>()
            val switchesArray = ReflectionUtils.tryReflective { lineClass.getMethod("getSwitches").invoke(lineObj) as? Array<*> }
            switchesArray?.forEachIndexed { idx, switchObj ->
                if (switchObj == null) return@forEachIndexed
                val keys = ReflectionUtils.tryReflective { switchObj.javaClass.getMethod("getKeys").invoke(switchObj) as? IntArray } ?: IntArray(0)
                // SwitchData.getHits() is the no-arg form returning int[] (parallel to getKeys()).
                // Not to be confused with a hypothetical parameterised variant — the IntelliJ
                // coverage API defines it as: public int[] getHits()
                val switchHits = ReflectionUtils.tryReflective { switchObj.javaClass.getMethod("getHits").invoke(switchObj) as? IntArray } ?: IntArray(0)
                val defaultHits = ReflectionUtils.tryReflective { switchObj.javaClass.getMethod("getDefaultHits").invoke(switchObj) as? Int } ?: 0

                val cases = mutableListOf<Pair<Int?, Int>>()
                keys.forEachIndexed { i, key ->
                    val h = if (i < switchHits.size) switchHits[i] else 0
                    cases.add(key to h)
                    totalBranches++
                    if (h > 0) coveredBranches++
                }
                // Default case
                cases.add(null to defaultHits)
                totalBranches++
                if (defaultHits > 0) coveredBranches++

                switches.add(SwitchCoverageDetail(idx, cases))
            }

            // Accumulate per-method stats
            if (methodSig != null) {
                val ms = methodStats.getOrPut(methodSig) { MethodStats() }
                ms.totalLines++
                if (hits > 0) ms.coveredLines++
                ms.totalBranches += jumps.size * 2 + switches.sumOf { it.cases.size }
                ms.coveredBranches += jumps.count { it.trueHits > 0 } + jumps.count { it.falseHits > 0 } +
                    switches.sumOf { sw -> sw.cases.count { (_, h) -> h > 0 } }
            }

            // Only store PARTIAL and NONE lines — FULL lines counted in totals only
            if (status != LineCoverageStatus.FULL) {
                lineDetails.add(LineCoverageDetail(lineNumber, hits, status, methodSig, jumps, switches))
            }
        }

        if (totalLines == 0) return null

        val methods = methodStats.map { (sig, ms) ->
            MethodCoverageDetail(sig, ms.coveredLines, ms.totalLines, ms.coveredBranches, ms.totalBranches)
        }.sortedWith(compareBy({ it.coveredLines > 0 }, { it.coveredLines.toDouble() / it.totalLines.coerceAtLeast(1) }))

        return FileCoverageDetail(
            coveredLines = coveredLines,
            totalLines = totalLines,
            coveredBranches = coveredBranches,
            totalBranches = totalBranches,
            lineCoveragePercent = coveredLines.toDouble() / totalLines * 100,
            branchCoveragePercent = if (totalBranches == 0) 0.0 else coveredBranches.toDouble() / totalBranches * 100,
            methods = methods,
            lines = lineDetails.sortedBy { it.lineNumber }
        )
    }

    /** Temporary accumulator for per-method stats during extraction. */
    private data class MethodStats(
        var coveredLines: Int = 0,
        var totalLines: Int = 0,
        var coveredBranches: Int = 0,
        var totalBranches: Int = 0
    )

    // ══════════════════════════════════════════════════════════════════════
    // Formatting
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Multi-file summary for run_with_coverage output.
     * Shows line % and branch % per file, sorted by line coverage ascending.
     */
    internal fun formatCoverageSummary(snapshot: CoverageSnapshot): String {
        val sb = StringBuilder()
        sb.appendLine("\nCoverage (${snapshot.files.size} files):")

        val sorted = snapshot.files.entries.sortedBy { it.value.lineCoveragePercent }
        for ((name, detail) in sorted) {
            val shortName = name.substringAfterLast('.')
            val linePct = String.format("%.1f", detail.lineCoveragePercent)
            val branchPart = if (detail.totalBranches > 0)
                ", ${String.format("%.1f", detail.branchCoveragePercent)}% branches"
            else ""
            sb.appendLine("  $shortName — ${linePct}% lines$branchPart")
        }

        val totalCovered = snapshot.files.values.sumOf { it.coveredLines }
        val totalLines = snapshot.files.values.sumOf { it.totalLines }
        val overallLinePct = if (totalLines == 0) 0.0 else totalCovered.toDouble() / totalLines * 100

        val totalCovBranches = snapshot.files.values.sumOf { it.coveredBranches }
        val totalBranches = snapshot.files.values.sumOf { it.totalBranches }

        sb.appendLine()
        if (totalBranches > 0) {
            val overallBranchPct = totalCovBranches.toDouble() / totalBranches * 100
            sb.appendLine("Overall: ${String.format("%.1f", overallLinePct)}% line coverage, ${String.format("%.1f", overallBranchPct)}% branch coverage")
        } else {
            sb.appendLine("Overall: ${String.format("%.1f", overallLinePct)}% line coverage")
        }

        return sb.toString().trimEnd()
    }

    /**
     * Full per-file coverage detail — used by both run_with_coverage (per file) and get_file_coverage.
     *
     * Format:
     *   === ClassName ===
     *   Lines   : 74.3%  (89/120 covered)
     *   Branches: 61.5%  (32/52 covered)
     *
     *   Methods (N uncovered / M total):
     *     ✗ methodName  — 0/12 lines, 0/4 branches
     *     ~ methodName  — 4/8 lines, 1/4 branches
     *
     *   Uncovered / Partial Lines:
     *     line 42  [NONE]    hits=0   method=methodName
     *       if[0] → true=0, false=3
     *     lines 43–45 [NONE] hits=0
     *     line 67  [PARTIAL] hits=5   method=methodName
     *       if[0] → true=5, false=0
     *       switch[0]: case(1)=3, case(2)=0, default=0
     */
    internal fun formatFileCoverageDetail(className: String, detail: FileCoverageDetail): String {
        val sb = StringBuilder()
        sb.appendLine("=== ${className.substringAfterLast('.')} ===")

        val linePct = String.format("%.1f", detail.lineCoveragePercent)
        sb.appendLine("Lines   : $linePct%  (${detail.coveredLines}/${detail.totalLines} covered)")

        if (detail.totalBranches > 0) {
            val branchPct = String.format("%.1f", detail.branchCoveragePercent)
            sb.appendLine("Branches: $branchPct%  (${detail.coveredBranches}/${detail.totalBranches} covered)")
        } else {
            sb.appendLine("Branches: N/A  (no branch data)")
        }

        // Methods section — only show uncovered (✗) and partial (~) methods
        val uncovered = detail.methods.filter { it.coveredLines == 0 }
        val partial = detail.methods.filter { it.coveredLines > 0 && it.coveredLines < it.totalLines }
        if (uncovered.isNotEmpty() || partial.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Methods (${uncovered.size} uncovered / ${detail.methods.size} total):")
            for (m in uncovered) {
                val branchStr = if (m.totalBranches > 0) ", ${m.coveredBranches}/${m.totalBranches} branches" else ""
                sb.appendLine("  ✗ ${m.signature.substringBefore("(")}  — ${m.coveredLines}/${m.totalLines} lines$branchStr")
            }
            for (m in partial) {
                val branchStr = if (m.totalBranches > 0) ", ${m.coveredBranches}/${m.totalBranches} branches" else ""
                sb.appendLine("  ~ ${m.signature.substringBefore("(")}  — ${m.coveredLines}/${m.totalLines} lines$branchStr")
            }
        } else if (detail.methods.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Methods: all covered (${detail.methods.size} total)")
        }

        // Uncovered / partial lines — with range collapsing for plain NONE lines
        if (detail.lines.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Uncovered / Partial Lines:")
            val rendered = buildUncoveredLineOutput(detail.lines)
            val toShow = rendered.take(MAX_UNCOVERED_ENTRIES)
            toShow.forEach { sb.appendLine(it) }
            if (rendered.size > MAX_UNCOVERED_ENTRIES) {
                sb.appendLine("  ... and ${rendered.size - MAX_UNCOVERED_ENTRIES} more uncovered/partial lines")
            }
        } else if (detail.coveredLines == detail.totalLines) {
            sb.appendLine()
            sb.appendLine("All lines covered.")
        }

        return sb.toString().trimEnd()
    }

    /**
     * Build the rendered list of uncovered/partial line strings.
     * Consecutive NONE lines with no branches and the same method are range-collapsed.
     */
    private fun buildUncoveredLineOutput(lines: List<LineCoverageDetail>): List<String> {
        val out = mutableListOf<String>()
        var rangeStart: LineCoverageDetail? = null
        var rangeEnd: LineCoverageDetail? = null

        fun flushRange() {
            val s = rangeStart ?: return
            val e = rangeEnd ?: return
            val methodStr = s.methodSignature?.let { "  method=${it.substringBefore("(")}" } ?: ""
            out.add(if (s.lineNumber == e.lineNumber)
                "  line ${s.lineNumber} [NONE]  hits=0$methodStr"
            else
                "  lines ${s.lineNumber}–${e.lineNumber} [NONE]  hits=0$methodStr"
            )
            rangeStart = null; rangeEnd = null
        }

        for (line in lines) {
            val collapsible = line.status == LineCoverageStatus.NONE && line.jumps.isEmpty() && line.switches.isEmpty()
            val consecutive = rangeEnd != null && line.lineNumber == rangeEnd!!.lineNumber + 1
            val sameMethod = rangeStart?.methodSignature == line.methodSignature

            if (collapsible && consecutive && sameMethod) {
                rangeEnd = line
            } else {
                flushRange()
                if (collapsible) {
                    rangeStart = line; rangeEnd = line
                } else {
                    val methodStr = line.methodSignature?.let { "  method=${it.substringBefore("(")}" } ?: ""
                    out.add("  line ${line.lineNumber} [${line.status}]  hits=${line.hits}$methodStr")
                    for (jump in line.jumps) {
                        out.add("    if[${jump.index}] → true=${jump.trueHits}, false=${jump.falseHits}")
                    }
                    for (sw in line.switches) {
                        val cases = sw.cases.joinToString(", ") { (key, h) ->
                            if (key == null) "default=$h" else "case($key)=$h"
                        }
                        out.add("    switch[${sw.index}]: $cases")
                    }
                }
            }
        }
        flushRange()
        return out
    }

    // ══════════════════════════════════════════════════════════════════════
    // Test results formatting
    //
    // CoverageRunResult is the data-class carrier we pass across the
    // withTimeoutOrNull boundary. Populate it from the canonical
    // [interpretTestRoot] ToolResult — same classifier as RuntimeExecTool and
    // JavaRuntimeExecTool, so a coverage-backed run can't report PASSED for
    // an empty suite or a runner crash.
    // ══════════════════════════════════════════════════════════════════════

    private fun ToolResult.toCoverageRunResult(): CoverageRunResult =
        CoverageRunResult(this.content, this.summary, this.isError)

    // ══════════════════════════════════════════════════════════════════════
    // Run configuration creation
    // ══════════════════════════════════════════════════════════════════════

    private fun createJUnitRunSettings(
        project: Project, className: String, method: String?
    ): com.intellij.execution.RunnerAndConfigurationSettings? {
        return try {
            val runManager = RunManager.getInstance(project)
            val testFramework = detectTestFramework(project, className)
            val configTypeId = if (testFramework == "TestNG") "TestNG" else "JUnit"
            val testConfigType = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.find { type ->
                type.id == configTypeId || type.displayName == configTypeId
            } ?: return null
            val factory = testConfigType.configurationFactories.firstOrNull() ?: return null

            val configName = "[Coverage] ${className.substringAfterLast('.')}${if (method != null) ".$method" else ""}"
            val settings = runManager.createConfiguration(configName, factory)
            val config = settings.configuration
            val isTestNG = testFramework == "TestNG"

            try {
                val dataMethodName = if (isTestNG) "getPersistantData" else "getPersistentData"
                val data = config.javaClass.methods.find { it.name == dataMethodName }?.invoke(config)
                    ?: return null
                data.javaClass.getField("TEST_OBJECT").set(data,
                    if (method != null) if (isTestNG) "METHOD" else "method"
                    else if (isTestNG) "CLASS" else "class")
                data.javaClass.getField("MAIN_CLASS_NAME").set(data, className)
                ReflectionUtils.tryReflective {
                    data.javaClass.getField("PACKAGE_NAME").set(data, className.substringBeforeLast('.', ""))
                }
                if (method != null) data.javaClass.getField("METHOD_NAME").set(data, method)
            } catch (_: Exception) { return null }

            val testModule = findModuleForClass(project, className) ?: return null
            try {
                config.javaClass.getMethod("setModule", com.intellij.openapi.module.Module::class.java)
                    .invoke(config, testModule)
            } catch (_: Exception) {
                try {
                    val configModule = config.javaClass.getMethod("getConfigurationModule").invoke(config)
                    configModule.javaClass.getMethod("setModule", com.intellij.openapi.module.Module::class.java)
                        .invoke(configModule, testModule)
                } catch (_: Exception) {}
            }

            settings.isTemporary = true
            settings
        } catch (_: Exception) { null }
    }

    private fun detectTestFramework(project: Project, className: String): String {
        return try {
            ReadAction.compute<String, Exception> {
                val psiClass = JavaPsiFacade.getInstance(project)
                    .findClass(className, GlobalSearchScope.projectScope(project))
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
            ReadAction.compute<com.intellij.openapi.module.Module?, Exception> {
                val psiClass = JavaPsiFacade.getInstance(project)
                    .findClass(className, GlobalSearchScope.projectScope(project))
                    ?: return@compute null
                ModuleUtilCore.findModuleForPsiElement(psiClass)
            }
        } catch (_: Exception) { null }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Utilities
    // ══════════════════════════════════════════════════════════════════════

    private fun resolveToClassName(filePath: String, project: Project): String? {
        return try {
            ReadAction.compute<String?, Exception> {
                val basePath = project.basePath ?: return@compute null
                val absolutePath = if (filePath.startsWith("/") || filePath.contains(":\\")) filePath
                    else "$basePath/$filePath"
                val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .findFileByIoFile(java.io.File(absolutePath)) ?: return@compute null
                val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vf) ?: return@compute null
                if (psiFile is com.intellij.psi.PsiJavaFile) {
                    val pkg = psiFile.packageName
                    val cls = psiFile.classes.firstOrNull()?.name ?: return@compute null
                    return@compute if (pkg.isNotBlank()) "$pkg.$cls" else cls
                }
                try {
                    val ktFileClass = Class.forName("org.jetbrains.kotlin.psi.KtFile")
                    if (ktFileClass.isInstance(psiFile)) {
                        val pkg = ktFileClass.getMethod("getPackageFqName").invoke(psiFile)?.toString() ?: ""
                        val cls = vf.nameWithoutExtension
                        return@compute if (pkg.isNotBlank()) "$pkg.$cls" else cls
                    }
                } catch (_: Exception) {}
                null
            }
        } catch (_: Exception) { null }
    }

    companion object {
        private const val DEFAULT_TIMEOUT = 300L
        private const val MAX_TIMEOUT = 900L
        private const val COVERAGE_LISTENER_TIMEOUT_MS = 30_000L
        private const val COVERAGE_FALLBACK_DELAY_MS = 5_000L
        private const val MAX_UNCOVERED_ENTRIES = 50

        /**
         * Collapse a sorted list of line numbers into contiguous ranges.
         * E.g., [3, 4, 5, 10, 12, 13] → [3..5, 10..10, 12..13]
         */
        internal fun collapseToRanges(lines: List<Int>): List<IntRange> {
            if (lines.isEmpty()) return emptyList()
            val sorted = lines.sorted()
            val ranges = mutableListOf<IntRange>()
            var start = sorted[0]; var end = sorted[0]
            for (i in 1 until sorted.size) {
                if (sorted[i] == end + 1) end = sorted[i]
                else { ranges.add(start..end); start = sorted[i]; end = sorted[i] }
            }
            ranges.add(start..end)
            return ranges
        }

        /** Test helper — exposes formatCoverageSummary for unit tests. */
        fun formatCoverageSnapshotPublic(snapshot: CoverageSnapshot): String =
            CoverageTool().formatCoverageSummary(snapshot)

        /** Test helper — exposes formatFileCoverageDetail for unit tests. */
        fun formatFileCoverageDetailPublic(className: String, detail: FileCoverageDetail): String =
            CoverageTool().formatFileCoverageDetail(className, detail)
    }
}

// ══════════════════════════════════════════════════════════════════════════
// Data classes
// ══════════════════════════════════════════════════════════════════════════

/** Line coverage status matching IntelliJ's FULL=2 / PARTIAL=1 / NONE=0 constants. */
enum class LineCoverageStatus { FULL, PARTIAL, NONE }

/** Hit counts for one conditional jump (if/else, ternary, &&, ||) on a line. */
data class JumpCoverageDetail(
    val index: Int,       // jump index within the line (0-based)
    val trueHits: Int,    // times the true branch was taken
    val falseHits: Int    // times the false branch was taken
)

/**
 * Hit counts for one switch statement on a line.
 * [cases] pairs: key (null = default) → hit count.
 */
data class SwitchCoverageDetail(
    val index: Int,
    val cases: List<Pair<Int?, Int>>
)

/** Full coverage detail for one executable line. */
data class LineCoverageDetail(
    val lineNumber: Int,
    val hits: Int,                          // execution count (0 = not covered)
    val status: LineCoverageStatus,         // FULL / PARTIAL / NONE
    val methodSignature: String?,           // JVM method descriptor this line belongs to
    val jumps: List<JumpCoverageDetail>,    // empty if no conditional branches on this line
    val switches: List<SwitchCoverageDetail>
)

/** Per-method coverage rollup derived from per-line method signatures. */
data class MethodCoverageDetail(
    val signature: String,      // JVM method descriptor (e.g. "processOrder(Lcom/example/Order;)V")
    val coveredLines: Int,
    val totalLines: Int,
    val coveredBranches: Int,
    val totalBranches: Int
)

/**
 * Full coverage detail for one class — returned by both run_with_coverage and get_file_coverage.
 *
 * [lines] contains only PARTIAL and NONE lines to keep output concise.
 * FULL lines are counted in [coveredLines] / [totalLines] but not stored individually.
 */
data class FileCoverageDetail(
    val coveredLines: Int,
    val totalLines: Int,
    val coveredBranches: Int,
    val totalBranches: Int,
    val lineCoveragePercent: Double,
    val branchCoveragePercent: Double,   // 0.0 when totalBranches == 0
    val methods: List<MethodCoverageDetail>,
    val lines: List<LineCoverageDetail>  // only PARTIAL and NONE
)

/** Top-level snapshot from a coverage run — keyed by fully qualified class name. */
data class CoverageSnapshot(val files: Map<String, FileCoverageDetail>)

internal data class CoverageRunResult(
    val testResult: String,
    val testSummary: String,
    val testIsError: Boolean = false,
)
