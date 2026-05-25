package com.workflow.orchestrator.bamboo.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.configurations.RunProfileState
import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.settings.PluginSettings
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.*
import java.io.OutputStream

class BambooBuildRunState(
    private val environment: ExecutionEnvironment,
    private val configuration: BambooBuildRunConfiguration
) : RunProfileState {

    private val log = Logger.getInstance(BambooBuildRunState::class.java)

    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val processHandler = BambooBuildProcessHandler(environment, configuration)
        val console = com.intellij.execution.filters.TextConsoleBuilderFactory.getInstance()
            .createBuilder(environment.project)
            .console
        console.attachToProcess(processHandler)
        processHandler.startNotify()
        return DefaultExecutionResult(console, processHandler)
    }
}

class BambooBuildProcessHandler(
    private val environment: ExecutionEnvironment,
    private val configuration: BambooBuildRunConfiguration
) : ProcessHandler() {

    private val log = Logger.getInstance(BambooBuildProcessHandler::class.java)
    private val parentJob = SupervisorJob()
    private val scope = CoroutineScope(parentJob + Dispatchers.IO)

    init {
        // Cancel coroutine scope when project is disposed to prevent leaks
        Disposer.register(environment.project, { parentJob.cancel() })
    }

    override fun startNotify() {
        super.startNotify()
        scope.launch { runBuild() }
    }

    private suspend fun runBuild() {
        val project = environment.project
        val bambooService = project.getService(BambooService::class.java)

        val planKey = configuration.getPlanKey()
        if (planKey.isBlank()) {
            printOutput("ERROR: No Bamboo plan key configured. Set it in the run configuration or plugin settings.\n")
            destroyProcess()
            return
        }

        val branch = configuration.getBranch()
        val variables = configuration.getBuildVariables()

        // Bamboo branch builds use planKey + branch suffix (e.g., PROJ-PLAN0 for branches)
        // Pass the branch as a build variable so the Bamboo plan can use it
        val effectiveVariables = if (branch.isNotBlank()) {
            variables + ("bamboo.planRepository.1.branch" to branch)
        } else {
            variables
        }

        printOutput("=== Bamboo Build Runner ===\n")
        printOutput("Plan Key: $planKey\n")
        if (branch.isNotBlank()) printOutput("Branch: $branch\n")
        if (effectiveVariables.isNotEmpty()) {
            printOutput("Variables:\n")
            // Branch-routing variable (bamboo.planRepository.1.branch) contains only a branch
            // name and is safe to print; all other variables are masked because the run
            // configuration's plain Map<String,String> carries no isPassword metadata —
            // any of them could be a deploy token or database password.
            effectiveVariables.forEach { (k, v) ->
                val displayValue = if (k == "bamboo.planRepository.1.branch") v else "••••"
                printOutput("  $k = $displayValue\n")
            }
        }
        printOutput("\n")

        val stages = configuration.getStages()
        printOutput("Triggering build...\n")
        if (stages != null) printOutput("Stages: ${stages.joinToString(", ")}\n")

        val triggerResult = bambooService.triggerBuild(planKey, effectiveVariables, stages)
        if (triggerResult.isError) {
            printOutput("ERROR: Failed to trigger build: ${triggerResult.summary}\n")
            destroyProcess()
            return
        }

        val resultKey = triggerResult.data!!.buildKey
        val buildNumber = triggerResult.data!!.buildNumber
        printOutput("Build triggered successfully. Result key: $resultKey (build #$buildNumber)\n\n")
        pollBuildStatus(bambooService, resultKey)
    }

    private suspend fun pollBuildStatus(bambooService: BambooService, resultKey: String) {
        // Use the configured poll interval (PluginSettings.buildPollIntervalSeconds, default 30s)
        // with a floor of 10s so the run configuration respects the same setting as
        // BuildMonitorService rather than using a hardcoded 15s.
        val pollIntervalMs = run {
            val project = environment.project
            val configured = PluginSettings.getInstance(project).state.buildPollIntervalSeconds
            maxOf(configured, MIN_POLL_INTERVAL_SECONDS).toLong() * 1000L
        }

        printOutput("Polling build status every ${pollIntervalMs / 1000}s...\n\n")

        while (isProcessTerminating.not() && isProcessTerminated.not()) {
            val result = bambooService.getBuild(resultKey)
            if (result.isError) {
                printOutput("WARNING: Failed to get build status: ${result.summary}\n")
            } else {
                val data = result.data!!
                val state = data.state

                printOutput("[${java.time.LocalTime.now()}] State: $state\n")

                // Print stage info
                for (stage in data.stages) {
                    printOutput("  Stage '${stage.name}': ${stage.state}\n")
                }

                if (state in TERMINAL_STATES) {
                    printOutput("\n=== Build Finished ===\n")
                    printOutput("Final result: $state\n")
                    printOutput("Duration: ${data.durationSeconds}s\n")
                    destroyProcess()
                    return
                }
            }

            delay(pollIntervalMs)
        }
    }

    companion object {
        private val TERMINAL_STATES = setOf("Successful", "Failed", "Unknown", "Finished")
        /** Floor for the configured poll interval — prevents accidental sub-10s polling. */
        private const val MIN_POLL_INTERVAL_SECONDS = 10
    }

    private fun printOutput(text: String) {
        notifyTextAvailable(text, ProcessOutputTypes.STDOUT)
    }

    override fun destroyProcessImpl() {
        scope.cancel()
        notifyProcessTerminated(0)
    }

    override fun detachProcessImpl() {
        scope.cancel()
        notifyProcessDetached()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null
}
