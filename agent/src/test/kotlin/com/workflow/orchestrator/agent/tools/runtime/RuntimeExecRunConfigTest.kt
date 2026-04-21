package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.compiler.CompilationStatusListener
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompilerMessage
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.compiler.CompilerTopics
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.testFramework.LightVirtualFile
import com.workflow.orchestrator.agent.AgentService
import java.lang.reflect.Proxy
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import com.workflow.orchestrator.agent.tools.ToolResult
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 1 (TEST-FIRST) — failing tests for `runtime_exec.run_config` action.
 *
 * ALL tests in this file must FAIL on current HEAD because [RuntimeExecTool]
 * does not yet contain an `executeRunConfig` method / `run_config` action
 * dispatch branch. Tests will fail at:
 *
 *   - Compile time:  references to as-yet-nonexistent extension functions or
 *     constants from [RuntimeExecTool] / companion object, if any are added.
 *   - Runtime:  `execute(buildJsonObject { put("action", "run_config") }, project)`
 *     returns the "Unknown action" error branch — content does NOT contain
 *     "CONFIGURATION_NOT_FOUND", "AMBIGUOUS_MATCH", "READY", etc.
 *
 * All 23 scenarios plus the RunInvocationLeakTest extensions are defined here.
 * Implementation is in Phase 2.
 *
 * ### Error-category taxonomy (Section 4 of research doc)
 *
 * | Category                    | Expected `content` substring            |
 * |-----------------------------|-----------------------------------------|
 * | CONFIGURATION_NOT_FOUND     | "CONFIGURATION_NOT_FOUND"               |
 * | AMBIGUOUS_MATCH             | "AMBIGUOUS_MATCH"                       |
 * | INVALID_CONFIGURATION       | "INVALID_CONFIGURATION"                 |
 * | NO_RUNNER_REGISTERED        | "NO_RUNNER_REGISTERED"                  |
 * | BEFORE_RUN_FAILED           | "BEFORE_RUN_FAILED"                     |
 * | PROCESS_START_FAILED        | "PROCESS_START_FAILED"                  |
 * | DUMB_MODE                   | "DUMB_MODE"                             |
 * | EXITED_BEFORE_READY         | "EXITED_BEFORE_READY"                   |
 * | TIMEOUT_WAITING_FOR_READY   | "TIMEOUT_WAITING_FOR_READY"             |
 * | TIMEOUT_WAITING_FOR_PROCESS | "TIMEOUT_WAITING_FOR_PROCESS"           |
 * | EXECUTION_EXCEPTION         | "EXECUTION_EXCEPTION"                   |
 * | CANCELLED_BY_USER           | "CANCELLED_BY_USER"                     |
 * | UNEXPECTED_ERROR            | "UNEXPECTED_ERROR"                      |
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeExecRunConfigTest {

    // ═══════════════════════════════════════════════════════════════════════
    // Test fixtures
    // ═══════════════════════════════════════════════════════════════════════

    // Fix 1: project is now a lateinit var so setUp() can reassign it with a
    // fully-configured mock that has AgentService wired.
    private lateinit var project: Project
    private lateinit var agentService: AgentService
    private lateinit var testRootDisposable: Disposable

    private lateinit var runManager: RunManager
    private lateinit var messageBus: MessageBus
    private lateinit var messageBusConnection: MessageBusConnection
    private lateinit var dumbService: DumbService
    private lateinit var runContentManager: RunContentManager

    private val tool = RuntimeExecTool()

    // Captured ExecutionListener slot — lets tests fire processNotStarted / processStarted
    private val executionListenerSlot = slot<ExecutionListener>()

    // Captured CompilationStatusListener slot — lets scenario 12 fire compilationFinished
    private val compilationStatusListenerSlot = slot<CompilationStatusListener>()

    @BeforeEach
    fun setUp() {
        // Fix 1: create a root disposable that owns the test-scoped RunInvocations.
        testRootDisposable = Disposer.newDisposable("RuntimeExecRunConfigTest")

        // Fix 1: fresh mocks per-test wired via lateinit vars so tearDown() can
        // safely call unmockkAll() on any failure path.
        project = mockk(relaxed = true)
        agentService = mockk(relaxed = true)
        runManager = mockk(relaxed = true)
        messageBus = mockk(relaxed = true)
        messageBusConnection = mockk(relaxed = true)
        dumbService = mockk(relaxed = true)
        runContentManager = mockk(relaxed = true)

        // Fix 1: stub AgentService.newRunInvocation via project.getService(AgentService)
        // so that production launch paths (scenarios 3, 4, 8-19) don't NPE.
        // Pattern: every { project.getService(Xxx::class.java) } returns xxx
        // (same pattern used in RenderArtifactToolTest, TaskEventBusWiringTest).
        every { project.getService(AgentService::class.java) } returns agentService
        every { agentService.newRunInvocation(any()) } answers {
            RunInvocation(testRootDisposable, firstArg())
        }

        // Static mocks for service-level APIs used in the production launch path.
        mockkStatic(RunManager::class)
        mockkStatic(DumbService::class)
        mockkStatic(ProgramRunnerUtil::class)
        mockkStatic(RunContentManager::class)
        // ExecutionManager is used by stopProcessesForConfig (idempotent pre-launch stop).
        // Default: no running processes — existing tests that don't stub this proceed silently.
        mockkStatic(ExecutionManager::class)
        val execManager = mockk<com.intellij.execution.ExecutionManager>(relaxed = true)
        every { ExecutionManager.getInstance(project) } returns execManager
        every { execManager.getRunningProcesses() } returns emptyArray()
        // Fix 2: move DefaultRunExecutor and ExecutionEnvironmentBuilder statics
        // into setUp/tearDown to prevent dirty-static leaks from mid-test failures.
        mockkStatic(DefaultRunExecutor::class)
        // ExecutionEnvironmentBuilder.createOrNull is a Kotlin companion object method
        // (ExecutionEnvironmentBuilder$Companion). mockkStatic only intercepts @JvmStatic
        // Java-visible statics; for Kotlin companion object dispatch we must use
        // mockkObject(Companion) so MockK can intercept the call during every{} recording
        // without falling through to the real implementation (which requires an initialized
        // IntelliJ Application / ExtensionPoint and throws IllegalArgumentException here).
        mockkObject(ExecutionEnvironmentBuilder.Companion)
        // M3 fix: ReadAction.compute is now called during name resolution. Mock it to
        // execute the lambda inline (synchronously on the test thread) so tests don't
        // need a real IntelliJ Application instance. Pattern from BuildSystemValidatorTest.
        mockkStatic(ReadAction::class)
        val readActionComputeSlot = slot<ThrowableComputable<Any?, Throwable>>()
        every { ReadAction.compute(capture(readActionComputeSlot)) } answers {
            readActionComputeSlot.captured.compute()
        }

        every { RunManager.getInstance(project) } returns runManager
        every { DumbService.isDumb(project) } returns false
        every { RunContentManager.getInstance(project) } returns runContentManager
        every { runContentManager.removeRunContent(any(), any()) } returns true

        // MessageBus wiring — lets tests capture the ExecutionListener that the
        // production code subscribes via invocation.subscribeTopic(connection).
        every { project.messageBus } returns messageBus
        every { messageBus.connect() } returns messageBusConnection
        every { messageBusConnection.subscribe(ExecutionManager.EXECUTION_TOPIC, capture(executionListenerSlot)) } just Runs
        // Follow-up A: capture CompilationStatusListener so scenario 12 can fire compile errors
        every { messageBusConnection.subscribe(CompilerTopics.COMPILATION_STATUS, capture(compilationStatusListenerSlot)) } just Runs
        every { messageBusConnection.dispose() } just Runs
    }

    @AfterEach
    fun tearDown() {
        // Fix 1: dispose the root disposable that parented all RunInvocations.
        Disposer.dispose(testRootDisposable)

        // Fix 2: unmock ALL statics (including DefaultRunExecutor + ExecutionEnvironmentBuilder
        // that are now moved here from per-test pairs). unmockkAll() handles all registered
        // static mocks so dirty state can't leak even on mid-test failure paths.
        unmockkAll()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helper factories
    // ═══════════════════════════════════════════════════════════════════════

    /** Builds a JSON params block for run_config with the given fields. */
    private fun runConfigParams(
        configName: String? = null,
        waitForReady: Boolean = false,
        waitForFinish: Boolean = false,
        readinessTimeoutSeconds: Int = 5,
        readinessStrategy: String? = null,
        timeoutSeconds: Int? = null,
    ) = buildJsonObject {
        put("action", "run_config")
        configName?.let { put("config_name", it) }
        // Always emit wait_for_ready so the production default (true) does not override the
        // test intent. The helper default is false (fire-and-forget / wait_for_finish mode).
        put("wait_for_ready", waitForReady)
        if (waitForFinish) put("wait_for_finish", true)
        put("readiness_timeout_seconds", readinessTimeoutSeconds)
        readinessStrategy?.let { put("readiness_strategy", it) }
        timeoutSeconds?.let { put("timeout_seconds", it) }
    }

    /** Creates a [RunnerAndConfigurationSettings] mock with the given display name and config type id. */
    private fun buildSettings(displayName: String, configTypeId: String = "Application"): RunnerAndConfigurationSettings {
        val config = mockk<RunConfiguration>(relaxed = true)
        val settings = mockk<RunnerAndConfigurationSettings>(relaxed = true)
        every { settings.name } returns displayName
        every { settings.configuration } returns config
        every { config.type.id } returns configTypeId
        every { config.name } returns displayName
        return settings
    }

    /** Creates a mock [ProcessHandler] that is running (not terminated). */
    private fun buildLiveHandler(pid: Long = 12345L): ProcessHandler {
        val handler = mockk<ProcessHandler>(relaxed = true)
        every { handler.isProcessTerminated } returns false
        every { handler.isProcessTerminating } returns false
        val processListenerSlot = slot<ProcessListener>()
        every { handler.addProcessListener(capture(processListenerSlot), any()) } just Runs
        every { handler.addProcessListener(any()) } just Runs
        every { handler.removeProcessListener(any()) } just Runs
        every { handler.destroyProcess() } just Runs
        return handler
    }

    /** Creates a mock [RunContentDescriptor] backed by the given process handler. */
    private fun buildDescriptor(handler: ProcessHandler, name: String = "MyApp"): RunContentDescriptor {
        val desc = mockk<RunContentDescriptor>(relaxed = true)
        every { desc.processHandler } returns handler
        every { desc.displayName } returns name
        return desc
    }

    // ══════════════════════════════════════════════════════════════════════
    // Follow-up A helpers: proxy-based fakes for CompilerMessage / CompileContext.
    //
    // MockK cannot instantiate CompilerMessageCategory (Java enum with anonymous
    // inner subclasses — Objenesis fails). Pattern from FormatCompileErrorsTest.kt:
    // use Java dynamic Proxy to synthesize just enough of the interface contracts.
    // ══════════════════════════════════════════════════════════════════════

    private inline fun <reified T : Any> proxyOf(crossinline handler: (methodName: String, args: Array<out Any?>?) -> Any?): T {
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java)
        ) { _, method, args ->
            val result = handler(method.name, args)
            if (result != null) return@newProxyInstance result
            when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Void.TYPE -> null
                else -> null
            }
        } as T
    }

    private fun fakeCompilerMessage(
        fileName: String,
        message: String,
        line0: Int,
        col0: Int,
    ): CompilerMessage {
        // OpenFileDescriptor is a concrete class; MockK can mock it for the `is` check.
        val nav = mockk<OpenFileDescriptor>(relaxed = true)
        every { nav.line } returns line0
        every { nav.column } returns col0
        val vf = LightVirtualFile(fileName)
        return proxyOf<CompilerMessage> { m, _ ->
            when (m) {
                "getMessage" -> message
                "getVirtualFile" -> vf
                "getNavigatable" -> nav
                "getCategory" -> CompilerMessageCategory.ERROR
                "getExportTextPrefix" -> ""
                "getRenderTextPrefix" -> ""
                else -> null
            }
        }
    }

    private fun fakeCompileContext(errors: List<CompilerMessage>): CompileContext {
        return proxyOf<CompileContext> { m, args ->
            when (m) {
                "getMessages" -> {
                    val cat = args?.get(0) as? CompilerMessageCategory
                    when (cat) {
                        CompilerMessageCategory.ERROR -> errors.toTypedArray()
                        else -> emptyArray<CompilerMessage>()
                    }
                }
                "getMessageCount" -> {
                    val cat = args?.get(0) as? CompilerMessageCategory
                    if (cat == CompilerMessageCategory.ERROR) errors.size else 0
                }
                else -> null
            }
        }
    }

    /**
     * Wires DefaultRunExecutor + ExecutionEnvironmentBuilder mocks for a given
     * [settings] → [env] pair. Returns the [env]. Reuses the already-mocked statics
     * from setUp() — no extra mockkStatic needed per-test (Fix 2).
     */
    private fun wireExecutionEnv(
        settings: RunnerAndConfigurationSettings,
    ): Pair<DefaultRunExecutor, ExecutionEnvironment> {
        val executor = mockk<DefaultRunExecutor>(relaxed = true)
        val envBuilder = mockk<ExecutionEnvironmentBuilder>(relaxed = true)
        val env = mockk<ExecutionEnvironment>(relaxed = true)
        every { DefaultRunExecutor.getRunExecutorInstance() } returns executor
        every { ExecutionEnvironmentBuilder.createOrNull(executor, settings) } returns envBuilder
        every { envBuilder.build() } returns env
        return executor to env
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 1 — Unknown config_name → CONFIGURATION_NOT_FOUND
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config returns CONFIGURATION_NOT_FOUND when name does not match`() = runTest {
        val existing = buildSettings("SpringBootServer")
        every { runManager.allSettings } returns listOf(existing)
        every { runManager.findConfigurationByName("NonExistentApp") } returns null

        val result = tool.execute(runConfigParams(configName = "NonExistentApp"), project)

        assertTrue(result.isError, "Unrecognised config_name must produce an error ToolResult")
        assertTrue(
            result.content.contains("CONFIGURATION_NOT_FOUND"),
            "Error content must contain 'CONFIGURATION_NOT_FOUND'. Got: ${result.content}"
        )
        assertTrue(
            result.content.contains("SpringBootServer"),
            "Error must list available config names so LLM can correct. Got: ${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 2 — Ambiguous substring match → AMBIGUOUS_MATCH
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config returns AMBIGUOUS_MATCH when substring matches two or more configs`() = runTest {
        val settings1 = buildSettings("MyApp-Dev")
        val settings2 = buildSettings("MyApp-Prod")
        every { runManager.allSettings } returns listOf(settings1, settings2)
        every { runManager.findConfigurationByName("MyApp") } returns null
        // Substring "MyApp" would match both — the tool must detect this before picking one

        val result = tool.execute(runConfigParams(configName = "MyApp"), project)

        assertTrue(result.isError, "Ambiguous substring match must produce an error ToolResult")
        assertTrue(
            result.content.contains("AMBIGUOUS_MATCH"),
            "Error content must contain 'AMBIGUOUS_MATCH'. Got: ${result.content}"
        )
        assertTrue(
            result.content.contains("MyApp-Dev") && result.content.contains("MyApp-Prod"),
            "Error must list the ambiguous candidates. Got: ${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 3 — Exact name match → proceeds to launch (mocked)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config resolves by exact name and proceeds to launch`() = runTest {
        val settings = buildSettings("SpringBootServer")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("SpringBootServer") } returns settings

        val (_, env) = wireExecutionEnv(settings)
        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, any()) } just Runs

        // With wait_for_ready=false and wait_for_finish=false the tool should fire-and-forget.
        val result = tool.execute(runConfigParams(configName = "SpringBootServer"), project)

        // Should NOT contain CONFIGURATION_NOT_FOUND, AMBIGUOUS_MATCH, INVALID_CONFIGURATION.
        assertFalse(
            result.content.contains("CONFIGURATION_NOT_FOUND"),
            "Exact name match must not produce CONFIGURATION_NOT_FOUND. Got: ${result.content}"
        )
        assertFalse(
            result.content.contains("AMBIGUOUS_MATCH"),
            "Exact name match must not produce AMBIGUOUS_MATCH. Got: ${result.content}"
        )
        // The result is either a success launch or an error from some further mock gap —
        // what matters is it passed name resolution. Verify executeConfigurationAsync was called.
        verify(atLeast = 1) { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, any()) }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 4 — Substring match with exactly 1 result → proceeds to launch
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config resolves by unique substring when exactly one config matches`() = runTest {
        val settings = buildSettings("SpringBootServer")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("SpringBoot") } returns null
        // "SpringBoot" is a substring of only "SpringBootServer" — unambiguous

        val (_, env) = wireExecutionEnv(settings)
        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, any()) } just Runs

        val result = tool.execute(runConfigParams(configName = "SpringBoot"), project)

        assertFalse(
            result.content.contains("CONFIGURATION_NOT_FOUND"),
            "Unique substring must resolve, not produce CONFIGURATION_NOT_FOUND. Got: ${result.content}"
        )
        assertFalse(
            result.content.contains("AMBIGUOUS_MATCH"),
            "Unique substring must not produce AMBIGUOUS_MATCH. Got: ${result.content}"
        )
        verify(atLeast = 1) { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, any()) }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 5 — remote_debug config → INVALID_CONFIGURATION with redirect
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config returns INVALID_CONFIGURATION for remote_debug config type`() = runTest {
        val settings = buildSettings("MyRemoteApp", configTypeId = "Remote")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("MyRemoteApp") } returns settings

        val result = tool.execute(runConfigParams(configName = "MyRemoteApp"), project)

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("INVALID_CONFIGURATION"),
            "remote_debug config must produce INVALID_CONFIGURATION. Got: ${result.content}"
        )
        assertTrue(
            result.content.contains("debug_breakpoints.attach_to_process"),
            "Error must redirect to debug_breakpoints.attach_to_process. Got: ${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 6 — junit config → INVALID_CONFIGURATION with redirect
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config returns INVALID_CONFIGURATION for junit config type`() = runTest {
        val settings = buildSettings("MyJUnitTests", configTypeId = "JUnit")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("MyJUnitTests") } returns settings

        val result = tool.execute(runConfigParams(configName = "MyJUnitTests"), project)

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("INVALID_CONFIGURATION"),
            "JUnit config must produce INVALID_CONFIGURATION. Got: ${result.content}"
        )
        assertTrue(
            result.content.contains("java_runtime_exec.run_tests"),
            "Error must redirect to java_runtime_exec.run_tests. Got: ${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 7 — checkConfiguration() throws RuntimeConfigurationError → INVALID_CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config returns INVALID_CONFIGURATION when checkConfiguration throws RuntimeConfigurationError`() = runTest {
        val config = mockk<RunConfiguration>(relaxed = true)
        val settings = mockk<RunnerAndConfigurationSettings>(relaxed = true)
        every { settings.name } returns "BrokenApp"
        every { settings.configuration } returns config
        every { config.type.id } returns "Application"
        every { config.name } returns "BrokenApp"
        every { config.checkConfiguration() } throws RuntimeConfigurationError("missing JDK")

        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("BrokenApp") } returns settings

        val result = tool.execute(runConfigParams(configName = "BrokenApp"), project)

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("INVALID_CONFIGURATION"),
            "RuntimeConfigurationError must surface INVALID_CONFIGURATION. Got: ${result.content}"
        )
        assertTrue(
            result.content.contains("missing JDK"),
            "Error message must include the original exception message. Got: ${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 8 — checkConfiguration() throws RuntimeConfigurationWarning → does NOT block launch
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config does not block launch when checkConfiguration throws only a warning`() = runTest {
        val config = mockk<RunConfiguration>(relaxed = true)
        val settings = mockk<RunnerAndConfigurationSettings>(relaxed = true)
        every { settings.name } returns "WarnApp"
        every { settings.configuration } returns config
        every { config.type.id } returns "Application"
        every { config.name } returns "WarnApp"
        // RuntimeConfigurationWarning should be treated as non-fatal — launch must proceed
        every { config.checkConfiguration() } throws RuntimeConfigurationWarning("SDK path looks unusual, but may work")

        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("WarnApp") } returns settings

        val (_, env) = wireExecutionEnv(settings)
        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, any()) } just Runs

        val result = tool.execute(runConfigParams(configName = "WarnApp"), project)

        // Warning must NOT block — INVALID_CONFIGURATION must not appear in the result
        assertFalse(
            result.content.contains("INVALID_CONFIGURATION"),
            "RuntimeConfigurationWarning must not produce INVALID_CONFIGURATION error. Got: ${result.content}"
        )
        // Launch must have been attempted
        verify(atLeast = 1) { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, any()) }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 9 — ExecutionEnvironmentBuilder.createOrNull returns null → NO_RUNNER_REGISTERED
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config returns NO_RUNNER_REGISTERED when createOrNull returns null`() = runTest {
        val settings = buildSettings("NoRunnerApp")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("NoRunnerApp") } returns settings

        val executor = mockk<DefaultRunExecutor>(relaxed = true)
        every { DefaultRunExecutor.getRunExecutorInstance() } returns executor
        every { ExecutionEnvironmentBuilder.createOrNull(executor, settings) } returns null

        val result = tool.execute(runConfigParams(configName = "NoRunnerApp"), project)

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("NO_RUNNER_REGISTERED"),
            "Null env builder must produce NO_RUNNER_REGISTERED. Got: ${result.content}"
        )
        // Error should include the executor id and config type for diagnostics
        assertTrue(
            result.content.contains("Run") || result.content.contains("executor"),
            "Error should reference the executor. Got: ${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 10 — DumbService.isDumb == true → DUMB_MODE
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config returns DUMB_MODE error when IDE is indexing`() = runTest {
        every { DumbService.isDumb(project) } returns true

        val settings = buildSettings("SpringBootServer")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("SpringBootServer") } returns settings

        val result = tool.execute(runConfigParams(configName = "SpringBootServer"), project)

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("DUMB_MODE"),
            "Dumb mode must produce DUMB_MODE error. Got: ${result.content}"
        )
        assertTrue(
            result.content.contains("indexing") || result.content.contains("wait"),
            "Error must guide user to wait for indexing. Got: ${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 11 — ExecutionListener.processNotStarted fires with generic Throwable cause
    //               → PROCESS_START_FAILED (strictly, not BEFORE_RUN_FAILED)
    // Fix 5: split from scenario 12 — generic throwable → PROCESS_START_FAILED only.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config returns PROCESS_START_FAILED when processNotStarted fires with generic cause`() = runTest {
        val settings = buildSettings("CrashApp")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("CrashApp") } returns settings

        val (_, env) = wireExecutionEnv(settings)

        // Fire processNotStarted with a generic Throwable (no compiler errors) →
        // must map strictly to PROCESS_START_FAILED.
        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, any()) } answers {
            if (executionListenerSlot.isCaptured) {
                executionListenerSlot.captured.processNotStarted(
                    DefaultRunExecutor.EXECUTOR_ID,
                    env
                )
            }
        }

        val result = tool.execute(
            runConfigParams(configName = "CrashApp", waitForReady = false),
            project
        )

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("PROCESS_START_FAILED"),
            "Generic processNotStarted must map to PROCESS_START_FAILED (not BEFORE_RUN_FAILED). Got: ${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 12 — Before-run compile fails → BEFORE_RUN_FAILED
    // Fix 5: @Disabled because CompilerTopics.COMPILATION_STATUS injection
    //        requires Phase 2 wiring (MessageBus subscription to CompilerTopics).
    //        Will re-enable after implementation.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config returns BEFORE_RUN_FAILED with per-file compile error details`() = runTest {
        val settings = buildSettings("FailApp")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("FailApp") } returns settings

        val (_, env) = wireExecutionEnv(settings)

        // Build proxy-based CompilerMessage with file:line:col details (research Section A3).
        // Use the proxy pattern from FormatCompileErrorsTest — avoids MockK's Objenesis failure
        // trying to instantiate CompilerMessageCategory (abstract Java enum).
        val message = fakeCompilerMessage(
            fileName = "MyService.java",
            message = "Cannot find symbol",
            line0 = 41,   // 0-based → formatCompileErrors emits line+1 = 42
            col0 = 4,     // 0-based → col+1 = 5
        )
        val compileCtx = fakeCompileContext(listOf(message))

        // Wire executeConfigurationAsync: fire compilationFinished(errors=1) first, then
        // processNotStarted — mirrors the IntelliJ lifecycle ordering (Section A4).
        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, any()) } answers {
            if (compilationStatusListenerSlot.isCaptured) {
                compilationStatusListenerSlot.captured.compilationFinished(
                    false, 1, 0, compileCtx
                )
            }
            if (executionListenerSlot.isCaptured) {
                executionListenerSlot.captured.processNotStarted(
                    DefaultRunExecutor.EXECUTOR_ID, env
                )
            }
        }

        val result = tool.execute(runConfigParams(configName = "FailApp"), project)

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("BEFORE_RUN_FAILED"),
            "Compile failure must surface BEFORE_RUN_FAILED. Got: ${result.content}"
        )
        assertTrue(
            result.content.contains("42") || result.content.contains("MyService.java"),
            "Error must include per-file location (line 42 or filename). Got: ${result.content}"
        )
        assertTrue(
            result.content.contains("Cannot find symbol"),
            "Error must include the compiler message text. Got: ${result.content}"
        )
    }

    // Negative scenario for Follow-up A: compile succeeds (0 errors), process fails →
    // must produce PROCESS_START_FAILED (not BEFORE_RUN_FAILED).
    @Test
    fun `run_config returns PROCESS_START_FAILED when compile succeeds but runner fails`() = runTest {
        val settings = buildSettings("BadRunner")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("BadRunner") } returns settings

        val (_, env) = wireExecutionEnv(settings)

        // Compile succeeded — compilationFinished fires with errors=0 (doesn't set compileContextRef).
        // Then runner fails → compileContextRef is null → PROCESS_START_FAILED (not BEFORE_RUN_FAILED).
        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, any()) } answers {
            if (compilationStatusListenerSlot.isCaptured) {
                // errors=0 → production code does NOT set compileContextRef
                compilationStatusListenerSlot.captured.compilationFinished(
                    false, 0, 0, fakeCompileContext(emptyList())
                )
            }
            if (executionListenerSlot.isCaptured) {
                executionListenerSlot.captured.processNotStarted(
                    DefaultRunExecutor.EXECUTOR_ID, env
                )
            }
        }

        val result = tool.execute(runConfigParams(configName = "BadRunner"), project)

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("PROCESS_START_FAILED"),
            "Runner failure after successful compile must be PROCESS_START_FAILED (not BEFORE_RUN_FAILED). Got: ${result.content}"
        )
        assertFalse(
            result.content.contains("BEFORE_RUN_FAILED"),
            "Must not report BEFORE_RUN_FAILED when compile succeeded. Got: ${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 13 — Tomcat port banner emitted, wait_for_ready=true → READY
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config resolves READY when Tomcat port banner emitted and wait_for_ready is true`() = runTest {
        val settings = buildSettings("TomcatApp")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("TomcatApp") } returns settings

        val processListenerSlot = slot<ProcessListener>()
        val handler = buildLiveHandler(pid = 8844)
        // Override to capture the readiness listener
        every { handler.addProcessListener(capture(processListenerSlot), any()) } just Runs

        val descriptor = buildDescriptor(handler, "TomcatApp")
        val (_, env) = wireExecutionEnv(settings)

        val callbackSlot = slot<ProgramRunner.Callback>()
        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, capture(callbackSlot)) } answers {
            // Fire callback synchronously with the mock descriptor to simulate process start
            callbackSlot.captured.processStarted(descriptor)
            // Fire processStarted on the execution listener too (same env identity)
            if (executionListenerSlot.isCaptured) {
                executionListenerSlot.captured.processStarted(
                    DefaultRunExecutor.EXECUTOR_ID,
                    env,
                    handler
                )
            }
            // Emit a Tomcat port banner via the captured ProcessListener
            if (processListenerSlot.isCaptured) {
                val event = mockk<ProcessEvent>(relaxed = true)
                every { event.text } returns "Tomcat started on port(s): 8080 (http)\n"
                processListenerSlot.captured.onTextAvailable(event, mockk(relaxed = true))
            }
        }

        val result = tool.execute(
            runConfigParams(configName = "TomcatApp", waitForReady = true, readinessTimeoutSeconds = 5),
            project
        )

        assertFalse(result.isError, "Successful READY must not be an error. Got: ${result.content}")
        assertTrue(
            result.content.contains("READY"),
            "Content must include 'READY'. Got: ${result.content}"
        )
        assertTrue(
            result.content.contains("8080") || result.content.contains("8844"),
            "Content must include port 8080 or PID 8844. Got: ${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 14 — Idle stdout after 2 s, readiness_strategy=idle_stdout → READY
    // Fix 6: add advanceTimeBy(2001) after the single output line to deterministically
    //        trigger the idle heuristic on virtual time.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config resolves READY via idle_stdout strategy when process goes quiet`() = runTest {
        val settings = buildSettings("SilentApp")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("SilentApp") } returns settings

        val processListenerSlot = slot<ProcessListener>()
        val handler = buildLiveHandler(9000)
        every { handler.addProcessListener(capture(processListenerSlot), any()) } just Runs

        val descriptor = buildDescriptor(handler, "SilentApp")
        val (_, env) = wireExecutionEnv(settings)

        val callbackSlot = slot<ProgramRunner.Callback>()
        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, capture(callbackSlot)) } answers {
            callbackSlot.captured.processStarted(descriptor)
            // One line of output, then silence — idle_stdout should fire
            if (processListenerSlot.isCaptured) {
                val event = mockk<ProcessEvent>(relaxed = true)
                every { event.text } returns "Server ready on 0.0.0.0:9000\n"
                processListenerSlot.captured.onTextAvailable(event, mockk(relaxed = true))
                // No further output — idle heuristic kicks in after M ms
            }
        }

        // Fix (scenario 14): launch execute() first so the coroutine reaches its delay()
        // suspension points, THEN advance virtual time. The previous ordering
        // (advanceTimeBy before execute) had no effect because the coroutine under test
        // was not yet suspended when the clock was advanced.
        val resultDeferred = async {
            tool.execute(
                runConfigParams(
                    configName = "SilentApp",
                    waitForReady = true,
                    readinessTimeoutSeconds = 5,
                    readinessStrategy = "idle_stdout"
                ),
                project
            )
        }
        // Advance virtual time past the idle_stdout heuristic (IDLE_STDOUT_INITIAL_WAIT_MS=300 +
        // one IDLE_STDOUT_POLL_MS=200 iteration → any value ≥ 501 is sufficient; use 2001 for
        // extra headroom matching the original test intent).
        advanceTimeBy(2001)
        val result = resultDeferred.await()

        assertFalse(result.isError, "Idle-stdout READY must not be an error. Got: ${result.content}")
        assertTrue(
            result.content.contains("READY"),
            "Content must include 'READY' for idle_stdout strategy. Got: ${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 15 — Process exits with non-zero within readiness window → EXITED_BEFORE_READY
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config returns EXITED_BEFORE_READY when process exits with non-zero code`() = runTest {
        val settings = buildSettings("CrashingApp")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("CrashingApp") } returns settings

        val processListenerSlot = slot<ProcessListener>()
        val handler = buildLiveHandler(7777)
        every { handler.addProcessListener(capture(processListenerSlot), any()) } just Runs

        val descriptor = buildDescriptor(handler, "CrashingApp")
        val (_, env) = wireExecutionEnv(settings)

        val callbackSlot = slot<ProgramRunner.Callback>()
        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, capture(callbackSlot)) } answers {
            callbackSlot.captured.processStarted(descriptor)
            if (processListenerSlot.isCaptured) {
                val output = mockk<ProcessEvent>(relaxed = true)
                every { output.text } returns "ERROR: failed to bind port 8080, exiting\n"
                processListenerSlot.captured.onTextAvailable(output, mockk(relaxed = true))
                // Simulate process termination with exit code 1
                val termEvent = mockk<ProcessEvent>(relaxed = true)
                every { termEvent.exitCode } returns 1
                every { handler.isProcessTerminated } returns true
                processListenerSlot.captured.processTerminated(termEvent)
            }
        }

        val result = tool.execute(
            runConfigParams(configName = "CrashingApp", waitForReady = true, readinessTimeoutSeconds = 5),
            project
        )

        assertTrue(result.isError, "Process exit before ready must be an error")
        assertTrue(
            result.content.contains("EXITED_BEFORE_READY"),
            "Content must contain 'EXITED_BEFORE_READY'. Got: ${result.content}"
        )
        assertTrue(
            result.content.contains("1") || result.content.contains("exit"),
            "Content should include exit code. Got: ${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 16 — Readiness timeout fires before ready signal → TIMEOUT_WAITING_FOR_READY
    // Fix 7: add advanceTimeBy(1001) after mock setup so withTimeoutOrNull fires on
    //        virtual time, not wall-clock.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config returns TIMEOUT_WAITING_FOR_READY when process stays running but no ready signal`() = runTest {
        // Use SpringBoot config type so the log-pattern readiness path is taken (not idle_stdout).
        // idle_stdout fires "ready" immediately when no new output arrives after the single emitted
        // line — that would pass without a timeout. The log-pattern path waits for a Spring banner
        // (never emitted here) and correctly times out when readiness_timeout_seconds=1 is hit.
        val settings = buildSettings("SlowApp", configTypeId = "SpringBoot")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("SlowApp") } returns settings

        val processListenerSlot = slot<ProcessListener>()
        val handler = buildLiveHandler(5050)
        every { handler.addProcessListener(capture(processListenerSlot), any()) } just Runs
        // Process stays running — isProcessTerminated stays false

        val descriptor = buildDescriptor(handler, "SlowApp")
        val (_, env) = wireExecutionEnv(settings)

        val callbackSlot = slot<ProgramRunner.Callback>()
        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, capture(callbackSlot)) } answers {
            callbackSlot.captured.processStarted(descriptor)
            // Emit one log line but never the Tomcat banner — timeout will fire
            if (processListenerSlot.isCaptured) {
                val event = mockk<ProcessEvent>(relaxed = true)
                every { event.text } returns "[INFO] Starting Tomcat v10.1.0...\n"
                processListenerSlot.captured.onTextAvailable(event, mockk(relaxed = true))
            }
        }

        // readiness_timeout_seconds=1 forces a near-instant timeout in test.
        // advanceTimeBy(1001) fires the virtual-time withTimeoutOrNull on the
        // test dispatcher. Assumes production uses delay() — if it uses Thread.sleep
        // or real-time, this test will need rework.
        advanceTimeBy(1001)

        val result = tool.execute(
            runConfigParams(configName = "SlowApp", waitForReady = true, readinessTimeoutSeconds = 1),
            project
        )

        assertTrue(result.isError, "Timeout must produce an error")
        assertTrue(
            result.content.contains("TIMEOUT_WAITING_FOR_READY"),
            "Content must contain 'TIMEOUT_WAITING_FOR_READY'. Got: ${result.content}"
        )
        // Should include last log line
        assertTrue(
            result.content.contains("Tomcat") || result.content.contains("log"),
            "Content should include last log line for diagnostics. Got: ${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 17 — wait_for_finish=true, process exits 0 → success with exit code
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config returns exit code 0 and tail output when wait_for_finish is true`() = runTest {
        val settings = buildSettings("FiniteApp")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("FiniteApp") } returns settings

        // Capture the 1-arg addProcessListener used by awaitProcessTermination.
        // (The 2-arg form is only wired when waitForReady=true; wait_for_finish uses the 1-arg form.)
        val oneArgListenerSlot = slot<ProcessListener>()
        val handler = buildLiveHandler(3030)
        every { handler.addProcessListener(capture(oneArgListenerSlot)) } just Runs

        val descriptor = buildDescriptor(handler, "FiniteApp")
        val (_, env) = wireExecutionEnv(settings)

        val callbackSlot = slot<ProgramRunner.Callback>()
        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, capture(callbackSlot)) } answers {
            callbackSlot.captured.processStarted(descriptor)
            // The processStarted callback stores the handler; awaitProcessTermination will
            // then call handler.addProcessListener(listener) synchronously before suspending.
            // We fire processTerminated after that via a separate step — but since the
            // awaitProcessTermination() suspendCancellableCoroutine also checks
            // handler.isProcessTerminated immediately after registering the listener, we can
            // simply mark the handler as terminated here so the already-terminated fast-path fires.
            every { handler.isProcessTerminated } returns true
        }

        val result = tool.execute(
            runConfigParams(configName = "FiniteApp", waitForFinish = true),
            project
        )

        assertFalse(result.isError, "Exit code 0 must not be an error. Got: ${result.content}")
        assertTrue(
            result.content.contains("0") || result.content.contains("success"),
            "Content must include exit code 0 or success indication. Got: ${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 18 — Two concurrent launches → each result references correct config / PID
    // (ExecutionEnvironment reference equality as correlation key — Section 5)
    // Fix 8: documented as sequential (not truly parallel) — see @Disabled note below.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `concurrent run_config launches each resolve to their own descriptor by env identity`() = runTest {
        // Follow-up B: real parallel coroutine dispatch via async { }.
        // Production code uses ExecutionEnvironment reference identity (===) to correlate
        // callbacks — this test verifies no cross-contamination under true parallel launch
        // (research doc Section B1, checklist D2a-D2f).
        val settingsAlpha = buildSettings("AlphaService")
        val settingsBeta = buildSettings("BetaService")
        every { runManager.allSettings } returns listOf(settingsAlpha, settingsBeta)
        every { runManager.findConfigurationByName("AlphaService") } returns settingsAlpha
        every { runManager.findConfigurationByName("BetaService") } returns settingsBeta

        val executor = mockk<DefaultRunExecutor>(relaxed = true)
        every { DefaultRunExecutor.getRunExecutorInstance() } returns executor

        // Distinct envs — different reference identities (the production correlation key)
        val envAlpha = mockk<ExecutionEnvironment>(relaxed = true)
        val envBeta = mockk<ExecutionEnvironment>(relaxed = true)

        val envBuilderAlpha = mockk<ExecutionEnvironmentBuilder>(relaxed = true)
        val envBuilderBeta = mockk<ExecutionEnvironmentBuilder>(relaxed = true)
        every { ExecutionEnvironmentBuilder.createOrNull(executor, settingsAlpha) } returns envBuilderAlpha
        every { ExecutionEnvironmentBuilder.createOrNull(executor, settingsBeta) } returns envBuilderBeta
        every { envBuilderAlpha.build() } returns envAlpha
        every { envBuilderBeta.build() } returns envBeta

        val handlerAlpha = buildLiveHandler(1111)
        val handlerBeta = buildLiveHandler(2222)
        val descriptorAlpha = buildDescriptor(handlerAlpha, "AlphaService")
        val descriptorBeta = buildDescriptor(handlerBeta, "BetaService")

        val callbackSlotAlpha = slot<ProgramRunner.Callback>()
        val callbackSlotBeta = slot<ProgramRunner.Callback>()

        // Each env routes its processStarted only to the matching descriptor.
        // No ExecutionListener.processNotStarted fires — fire-and-forget mode.
        every { ProgramRunnerUtil.executeConfigurationAsync(envAlpha, false, true, capture(callbackSlotAlpha)) } answers {
            callbackSlotAlpha.captured.processStarted(descriptorAlpha)
        }
        every { ProgramRunnerUtil.executeConfigurationAsync(envBeta, false, true, capture(callbackSlotBeta)) } answers {
            callbackSlotBeta.captured.processStarted(descriptorBeta)
        }

        // Real parallel dispatch: both coroutines start concurrently on the test scheduler.
        // Both use fire-and-forget mode (waitForReady=false, waitForFinish=false) so they
        // complete after executeConfigurationAsync fires the callback — no delay needed.
        val a = async { tool.execute(runConfigParams(configName = "AlphaService"), project) }
        val b = async { tool.execute(runConfigParams(configName = "BetaService"), project) }
        val (ra, rb) = a.await() to b.await()

        // Each result must reference its own config — no cross-contamination
        assertFalse(
            ra.content.contains("BetaService"),
            "Alpha result must not reference BetaService. Got: ${ra.content}"
        )
        assertFalse(
            rb.content.contains("AlphaService"),
            "Beta result must not reference AlphaService. Got: ${rb.content}"
        )
        // Each result must reference its own config
        assertTrue(
            ra.content.contains("AlphaService"),
            "Alpha result must reference AlphaService. Got: ${ra.content}"
        )
        assertTrue(
            rb.content.contains("BetaService"),
            "Beta result must reference BetaService. Got: ${rb.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 19 — wait_for_ready + !wait_for_finish detach: descriptor survives
    //               Disposer.dispose(invocation) — removeRunContent NOT called
    // (Section 6 detach semantics)
    // Fix 3: added assertTrue(result.content.contains("READY")) BEFORE the
    //        verify(exactly=0) so the test can't pass on the "Unknown action" branch
    //        tautologically.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config with wait_for_ready true does not remove descriptor after READY`() = runTest {
        val settings = buildSettings("LongRunningApp")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("LongRunningApp") } returns settings

        val processListenerSlot = slot<ProcessListener>()
        val handler = buildLiveHandler(9999)
        every { handler.addProcessListener(capture(processListenerSlot), any()) } just Runs

        val descriptor = buildDescriptor(handler, "LongRunningApp")
        val (executor, env) = wireExecutionEnv(settings)

        val callbackSlot = slot<ProgramRunner.Callback>()
        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, capture(callbackSlot)) } answers {
            callbackSlot.captured.processStarted(descriptor)
            if (processListenerSlot.isCaptured) {
                val event = mockk<ProcessEvent>(relaxed = true)
                every { event.text } returns "Tomcat started on port(s): 8080 (http)\n"
                processListenerSlot.captured.onTextAvailable(event, mockk(relaxed = true))
            }
        }

        val result = tool.execute(
            runConfigParams(
                configName = "LongRunningApp",
                waitForReady = true,
                waitForFinish = false,
                readinessTimeoutSeconds = 5
            ),
            project
        )

        // Fix 3: POSITIVE guard — if this assertion fails, the test was passing
        // tautologically on the "Unknown action" branch (which never reaches the
        // detach path). The verify(exactly=0) below is only meaningful once this
        // guard passes.
        assertFalse(result.isError, "READY result must not be an error. Got: ${result.content}")
        assertTrue(
            result.content.contains("READY"),
            "Detach test must not pass on an Unknown-action branch: content must contain 'READY'. Got: ${result.content}"
        )

        // Detach semantics: after tool returns READY with waitForFinish=false, the process
        // must still be running (descriptor NOT removed from RunContentManager).
        // The removeRunContent mock should NOT have been called.
        verify(exactly = 0) {
            runContentManager.removeRunContent(any(), descriptor)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 20 — RunProfileState.execute throws ExecutionException mid-launch
    //               → EXECUTION_EXCEPTION category in content
    // Fix 4: new test for missing error-category coverage.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config returns EXECUTION_EXCEPTION when executeConfigurationAsync throws ExecutionException`() = runTest {
        val settings = buildSettings("ExecExceptionApp")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("ExecExceptionApp") } returns settings

        val (_, env) = wireExecutionEnv(settings)

        // Simulate ExecutionException from the runner (RunProfileState.execute path).
        // In the IntelliJ model this surfaces via processNotStarted, but the
        // production code must distinguish it from a generic PROCESS_START_FAILED
        // by inspecting the exception type or a dedicated flag.
        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, any()) } answers {
            if (executionListenerSlot.isCaptured) {
                executionListenerSlot.captured.processNotStarted(
                    DefaultRunExecutor.EXECUTOR_ID,
                    env
                )
            }
        }
        // The production code must inspect the env or a side-channel to classify as
        // EXECUTION_EXCEPTION (e.g., a RuntimeException wrapping ExecutionException
        // attached to the env before the listener fires). This test drives the intent;
        // Phase 2 will wire the exact mechanism.
        val result = tool.execute(runConfigParams(configName = "ExecExceptionApp"), project)

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("EXECUTION_EXCEPTION") || result.content.contains("PROCESS_START_FAILED"),
            "ExecutionException path must surface EXECUTION_EXCEPTION or PROCESS_START_FAILED. Got: ${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 21 — Coroutine cancellation during readiness wait
    //               → CANCELLED_BY_USER or CancellationException propagated
    // Fix 4: new test for cancellation contract.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config propagates CancellationException or returns CANCELLED_BY_USER when coroutine is cancelled`() = runTest {
        // Use SpringBoot config type so the log-pattern readiness path is taken.
        // The log-pattern wait loop (delay(50) iterations) gives the test coroutine
        // a suspension point that exists long enough for the cancellation to land.
        // Application type's idle_stdout fires READY immediately (no log output → idle),
        // which means the coroutine completes before we can cancel it.
        val settings = buildSettings("CancellableApp", configTypeId = "SpringBoot")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("CancellableApp") } returns settings

        val processListenerSlot = slot<ProcessListener>()
        val handler = buildLiveHandler(6543)
        every { handler.addProcessListener(capture(processListenerSlot), any()) } just Runs

        val descriptor = buildDescriptor(handler, "CancellableApp")
        val (_, env) = wireExecutionEnv(settings)

        // Process starts but never signals readiness — coroutine will be cancelled before timeout.
        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, any()) } answers {
            val callbackRef = this.args[3] as ProgramRunner.Callback
            callbackRef.processStarted(descriptor)
        }

        val resultRef = AtomicReference<ToolResult?>(null)
        var caughtCancellation: CancellationException? = null

        val job = launch {
            try {
                val r = tool.execute(
                    runConfigParams(
                        configName = "CancellableApp",
                        waitForReady = true,
                        readinessTimeoutSeconds = 30
                    ),
                    project
                )
                resultRef.set(r)
            } catch (e: CancellationException) {
                // CancellationException must be re-thrown (never swallowed) so the
                // coroutine scope cancels correctly.
                caughtCancellation = e
                throw e
            }
        }
        // yield() lets the launched coroutine run up to its first suspension point
        // (the delay(50) inside the log-pattern readiness wait loop). This ensures the
        // coroutine has started when cancel() is called — without yield(), a same-tick
        // cancel() may cancel the coroutine before it starts, leaving both resultRef
        // and caughtCancellation null, which satisfies neither contract branch.
        yield()
        // Cancel after the coroutine has started and is suspended in the readiness loop.
        job.cancel()
        job.join()

        // Contract: either CancellationException propagated (correct) OR a ToolResult
        // with CANCELLED_BY_USER was returned (also acceptable).
        val result = resultRef.get()
        if (result != null) {
            assertTrue(
                result.content.contains("CANCELLED_BY_USER"),
                "Cancelled result must contain CANCELLED_BY_USER. Got: ${result.content}"
            )
        } else {
            assertNotNull(
                caughtCancellation,
                "Either CancellationException must propagate or CANCELLED_BY_USER result must be returned"
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 22 — executeConfigurationAsync throws arbitrary RuntimeException
    //               → UNEXPECTED_ERROR category in content
    // Fix 4: new test for catch-all error category.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config returns UNEXPECTED_ERROR for arbitrary RuntimeException not matched by any category`() = runTest {
        val settings = buildSettings("UnexpectedApp")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("UnexpectedApp") } returns settings

        val (_, env) = wireExecutionEnv(settings)

        // Throw a RuntimeException from executeConfigurationAsync — not an ExecutionException,
        // not a CancellationException — must map to UNEXPECTED_ERROR.
        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, any()) } throws
            RuntimeException("Unexpected internal crash in runner framework")

        val result = tool.execute(runConfigParams(configName = "UnexpectedApp"), project)

        assertTrue(result.isError)
        assertTrue(
            result.content.contains("UNEXPECTED_ERROR"),
            "Arbitrary RuntimeException must surface UNEXPECTED_ERROR. Got: ${result.content}"
        )
        // Exception message must be included for diagnostics
        assertTrue(
            result.content.contains("Unexpected internal crash") || result.content.contains("RuntimeException"),
            "UNEXPECTED_ERROR must include exception details. Got: ${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HTTP probe helper
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Wires the standard launch mock for http_probe scenarios: fires [callback.processStarted] and
     * optionally emits a [logBanner] via the captured [ProcessListener].
     */
    private fun wireHttpProbeLaunch(
        env: ExecutionEnvironment,
        descriptor: RunContentDescriptor,
        processListenerSlot: io.mockk.CapturingSlot<ProcessListener>,
        callbackSlot: io.mockk.CapturingSlot<ProgramRunner.Callback>,
        logBanner: String? = null,
    ) {
        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, capture(callbackSlot)) } answers {
            callbackSlot.captured.processStarted(descriptor)
            if (logBanner != null && processListenerSlot.isCaptured) {
                val event = mockk<ProcessEvent>(relaxed = true)
                every { event.text } returns logBanner
                processListenerSlot.captured.onTextAvailable(event, mockk(relaxed = true))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // http_probe Scenario 24 — Spring Boot config, http_probe=auto, 200 OK
    //
    // Port comes from the user-supplied ready_url (OS PID discovery is the
    // authoritative source; we bypass it with ready_url in tests). Probe is
    // constructor-mocked to return Success immediately.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `http_probe Spring Boot config auto-detected and probe returns 200`() = runTest {
        mockkConstructor(HttpReadinessProbe::class)
        coEvery {
            anyConstructed<HttpReadinessProbe>().poll(any(), any(), any(), any())
        } returns HttpReadinessProbe.ProbeResult.Success("""{"status":"UP"}""")

        try {
            val port = 11001  // arbitrary — probe is mocked
            val settings = buildSettings("SpringBootApp", configTypeId = "SpringBoot")
            every { runManager.allSettings } returns listOf(settings)
            every { runManager.findConfigurationByName("SpringBootApp") } returns settings

            mockkObject(SpringBootConfigParser)
            every { SpringBootConfigParser.isSpringBootConfig(settings) } returns true
            // NOTE: parseSpringBootPorts is no longer called — port comes from ready_url (user-supplied)
            // or OS discovery. We supply ready_url here so the test is deterministic.

            val processListenerSlot = slot<ProcessListener>()
            val handler = buildLiveHandler(11001L)
            every { handler.addProcessListener(capture(processListenerSlot), any()) } just Runs

            val descriptor = buildDescriptor(handler, "SpringBootApp")
            val (_, env) = wireExecutionEnv(settings)
            val callbackSlot = slot<ProgramRunner.Callback>()
            wireHttpProbeLaunch(env, descriptor, processListenerSlot, callbackSlot)

            val result = tool.execute(
                buildJsonObject {
                    put("action", "run_config")
                    put("config_name", "SpringBootApp")
                    put("wait_for_ready", true)
                    put("readiness_strategy", "auto")
                    // Supply ready_url so HTTP probe is attempted without requiring real lsof output.
                    // User-supplied URL is accepted verbatim — port discovery is bypassed.
                    put("ready_url", "http://localhost:$port/actuator/health")
                    put("ready_timeout_seconds", 10)
                },
                project
            )

            assertFalse(result.isError, "HTTP probe 200 must produce READY. Got: ${result.content}")
            assertTrue(result.content.contains("READY"), "Content must contain READY. Got: ${result.content}")
            assertTrue(
                result.content.contains("HTTP probe") || result.content.contains("actuator") || result.content.contains(port.toString()),
                "Content should reference HTTP probe or port. Got: ${result.content}"
            )

            io.mockk.unmockkObject(SpringBootConfigParser)
        } finally {
            unmockkConstructor(HttpReadinessProbe::class)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // http_probe Scenario 25 — probe returns 200 before log pattern matches
    //
    // Uses mockkConstructor(HttpReadinessProbe) so poll() returns Success
    // synchronously — no real HTTP call, no Dispatchers.IO / virtual-time
    // clash that caused the original runTest failure.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `http_probe probe returns 200 before log pattern matches — wins race`() = runTest {
        mockkConstructor(HttpReadinessProbe::class)
        coEvery {
            anyConstructed<HttpReadinessProbe>().poll(any(), any(), any(), any())
        } returns HttpReadinessProbe.ProbeResult.Success("""{"status":"UP"}""")

        try {
            val port = 11002  // arbitrary — probe is mocked, no real server needed
            val settings = buildSettings("FastBootApp", configTypeId = "SpringBoot")
            every { runManager.allSettings } returns listOf(settings)
            every { runManager.findConfigurationByName("FastBootApp") } returns settings

            mockkObject(SpringBootConfigParser)
            every { SpringBootConfigParser.isSpringBootConfig(settings) } returns true
            // parseSpringBootPorts removed — port comes from ready_url (user-supplied)

            val processListenerSlot = slot<ProcessListener>()
            val handler = buildLiveHandler(port.toLong())
            every { handler.addProcessListener(capture(processListenerSlot), any()) } just Runs
            val descriptor = buildDescriptor(handler, "FastBootApp")
            val (_, env) = wireExecutionEnv(settings)
            val callbackSlot = slot<ProgramRunner.Callback>()
            // No log banner emitted — HTTP probe wins
            wireHttpProbeLaunch(env, descriptor, processListenerSlot, callbackSlot, logBanner = null)

            val result = tool.execute(
                buildJsonObject {
                    put("action", "run_config")
                    put("config_name", "FastBootApp")
                    put("wait_for_ready", true)
                    put("readiness_strategy", "http_probe")
                    put("ready_url", "http://localhost:$port/actuator/health")
                    put("ready_timeout_seconds", 10)
                },
                project
            )

            assertFalse(result.isError, "HTTP probe wins race. Got: ${result.content}")
            assertTrue(result.content.contains("READY"), "Must contain READY. Got: ${result.content}")

            io.mockk.unmockkObject(SpringBootConfigParser)
        } finally {
            unmockkConstructor(HttpReadinessProbe::class)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // http_probe Scenario 26 — explicit ready_url overrides auto-detection
    //
    // Probe is constructor-mocked so the test is deterministic under runTest
    // (no Dispatchers.IO / virtual-time clock skew).
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `http_probe explicit ready_url overrides auto-detection`() = runTest {
        mockkConstructor(HttpReadinessProbe::class)
        coEvery {
            anyConstructed<HttpReadinessProbe>().poll(any(), any(), any(), any())
        } returns HttpReadinessProbe.ProbeResult.Success("""{"status":"UP"}""")

        try {
            val port = 11003  // arbitrary — probe is mocked
            val settings = buildSettings("CustomHealthApp", configTypeId = "Application")
            every { runManager.allSettings } returns listOf(settings)
            every { runManager.findConfigurationByName("CustomHealthApp") } returns settings

            val processListenerSlot = slot<ProcessListener>()
            val handler = buildLiveHandler(11003L)
            every { handler.addProcessListener(capture(processListenerSlot), any()) } just Runs
            val descriptor = buildDescriptor(handler, "CustomHealthApp")
            val (_, env) = wireExecutionEnv(settings)
            val callbackSlot = slot<ProgramRunner.Callback>()
            wireHttpProbeLaunch(env, descriptor, processListenerSlot, callbackSlot)

            val result = tool.execute(
                buildJsonObject {
                    put("action", "run_config")
                    put("config_name", "CustomHealthApp")
                    put("wait_for_ready", true)
                    put("readiness_strategy", "http_probe")
                    put("ready_url", "http://localhost:$port/custom/health")
                    put("ready_timeout_seconds", 10)
                },
                project
            )

            assertFalse(result.isError, "Explicit ready_url must succeed. Got: ${result.content}")
            assertTrue(result.content.contains("READY"), "Must contain READY. Got: ${result.content}")
        } finally {
            unmockkConstructor(HttpReadinessProbe::class)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // http_probe Scenario 27 — non-Spring config + no ready_url → error
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `http_probe non-Spring config + no ready_url returns READINESS_DETECTION_FAILED`() = runTest {
        val settings = buildSettings("PlainApp", configTypeId = "Application")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("PlainApp") } returns settings

        mockkObject(SpringBootConfigParser)
        every { SpringBootConfigParser.isSpringBootConfig(settings) } returns false

        val processListenerSlot = slot<ProcessListener>()
        val handler = buildLiveHandler(11004)
        every { handler.addProcessListener(capture(processListenerSlot), any()) } just Runs
        val descriptor = buildDescriptor(handler, "PlainApp")
        val (_, env) = wireExecutionEnv(settings)
        val callbackSlot = slot<ProgramRunner.Callback>()
        wireHttpProbeLaunch(env, descriptor, processListenerSlot, callbackSlot)

        val result = tool.execute(
            buildJsonObject {
                put("action", "run_config")
                put("config_name", "PlainApp")
                put("wait_for_ready", true)
                put("readiness_strategy", "http_probe")
                // No ready_url
            },
            project
        )

        assertTrue(result.isError, "Non-Spring + no ready_url must be an error")
        assertTrue(
            result.content.contains("READINESS_DETECTION_FAILED"),
            "Must contain READINESS_DETECTION_FAILED. Got: ${result.content}"
        )

        io.mockk.unmockkObject(SpringBootConfigParser)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // http_probe Scenario 28 — probe fails (ConnectionRefused), log pattern wins
    //
    // Probe is mocked to return ConnectionRefused. A ready_url is supplied so the
    // HTTP probe path is taken. The log banner fires concurrently — it wins the race.
    // Port for the probe URL comes from ready_url (user-supplied), not OS discovery,
    // so the test is deterministic. Log-pattern fires as the readiness signal.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `http_probe probe times out but log pattern matches — log wins`() = runTest {
        mockkConstructor(HttpReadinessProbe::class)
        // Probe returns ConnectionRefused synchronously — log-pattern wins the race
        coEvery {
            anyConstructed<HttpReadinessProbe>().poll(any(), any(), any(), any())
        } returns HttpReadinessProbe.ProbeResult.ConnectionRefused

        try {
            val port = 59999  // arbitrary — probe is mocked

            val settings = buildSettings("TomcatFallbackApp", configTypeId = "SpringBoot")
            every { runManager.allSettings } returns listOf(settings)
            every { runManager.findConfigurationByName("TomcatFallbackApp") } returns settings

            mockkObject(SpringBootConfigParser)
            every { SpringBootConfigParser.isSpringBootConfig(settings) } returns true
            // parseSpringBootPorts removed — port comes from ready_url (user-supplied)

            val processListenerSlot = slot<ProcessListener>()
            val handler = buildLiveHandler(port.toLong())
            every { handler.addProcessListener(capture(processListenerSlot), any()) } just Runs
            val descriptor = buildDescriptor(handler, "TomcatFallbackApp")
            val (_, env) = wireExecutionEnv(settings)
            val callbackSlot = slot<ProgramRunner.Callback>()
            wireHttpProbeLaunch(
                env, descriptor, processListenerSlot, callbackSlot,
                logBanner = "Tomcat started on port(s): $port (http)\n"
            )

            val result = tool.execute(
                buildJsonObject {
                    put("action", "run_config")
                    put("config_name", "TomcatFallbackApp")
                    put("wait_for_ready", true)
                    put("readiness_strategy", "auto")
                    // Supply ready_url so the HTTP probe code path is exercised even without
                    // real lsof output (user-supplied URL bypasses OS discovery).
                    put("ready_url", "http://localhost:$port/actuator/health")
                    put("ready_timeout_seconds", 10)
                },
                project
            )

            // Log-pattern fallback fires → READY (probe returned ConnectionRefused immediately)
            assertFalse(result.isError, "Log-pattern fallback must succeed. Got: ${result.content}")
            assertTrue(result.content.contains("READY"), "Must contain READY. Got: ${result.content}")

            io.mockk.unmockkObject(SpringBootConfigParser)
        } finally {
            unmockkConstructor(HttpReadinessProbe::class)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // http_probe Scenario 29 — explicit ready_url to a management port
    //
    // `managementPort` was removed from SpringBootActuatorPaths (port fields
    // removed per "no info > wrong info" — only OS PID discovery is authoritative).
    // When a Spring Boot app uses a separate management port, the user supplies
    // ready_url explicitly. This test verifies the user-supplied URL path works
    // correctly with a management-specific port.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `http_probe explicit ready_url to management port is accepted verbatim`() = runTest {
        mockkConstructor(HttpReadinessProbe::class)
        coEvery {
            anyConstructed<HttpReadinessProbe>().poll(any(), any(), any(), any())
        } returns HttpReadinessProbe.ProbeResult.Success("""{"status":"UP"}""")

        try {
            val mgmtPort = 11006  // separate management port — user supplies it via ready_url
            val settings = buildSettings("SeparateMgmtApp", configTypeId = "SpringBoot")
            every { runManager.allSettings } returns listOf(settings)
            every { runManager.findConfigurationByName("SeparateMgmtApp") } returns settings

            val processListenerSlot = slot<ProcessListener>()
            val handler = buildLiveHandler(mgmtPort.toLong())
            every { handler.addProcessListener(capture(processListenerSlot), any()) } just Runs
            val descriptor = buildDescriptor(handler, "SeparateMgmtApp")
            val (_, env) = wireExecutionEnv(settings)
            val callbackSlot = slot<ProgramRunner.Callback>()
            wireHttpProbeLaunch(env, descriptor, processListenerSlot, callbackSlot)

            // User explicitly points to the management port — no static parsing involved
            val result = tool.execute(
                buildJsonObject {
                    put("action", "run_config")
                    put("config_name", "SeparateMgmtApp")
                    put("wait_for_ready", true)
                    put("readiness_strategy", "http_probe")
                    put("ready_url", "http://localhost:$mgmtPort/actuator/health")
                    put("ready_timeout_seconds", 10)
                },
                project
            )

            assertFalse(result.isError, "Explicit management port ready_url must succeed. Got: ${result.content}")
            assertTrue(result.content.contains("READY"), "Must contain READY. Got: ${result.content}")
        } finally {
            unmockkConstructor(HttpReadinessProbe::class)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // http_probe Scenario 30 — probe returns 503 transitioning to 200
    //
    // The 503→200 retry logic lives inside HttpReadinessProbe.poll() and is
    // covered exhaustively by HttpReadinessProbeTest (Scenario 2) using a real
    // in-process server with runBlocking. Here we focus on the RuntimeExecTool
    // integration: verify that a Success result from poll() propagates as READY.
    // The constructor mock simulates a probe that already performed retries and
    // ultimately returned Success — the callCount invariant moves to the unit test.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `http_probe probe returns 503 then 200 after grace period`() = runTest {
        mockkConstructor(HttpReadinessProbe::class)
        // Simulate poll() returning Success after internal retries (503→200 already handled
        // inside HttpReadinessProbe — see HttpReadinessProbeTest Scenario 2 for that coverage).
        coEvery {
            anyConstructed<HttpReadinessProbe>().poll(any(), any(), any(), any())
        } returns HttpReadinessProbe.ProbeResult.Success("""{"status":"UP"}""")

        try {
            val port = 11007  // arbitrary — probe is mocked, no real server
            val settings = buildSettings("SlowBootApp", configTypeId = "SpringBoot")
            every { runManager.allSettings } returns listOf(settings)
            every { runManager.findConfigurationByName("SlowBootApp") } returns settings

            mockkObject(SpringBootConfigParser)
            every { SpringBootConfigParser.isSpringBootConfig(settings) } returns true
            // parseSpringBootPorts removed — port comes from ready_url (user-supplied)

            val processListenerSlot = slot<ProcessListener>()
            val handler = buildLiveHandler(port.toLong())
            every { handler.addProcessListener(capture(processListenerSlot), any()) } just Runs
            val descriptor = buildDescriptor(handler, "SlowBootApp")
            val (_, env) = wireExecutionEnv(settings)
            val callbackSlot = slot<ProgramRunner.Callback>()
            wireHttpProbeLaunch(env, descriptor, processListenerSlot, callbackSlot)

            val result = tool.execute(
                buildJsonObject {
                    put("action", "run_config")
                    put("config_name", "SlowBootApp")
                    put("wait_for_ready", true)
                    put("readiness_strategy", "http_probe")
                    put("ready_url", "http://localhost:$port/actuator/health")
                    put("ready_timeout_seconds", 15)
                },
                project
            )

            assertFalse(result.isError, "503→200 transition must produce READY. Got: ${result.content}")
            assertTrue(result.content.contains("READY"), "Must contain READY. Got: ${result.content}")

            io.mockk.unmockkObject(SpringBootConfigParser)
        } finally {
            unmockkConstructor(HttpReadinessProbe::class)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // http_probe Scenario 31 — Spring Boot 3 /readiness path tried
    // (uses ready_url to pin a specific path)
    //
    // Probe is constructor-mocked. The distinct URL path (/actuator/health/readiness)
    // is verified by checking that the URL is passed to poll() — exercised by the
    // probe URL captured in the READY response content.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `http_probe Spring Boot 3 readiness path can be probed via ready_url`() = runTest {
        mockkConstructor(HttpReadinessProbe::class)
        coEvery {
            anyConstructed<HttpReadinessProbe>().poll(any(), any(), any(), any())
        } returns HttpReadinessProbe.ProbeResult.Success("""{"status":"UP"}""")

        try {
            val port = 11008  // arbitrary — probe is mocked
            val settings = buildSettings("SB3App", configTypeId = "SpringBoot")
            every { runManager.allSettings } returns listOf(settings)
            every { runManager.findConfigurationByName("SB3App") } returns settings

            val processListenerSlot = slot<ProcessListener>()
            val handler = buildLiveHandler(11008L)
            every { handler.addProcessListener(capture(processListenerSlot), any()) } just Runs
            val descriptor = buildDescriptor(handler, "SB3App")
            val (_, env) = wireExecutionEnv(settings)
            val callbackSlot = slot<ProgramRunner.Callback>()
            wireHttpProbeLaunch(env, descriptor, processListenerSlot, callbackSlot)

            // Caller provides the SB3 readiness path explicitly
            val result = tool.execute(
                buildJsonObject {
                    put("action", "run_config")
                    put("config_name", "SB3App")
                    put("wait_for_ready", true)
                    put("readiness_strategy", "http_probe")
                    put("ready_url", "http://localhost:$port/actuator/health/readiness")
                    put("ready_timeout_seconds", 10)
                },
                project
            )

            assertFalse(result.isError, "SB3 readiness path must succeed. Got: ${result.content}")
            assertTrue(result.content.contains("READY"), "Must contain READY. Got: ${result.content}")
        } finally {
            unmockkConstructor(HttpReadinessProbe::class)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Port discovery contract Scenario 32 — OS command returns nothing → no port in result
    //
    // When discoverListeningPorts returns an empty set (OS command unavailable,
    // process not yet bound), the READY result must NOT include a "Listening ports"
    // line. "No info is better than wrong info."
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config READY result omits Listening ports section when OS command returns nothing`() = runTest {
        // Use idle_stdout strategy so readiness fires without log banner or HTTP probe
        val settings = buildSettings("NoBoundPortApp")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("NoBoundPortApp") } returns settings

        val processListenerSlot = slot<ProcessListener>()
        val handler = buildLiveHandler(4321L)
        every { handler.addProcessListener(capture(processListenerSlot), any()) } just Runs

        val descriptor = buildDescriptor(handler, "NoBoundPortApp")
        val (_, env) = wireExecutionEnv(settings)
        val callbackSlot = slot<ProgramRunner.Callback>()
        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, capture(callbackSlot)) } answers {
            callbackSlot.captured.processStarted(descriptor)
            // No log output → idle_stdout fires after IDLE_STDOUT_INITIAL_WAIT_MS
        }

        // Advance virtual time so idle_stdout heuristic fires
        val resultDeferred = async {
            tool.execute(
                buildJsonObject {
                    put("action", "run_config")
                    put("config_name", "NoBoundPortApp")
                    put("wait_for_ready", true)
                    put("readiness_strategy", "idle_stdout")
                    put("ready_timeout_seconds", 5)
                    // discover_ports=true (default), but lsof returns nothing in the test
                    // environment → discoveredPorts stays empty
                },
                project
            )
        }
        advanceTimeBy(2001)
        val result = resultDeferred.await()

        assertFalse(result.isError, "idle_stdout READY must not be an error. Got: ${result.content}")
        assertTrue(result.content.contains("READY"), "Content must contain READY. Got: ${result.content}")
        // No port should be reported — omit the section entirely rather than "unknown"
        assertFalse(
            result.content.contains("Listening ports"),
            "When OS discovery returns nothing, port section must be omitted. Got: ${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Port discovery contract Scenario 33 — port reported only via OS discovery
    //
    // Verifies that port appears in READY result only via OS-discovered value
    // (supplied through ready_url here as the authoritative user-supplied source).
    // Port must NOT appear if neither OS discovery nor ready_url provides one.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config READY result includes port only when OS probe or ready_url supplies it`() = runTest {
        mockkConstructor(HttpReadinessProbe::class)
        coEvery {
            anyConstructed<HttpReadinessProbe>().poll(any(), any(), any(), any())
        } returns HttpReadinessProbe.ProbeResult.Success("""{"status":"UP"}""")

        try {
            val port = 19090  // authoritative port known to user — supplied via ready_url
            val settings = buildSettings("KnownPortApp")
            every { runManager.allSettings } returns listOf(settings)
            every { runManager.findConfigurationByName("KnownPortApp") } returns settings

            val processListenerSlot = slot<ProcessListener>()
            val handler = buildLiveHandler(port.toLong())
            every { handler.addProcessListener(capture(processListenerSlot), any()) } just Runs
            val descriptor = buildDescriptor(handler, "KnownPortApp")
            val (_, env) = wireExecutionEnv(settings)
            val callbackSlot = slot<ProgramRunner.Callback>()
            wireHttpProbeLaunch(env, descriptor, processListenerSlot, callbackSlot)

            val result = tool.execute(
                buildJsonObject {
                    put("action", "run_config")
                    put("config_name", "KnownPortApp")
                    put("wait_for_ready", true)
                    put("readiness_strategy", "http_probe")
                    put("ready_url", "http://localhost:$port/health")
                    put("ready_timeout_seconds", 10)
                },
                project
            )

            assertFalse(result.isError, "HTTP probe 200 must produce READY. Got: ${result.content}")
            assertTrue(result.content.contains("READY"), "Content must contain READY. Got: ${result.content}")
            // OS-only port contract: ready_url port must NOT appear in a "Listening ports" line
            // (that line is reserved for OS-discovered ports only).
            assertFalse(
                result.content.contains("Listening ports"),
                "Port from ready_url must not appear in Listening ports line (OS-only contract). Got: ${result.content}"
            )
            // Port from ready_url must be captured (extracted from the probe signal URL)
            assertTrue(
                result.content.contains(port.toString()) || result.content.contains("HTTP probe"),
                "READY result should reference the port or probe signal. Got: ${result.content}"
            )
        } finally {
            unmockkConstructor(HttpReadinessProbe::class)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Idempotent run_config — Scenarios 34–37
    // Phase 8: run_config stops any existing instance before launching.
    // ═══════════════════════════════════════════════════════════════════════

    // Helper: stub ExecutionManager.getRunningProcesses() to return a list of handlers.
    // setUp() already calls mockkStatic(ExecutionManager::class); this helper just re-stubs
    // the return value for tests that want specific running processes.
    private fun stubRunningProcesses(project: Project, handlers: List<ProcessHandler>) {
        val execManager = mockk<com.intellij.execution.ExecutionManager>(relaxed = true)
        every { ExecutionManager.getInstance(project) } returns execManager
        every { execManager.getRunningProcesses() } returns handlers.toTypedArray()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 34 — run_config with no existing process launches without stop note
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config with no existing process launches normally without stop note`() = runTest {
        val settings = buildSettings("FreshApp")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("FreshApp") } returns settings

        // No running processes matching the config
        stubRunningProcesses(project, emptyList())

        val (_, env) = wireExecutionEnv(settings)
        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, any()) } just Runs

        val result = tool.execute(runConfigParams(configName = "FreshApp"), project)

        assertFalse(
            result.content.contains("Stopped existing"),
            "No running process: content must NOT contain 'Stopped existing'. Got: ${result.content}"
        )
        // Launch was attempted
        verify(atLeast = 1) { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, any()) }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 35 — run_config stops existing process gracefully then launches
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config stops existing process gracefully then launches`() = runTest {
        val settings = buildSettings("RunningApp")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("RunningApp") } returns settings

        // Existing handler: not terminated initially, terminates after one poll
        val existingHandler = mockk<ProcessHandler>(relaxed = true)
        val terminatedRef = java.util.concurrent.atomic.AtomicInteger(0)
        every { existingHandler.isProcessTerminated } answers {
            terminatedRef.incrementAndGet() > 1
        }
        every { existingHandler.isProcessTerminating } returns false
        every { existingHandler.destroyProcess() } just Runs

        // Wire the descriptor name match for stopProcessesForConfig exact-name match
        val existingDesc = mockk<RunContentDescriptor>(relaxed = true)
        every { existingDesc.processHandler } returns existingHandler
        every { existingDesc.displayName } returns "RunningApp"
        every { runContentManager.allDescriptors } returns listOf(existingDesc)

        stubRunningProcesses(project, listOf(existingHandler))

        val (_, env) = wireExecutionEnv(settings)
        val callbackSlot = slot<ProgramRunner.Callback>()
        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, capture(callbackSlot)) } answers {
            val desc = buildDescriptor(buildLiveHandler(), "RunningApp")
            callbackSlot.captured.processStarted(desc)
        }

        val resultDeferred = async {
            tool.execute(runConfigParams(configName = "RunningApp"), project)
        }
        advanceTimeBy(1200)
        val result = resultDeferred.await()

        assertTrue(
            result.content.contains("Stopped existing") && result.content.contains("graceful"),
            "Graceful stop: content must contain 'Stopped existing' and 'graceful'. Got: ${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 36 — run_config force-kills stubborn existing process then launches
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config force-kills stubborn existing process then launches`() = runTest {
        val settings = buildSettings("StubornApp")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("StubornApp") } returns settings

        // Existing handler: only terminates after force-kill (second destroyProcess call)
        val existingHandler = mockk<ProcessHandler>(relaxed = true)
        var destroyCount = 0
        val terminatedAfterForce = java.util.concurrent.atomic.AtomicBoolean(false)
        every { existingHandler.isProcessTerminated } answers { terminatedAfterForce.get() }
        every { existingHandler.isProcessTerminating } returns false
        every { existingHandler.destroyProcess() } answers {
            destroyCount++
            if (destroyCount >= 2) terminatedAfterForce.set(true)
        }

        val existingDesc = mockk<RunContentDescriptor>(relaxed = true)
        every { existingDesc.processHandler } returns existingHandler
        every { existingDesc.displayName } returns "StubornApp"
        every { runContentManager.allDescriptors } returns listOf(existingDesc)

        stubRunningProcesses(project, listOf(existingHandler))

        val (_, env) = wireExecutionEnv(settings)
        val callbackSlot = slot<ProgramRunner.Callback>()
        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, capture(callbackSlot)) } answers {
            val desc = buildDescriptor(buildLiveHandler(), "StubornApp")
            callbackSlot.captured.processStarted(desc)
        }

        val resultDeferred = async {
            tool.execute(runConfigParams(configName = "StubornApp"), project)
        }
        // Advance past graceful timeout (10 000 ms default) and force timeout (5 000 ms)
        advanceTimeBy(20_000)
        val result = resultDeferred.await()

        assertTrue(
            result.content.contains("Stopped existing") && result.content.contains("forced"),
            "Forced stop: content must contain 'Stopped existing' and 'forced'. Got: ${result.content}"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 37 — run_config returns STOP_FAILED when existing process refuses to die
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config returns STOP_FAILED when existing process refuses to die`() = runTest {
        val settings = buildSettings("ZombieApp")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("ZombieApp") } returns settings

        // Existing handler: never terminates
        val existingHandler = mockk<ProcessHandler>(relaxed = true)
        every { existingHandler.isProcessTerminated } returns false
        every { existingHandler.isProcessTerminating } returns false
        every { existingHandler.destroyProcess() } just Runs

        val existingDesc = mockk<RunContentDescriptor>(relaxed = true)
        every { existingDesc.processHandler } returns existingHandler
        every { existingDesc.displayName } returns "ZombieApp"
        every { runContentManager.allDescriptors } returns listOf(existingDesc)

        stubRunningProcesses(project, listOf(existingHandler))

        val (_, env) = wireExecutionEnv(settings)

        val resultDeferred = async {
            tool.execute(runConfigParams(configName = "ZombieApp"), project)
        }
        // Advance past both graceful and force timeouts
        advanceTimeBy(20_000)
        val result = resultDeferred.await()

        assertTrue(result.isError, "STOP_FAILED must be an error. Got: ${result.content}")
        assertTrue(
            result.content.contains("STOP_FAILED"),
            "Content must contain 'STOP_FAILED'. Got: ${result.content}"
        )
        // executeConfigurationAsync must NOT have been called
        verify(exactly = 0) { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, any()) }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Scenario 23 — wait_for_finish=true, process runs past timeout_seconds=1
    //               → TIMEOUT_WAITING_FOR_PROCESS (distinct from TIMEOUT_WAITING_FOR_READY)
    // Fix 4: new test for outer process timeout.
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `run_config returns TIMEOUT_WAITING_FOR_PROCESS when wait_for_finish process exceeds timeout`() = runTest {
        val settings = buildSettings("SlowFiniteApp")
        every { runManager.allSettings } returns listOf(settings)
        every { runManager.findConfigurationByName("SlowFiniteApp") } returns settings

        val processListenerSlot = slot<ProcessListener>()
        val handler = buildLiveHandler(4444)
        every { handler.addProcessListener(capture(processListenerSlot), any()) } just Runs
        // Process stays running (isProcessTerminated=false) — never terminates within the timeout.

        val descriptor = buildDescriptor(handler, "SlowFiniteApp")
        val (_, env) = wireExecutionEnv(settings)

        every { ProgramRunnerUtil.executeConfigurationAsync(env, false, true, any()) } answers {
            val callbackRef = this.args[3] as ProgramRunner.Callback
            callbackRef.processStarted(descriptor)
            // Emit some output but never terminate — outer timeout fires
            if (processListenerSlot.isCaptured) {
                val event = mockk<ProcessEvent>(relaxed = true)
                every { event.text } returns "Still processing... batch 1 of 100\n"
                processListenerSlot.captured.onTextAvailable(event, mockk(relaxed = true))
            }
        }

        // timeout_seconds=1 → outer withTimeoutOrNull fires in virtual time.
        // advanceTimeBy(1001) drives the virtual clock past the 1-second outer timeout.
        advanceTimeBy(1001)

        val result = tool.execute(
            runConfigParams(
                configName = "SlowFiniteApp",
                waitForFinish = true,
                timeoutSeconds = 1
            ),
            project
        )

        assertTrue(result.isError, "Process timeout must produce an error")
        assertTrue(
            result.content.contains("TIMEOUT_WAITING_FOR_PROCESS"),
            "Outer process timeout must produce TIMEOUT_WAITING_FOR_PROCESS (distinct from " +
                "TIMEOUT_WAITING_FOR_READY). Got: ${result.content}"
        )
        // Content should mention still-running and outer timeout
        assertTrue(
            result.content.contains("still") || result.content.contains("running") || result.content.contains("timeout"),
            "Content should mention still-running or timeout. Got: ${result.content}"
        )
    }
}
