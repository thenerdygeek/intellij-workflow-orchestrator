package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.util.execution.ParametersListUtil
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.framework.MavenUtils
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.idea.maven.execution.MavenRunnerParameters

internal const val CATEGORY_INVALID_ARGS = "INVALID_ARGS"
internal const val CATEGORY_NOT_A_MAVEN_PROJECT = "NOT_A_MAVEN_PROJECT"
internal const val CATEGORY_APPROVAL_DENIED = "APPROVAL_DENIED"
internal const val CATEGORY_EXECUTION_EXCEPTION = "EXECUTION_EXCEPTION"
internal const val CATEGORY_PROCESS_NOT_STARTED = "PROCESS_NOT_STARTED"
internal const val CATEGORY_BUILD_FAILURE = "BUILD_FAILURE"
internal const val CATEGORY_TIMEOUT = "TIMEOUT"

internal const val MAVEN_GOAL_TIMEOUT_MS = 1_200_000L  // 20 minutes

internal sealed class BuildOutcome {
    data class Completed(val exitCode: Int, val output: String, val durationSec: Double) : BuildOutcome()
    data class TimedOut(val output: String, val timeoutSec: Double) : BuildOutcome()
    data class FailedPreflight(val toolResult: ToolResult) : BuildOutcome()
}

internal fun buildPreflightError(category: String, message: String): ToolResult =
    ToolResult(
        content = "$category: $message",
        summary = "$category — see content",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true
    )

internal suspend fun executeRunMavenGoal(
    params: JsonObject,
    project: Project,
    tool: AgentTool
): ToolResult {
    val goals = params["goals"]?.jsonPrimitive?.content ?: ""
    if (goals.isBlank()) {
        return buildPreflightError(
            CATEGORY_INVALID_ARGS,
            "goals is blank — Maven CLI silently produces BUILD SUCCESS for an empty goal list, which is misleading. Provide at least one goal (e.g., \"clean\", \"install\", \"dependency:tree\")."
        )
    }
    if (MavenUtils.getMavenManager(project) == null) {
        return buildPreflightError(
            CATEGORY_NOT_A_MAVEN_PROJECT,
            "this project is not Mavenized (MavenProjectsManager reports isMavenizedProject=false). " +
                "Use ./gradlew goals via run_command for Gradle projects, or open the project's pom.xml to import as Maven first."
        )
    }
    val extraArgsRaw = params["extra_args"]?.jsonPrimitive?.content ?: ""
    val extraTokens: List<String> = try {
        tokenizeExtraArgs(extraArgsRaw)
    } catch (e: Exception) {
        return buildPreflightError(
            CATEGORY_INVALID_ARGS,
            "extra_args tokenization failed (${e::class.simpleName}: ${e.message}). " +
                "Check for unbalanced quotes or invalid escape sequences."
        )
    }
    val modules: List<String> = params["modules"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.content.takeIf { s -> s.isNotBlank() } }
        ?: emptyList()
    val offline: Boolean = params["offline"]?.jsonPrimitive?.booleanOrNull ?: false

    val approvalArgs = buildString {
        append("mvn ")
        append(goals)
        if (modules.isNotEmpty()) append(" -pl ").append(modules.joinToString(","))
        if (offline) append(" -o")
    }
    val approval = tool.requestApproval(
        toolName = "java_runtime_exec.run_maven_goal",
        args = approvalArgs,
        riskLevel = "medium",
        allowSessionApproval = true
    )
    if (approval == ApprovalResult.DENIED) {
        return buildPreflightError(
            CATEGORY_APPROVAL_DENIED,
            "user declined approval for: $approvalArgs"
        )
    }

    val allGoalTokens = assembleGoalTokens(goals, modules, extraTokens, offline)

    val mavenHome: String = try {
        org.jetbrains.idea.maven.project.MavenProjectsManager
            .getInstance(project).generalSettings.toString()
    } catch (_: Throwable) { "(IDE default)" }
    val workingDir = project.basePath ?: "(unknown)"

    return when (val outcome = launchAndAwaitMavenBuild(project, goals, modules, allGoalTokens)) {
        is BuildOutcome.FailedPreflight -> outcome.toolResult
        is BuildOutcome.Completed -> {
            val spill = tool.spillOrFormat(outcome.output, project)
            buildSuccessResult(
                goals = goals,
                modules = modules,
                workingDir = workingDir,
                mavenHome = mavenHome,
                exitCode = outcome.exitCode,
                durationSec = outcome.durationSec,
                output = spill.preview,
                spillPath = spill.spilledToFile,
                project = project
            )
        }
        is BuildOutcome.TimedOut -> {
            val spill = tool.spillOrFormat(outcome.output, project)
            buildTimeoutResult(
                goals = goals,
                modules = modules,
                workingDir = workingDir,
                mavenHome = mavenHome,
                timeoutSec = outcome.timeoutSec,
                output = spill.preview,
                spillPath = spill.spilledToFile,
                project = project
            )
        }
    }
}

internal fun tokenizeExtraArgs(raw: String): List<String> =
    if (raw.isBlank()) emptyList() else ParametersListUtil.parse(raw)

/**
 * Locale-independent lookup. The id "MavenRunConfiguration" is registered by
 * the bundled Maven plugin (`org.jetbrains.idea.maven.execution.MavenRunConfigurationType`)
 * and is stable across IntelliJ versions. The historical displayName "Maven" would
 * have been locale-sensitive (it flows through MavenRunnerBundle) and is intentionally
 * not used as a fallback.
 */
internal fun findMavenConfigurationType(types: List<ConfigurationType>): ConfigurationType? =
    types.firstOrNull { it.id == "MavenRunConfiguration" }

internal fun assembleGoalTokens(
    goals: String,
    modules: List<String>,
    extraTokens: List<String>,
    offline: Boolean
): List<String> {
    val moduleTokens =
        if (modules.isNotEmpty()) listOf("-pl", modules.joinToString(","), "-am") else emptyList()
    val goalsTokens = goals.trim().split("\\s+".toRegex())
    val offlineToken = if (offline) listOf("-o") else emptyList()
    return moduleTokens + goalsTokens + extraTokens + offlineToken
}

private const val HEADER_DIVIDER = "─────────────────────────────────────────"

internal fun formatHeader(
    goals: String,
    modules: List<String>,
    workingDir: String,
    mavenHome: String,
    exitCode: Int,
    durationSec: Double
): String = buildString {
    append("Maven goal: ").append(goals).append('\n')
    append("Modules: ").append(if (modules.isEmpty()) "all" else modules.joinToString(", ")).append('\n')
    append("Working directory: ").append(workingDir).append('\n')
    append("Maven home (IDE-configured): ").append(mavenHome).append('\n')
    append("Exit code: ").append(exitCode).append('\n')
    append("Duration: ").append("%.1f".format(durationSec)).append("s\n")
    append(HEADER_DIVIDER).append('\n')
}

internal fun buildSuccessResult(
    goals: String,
    modules: List<String>,
    workingDir: String,
    mavenHome: String,
    exitCode: Int,
    durationSec: Double,
    output: String,
    spillPath: String? = null,
    project: Project
): ToolResult {
    val header = formatHeader(goals, modules, workingDir, mavenHome, exitCode, durationSec)
    val isFailure = exitCode != 0
    val prefix = if (isFailure) "BUILD_FAILURE: exit code $exitCode\n\n" else ""
    val body = prefix + header + output
    val mark = if (isFailure) "✗" else "✓"
    val summary = "mvn $goals $mark (${"%.0f".format(durationSec)}s, exit=$exitCode)"
    return ToolResult(
        content = body,
        summary = summary,
        tokenEstimate = body.length / 4 + 1,
        isError = isFailure,
        spillPath = spillPath
    )
}

internal fun buildTimeoutResult(
    goals: String,
    modules: List<String>,
    workingDir: String,
    mavenHome: String,
    timeoutSec: Double,
    output: String,
    spillPath: String? = null,
    project: Project
): ToolResult {
    val header = formatHeader(
        goals, modules, workingDir, mavenHome,
        exitCode = -1, durationSec = timeoutSec
    )
    val body = "TIMEOUT: exceeded ${"%.0f".format(timeoutSec)}s — process killed.\n\n" +
        "Narrow scope: smaller modules list, skip tests with -DskipTests, " +
        "or use -T <n> for parallelism.\n\n" + header + output
    return ToolResult(
        content = body,
        summary = "mvn $goals ⏱ TIMEOUT after ${"%.0f".format(timeoutSec)}s",
        tokenEstimate = body.length / 4 + 1,
        isError = true,
        spillPath = spillPath
    )
}

/**
 * Build a transient MavenRunConfiguration and run it through the IDE Run executor.
 * Output is captured via the descriptor's ProcessHandler. RunInvocation disposal
 * guarantees process+listener teardown on every exit path.
 *
 * Returns a [BuildOutcome] — either [BuildOutcome.Completed], [BuildOutcome.TimedOut],
 * or [BuildOutcome.FailedPreflight]. The suspend-friendly caller ([executeRunMavenGoal])
 * is responsible for calling [AgentTool.spillOrFormat] on the raw output before building
 * the final [ToolResult].
 */
private suspend fun launchAndAwaitMavenBuild(
    project: Project,
    goals: String,
    modules: List<String>,
    allGoalTokens: List<String>
): BuildOutcome {
    val output = StringBuilder()
    val startNs = System.nanoTime()

    return withTimeoutOrNull(MAVEN_GOAL_TIMEOUT_MS) {
        suspendCancellableCoroutine<BuildOutcome> { continuation ->
            val agentService: AgentService = try {
                project.service<AgentService>()
            } catch (e: Throwable) {
                continuation.resume(BuildOutcome.FailedPreflight(buildPreflightError(
                    CATEGORY_EXECUTION_EXCEPTION,
                    "AgentService not available: ${e::class.simpleName}: ${e.message}"
                )))
                return@suspendCancellableCoroutine
            }
            val invocation = agentService
                .newRunInvocation("run-maven-${goals.take(20).replace(" ", "_")}")

            continuation.invokeOnCancellation {
                Disposer.dispose(invocation)
            }

            invokeLater {
                if (!continuation.isActive) return@invokeLater

                val types = com.intellij.execution.configurations.ConfigurationType
                    .CONFIGURATION_TYPE_EP.extensionList
                val mavenType = findMavenConfigurationType(types) ?: run {
                    continuation.resume(BuildOutcome.FailedPreflight(buildPreflightError(
                        CATEGORY_EXECUTION_EXCEPTION,
                        "MavenRunConfigurationType is not registered. Is the Maven plugin enabled in this IDE?"
                    )))
                    return@invokeLater
                }
                val factory = mavenType.configurationFactories.firstOrNull() ?: run {
                    continuation.resume(BuildOutcome.FailedPreflight(buildPreflightError(
                        CATEGORY_EXECUTION_EXCEPTION,
                        "Maven ConfigurationType has no configuration factories registered."
                    )))
                    return@invokeLater
                }

                val configName = "[Agent] mvn ${goals.take(40)}"
                val settings: RunnerAndConfigurationSettings =
                    RunManager.getInstance(project).createConfiguration(configName, factory)
                val config = settings.configuration

                // Populate runner parameters. Kotlin Java-bean property-access synthesis
                // turns the setter into property assignment. If a future Maven plugin
                // removes the setter, fall back to reflection (see spec §Step 4).
                val params = MavenRunnerParameters(
                    /* workingDirPath     */ project.basePath ?: ".",
                    /* pomFileName        */ "pom.xml",
                    /* isPomExecution     */ true,
                    /* goals              */ allGoalTokens,
                    /* explicitProfiles   */ emptyMap<String, Boolean>()
                )
                try {
                    config.javaClass.getMethod(
                        "setRunnerParameters", MavenRunnerParameters::class.java
                    ).invoke(config, params)
                } catch (e: Throwable) {
                    continuation.resume(BuildOutcome.FailedPreflight(buildPreflightError(
                        CATEGORY_EXECUTION_EXCEPTION,
                        "could not populate MavenRunnerParameters: ${e::class.simpleName}: ${e.message}"
                    )))
                    return@invokeLater
                }

                // Do NOT call RunManager.setTemporaryConfiguration(settings) — that
                // overwrites the user's selectedConfiguration and causes
                // "initialization error on next manual run" (regression documented
                // by run_tests commit 9b164bf3).
                settings.isTemporary = true

                val executor = DefaultRunExecutor.getRunExecutorInstance()
                val env: ExecutionEnvironment = ExecutionEnvironmentBuilder
                    .createOrNull(executor, settings)
                    ?.build()
                    ?: run {
                        continuation.resume(BuildOutcome.FailedPreflight(buildPreflightError(
                            CATEGORY_EXECUTION_EXCEPTION,
                            "ExecutionEnvironmentBuilder.createOrNull returned null — no runner registered for MavenRunConfiguration."
                        )))
                        return@invokeLater
                    }

                val callback = object : ProgramRunner.Callback {
                    override fun processStarted(descriptor: RunContentDescriptor?) {
                        if (descriptor == null) {
                            if (continuation.isActive) continuation.resume(BuildOutcome.FailedPreflight(buildPreflightError(
                                CATEGORY_PROCESS_NOT_STARTED,
                                "ProgramRunner.Callback received no RunContentDescriptor."
                            )))
                            return
                        }
                        invocation.descriptorRef.set(descriptor)

                        descriptor.processHandler?.addProcessListener(object : ProcessAdapter() {
                            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                                output.append(event.text)
                            }
                            override fun processTerminated(event: ProcessEvent) {
                                if (!continuation.isActive) return
                                val durationSec = (System.nanoTime() - startNs) / 1_000_000_000.0
                                continuation.resume(BuildOutcome.Completed(
                                    exitCode = event.exitCode,
                                    output = output.toString(),
                                    durationSec = durationSec
                                ))
                            }
                        })

                        // Belt-and-suspenders descriptor cleanup. Maven's own runner
                        // self-cleans the RunContentDescriptor on normal termination
                        // — so this is NOT required the way it is for JUnit (where
                        // TestResultsViewer is Disposable with no removeEventsListener
                        // API). It IS still needed for abnormal termination and
                        // agent-cancel paths where the runner may not get a chance to
                        // clean up. RunContentManager.removeRunContent is idempotent.
                        invocation.onDispose {
                            val d = invocation.descriptorRef.get() ?: return@onDispose
                            invokeLater {
                                RunContentManager.getInstance(project).removeRunContent(executor, d)
                            }
                        }
                    }
                }

                // Defence-in-depth: ExecutionListener.processNotStarted catches cases
                // the runner refuses (no ProgramRunner registered, executor disabled,
                // JDK lookup failed) that the callback above doesn't surface.
                val conn = project.messageBus.connect()
                invocation.subscribeTopic(conn)
                conn.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                    override fun processNotStarted(executorId: String, e: ExecutionEnvironment) {
                        if (e === env && continuation.isActive) {
                            continuation.resume(BuildOutcome.FailedPreflight(buildPreflightError(
                                CATEGORY_PROCESS_NOT_STARTED,
                                "Execution framework aborted before launch: $executorId."
                            )))
                        }
                    }
                })

                try {
                    ProgramRunnerUtil.executeConfigurationAsync(env, false, true, callback)
                } catch (_: NoSuchMethodError) {
                    env.callback = callback
                    ProgramRunnerUtil.executeConfiguration(env, false, true)
                }
            }
        }
    } ?: BuildOutcome.TimedOut(output.toString(), MAVEN_GOAL_TIMEOUT_MS / 1000.0)
}
